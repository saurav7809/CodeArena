package com.codearena.stats;

import com.codearena.api.PageResult;
import com.codearena.contest.ContestService;
import com.codearena.problem.ProblemService;
import com.codearena.submission.SubmissionService;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StatsService {
    private static final String ELIGIBLE = "verdict NOT IN ('PENDING', 'RUNNING', 'INTERNAL_ERROR')";
    private static final String LEADERBOARD = """
        WITH attempts AS (
            SELECT user_id, count(*) AS judged, count(*) FILTER (WHERE verdict = 'ACCEPTED') AS accepted
            FROM submissions WHERE mode = 'SUBMIT' AND created_at >= :since
                AND verdict NOT IN ('PENDING', 'RUNNING', 'INTERNAL_ERROR') GROUP BY user_id
        ), solved AS (
            SELECT x.user_id, count(*) AS solved, sum(CASE p.difficulty
                WHEN 'EASY' THEN 100 WHEN 'MEDIUM' THEN 200 ELSE 300 END) AS points
            FROM (SELECT DISTINCT user_id, problem_id FROM submissions
                  WHERE mode = 'SUBMIT' AND verdict = 'ACCEPTED' AND created_at >= :since) x
            JOIN problems p ON p.id = x.problem_id GROUP BY x.user_id
        ), ranked AS (
            SELECT u.id, u.username, coalesce(s.solved, 0) AS solved, coalesce(s.points, 0) AS points,
                round(100.0 * a.accepted / a.judged, 1) AS acceptance,
                rank() OVER (ORDER BY coalesce(s.points, 0) DESC, coalesce(s.solved, 0) DESC) AS rank
            FROM attempts a JOIN users u ON u.id = a.user_id LEFT JOIN solved s ON s.user_id = u.id
            WHERE u.enabled
        )
        """;
    private final JdbcClient jdbc;
    private final ProblemService problems;
    private final SubmissionService submissions;
    private final ContestService contests;

    public StatsService(JdbcClient jdbc, ProblemService problems, SubmissionService submissions, ContestService contests) {
        this.jdbc = jdbc;
        this.problems = problems;
        this.submissions = submissions;
        this.contests = contests;
    }

    public record Totals(long problems, long users, long submissions, long activeContests) {}
    public record Rank(long rank, String username, long solved, long points, double acceptance) {}
    public record Counts(long solved, long easy, long medium, long hard, long submissions, double acceptance, Long rank) {}
    public record Day(String date, long count) {}
    public record Bucket(String label, long count) {}
    public record Achievement(String code, String name, String description, Instant earnedAt) {}
    public record Dashboard(Counts counts, List<Day> activity, List<Day> solvedOverTime, List<Day> acceptanceOverTime,
                            List<Bucket> languages, List<Achievement> achievements, List<ProblemService.Card> recommended,
                            List<SubmissionService.Card> recent, List<ContestService.Card> contests) {}
    public record Admin(Totals totals, long accepted, List<Bucket> verdicts, List<Day> activity,
                        List<SubmissionService.Card> recent) {}

    public Totals totals() {
        return jdbc.sql("""
            SELECT (SELECT count(*) FROM problems WHERE published AND NOT archived) AS problems,
                (SELECT count(*) FROM users) AS users,
                (SELECT count(*) FROM submissions WHERE mode = 'SUBMIT') AS submissions,
                (SELECT count(*) FROM contests WHERE state = 'PUBLISHED' AND now() >= starts_at AND now() < ends_at) AS contests
            """).query((rs, row) -> new Totals(rs.getLong("problems"), rs.getLong("users"),
                rs.getLong("submissions"), rs.getLong("contests"))).single();
    }

    public PageResult<Rank> leaderboard(String period, String sort, int page, int size) {
        int offset = PageResult.offset(page, size);
        var today = LocalDate.now(ZoneOffset.UTC);
        Instant since = switch (period) {
            case "global" -> Instant.EPOCH;
            case "weekly" -> today.with(DayOfWeek.MONDAY).atStartOfDay().toInstant(ZoneOffset.UTC);
            case "monthly" -> today.withDayOfMonth(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            default -> throw badRequest("Choose a supported ranking period.");
        };
        String order = switch (sort) {
            case "points" -> "rank, username";
            case "solved" -> "solved DESC, rank, username";
            case "acceptance" -> "acceptance DESC, rank, username";
            default -> throw badRequest("Choose a supported ranking sort.");
        };
        var cutoff = Timestamp.from(since);
        long total = jdbc.sql(LEADERBOARD + "SELECT count(*) FROM ranked").param("since", cutoff).query(Long.class).single();
        var items = jdbc.sql(LEADERBOARD + "SELECT * FROM ranked ORDER BY " + order + " LIMIT :size OFFSET :offset")
            .param("since", cutoff).param("size", size).param("offset", offset).query((rs, row) ->
                new Rank(rs.getLong("rank"), rs.getString("username"), rs.getLong("solved"),
                    rs.getLong("points"), rs.getDouble("acceptance"))).list();
        return new PageResult<>(items, page, size, total);
    }

    @Transactional
    public Dashboard dashboard(long userId) {
        Long rank = jdbc.sql(LEADERBOARD + "SELECT rank FROM ranked WHERE id = :id")
            .param("since", Timestamp.from(Instant.EPOCH)).param("id", userId).query(Long.class).optional().orElse(null);
        Counts counts = jdbc.sql("""
            SELECT count(DISTINCT s.problem_id) FILTER (WHERE s.verdict = 'ACCEPTED') AS solved,
                count(DISTINCT s.problem_id) FILTER (WHERE s.verdict = 'ACCEPTED' AND p.difficulty = 'EASY') AS easy,
                count(DISTINCT s.problem_id) FILTER (WHERE s.verdict = 'ACCEPTED' AND p.difficulty = 'MEDIUM') AS medium,
                count(DISTINCT s.problem_id) FILTER (WHERE s.verdict = 'ACCEPTED' AND p.difficulty = 'HARD') AS hard,
                count(*) AS submissions, coalesce(round(100.0 * count(*) FILTER (WHERE s.verdict = 'ACCEPTED') /
                    nullif(count(*) FILTER (WHERE s.verdict NOT IN ('PENDING', 'RUNNING', 'INTERNAL_ERROR')), 0), 1), 0) AS acceptance
            FROM submissions s JOIN problems p ON p.id = s.problem_id WHERE s.user_id = :id AND s.mode = 'SUBMIT'
            """).param("id", userId).query((rs, row) -> new Counts(rs.getLong("solved"), rs.getLong("easy"),
                rs.getLong("medium"), rs.getLong("hard"), rs.getLong("submissions"), rs.getDouble("acceptance"), rank)).single();
        award(userId, counts.solved());
        var awards = jdbc.sql("""
            SELECT a.*, u.earned_at FROM achievements a LEFT JOIN user_achievements u
              ON u.achievement_code = a.code AND u.user_id = :id ORDER BY u.earned_at NULLS LAST, a.code
            """).param("id", userId).query((rs, row) -> {
                var earned = rs.getTimestamp("earned_at");
                return new Achievement(rs.getString("code"), rs.getString("name"), rs.getString("description"),
                    earned == null ? null : earned.toInstant());
            }).list();
        var solvedOverTime = days("""
            SELECT (first_solve AT TIME ZONE 'UTC')::date AS day, count(*) AS count
            FROM (SELECT min(occurred_at) AS first_solve FROM user_activity a
                  JOIN submissions s ON s.id = a.submission_id WHERE a.user_id = :id AND a.verdict = 'ACCEPTED'
                  GROUP BY s.problem_id) firsts
            GROUP BY day ORDER BY day
            """, userId);
        var acceptance = days("""
            SELECT (occurred_at AT TIME ZONE 'UTC')::date AS day,
                round(100.0 * count(*) FILTER (WHERE verdict = 'ACCEPTED') / count(*)) AS count
            FROM user_activity WHERE user_id = :id AND occurred_at >= now() - interval '90 days' AND
            """ + ELIGIBLE + " GROUP BY day ORDER BY day", userId);
        var languages = jdbc.sql("SELECT language AS label, count(*) FROM user_activity WHERE user_id = :id GROUP BY language ORDER BY language")
            .param("id", userId).query((rs, row) -> new Bucket(rs.getString("label"), rs.getLong("count"))).list();
        return new Dashboard(counts, activity(userId), solvedOverTime, acceptance, languages, awards,
            problems.browse(userId, "", "", "", "UNSOLVED", "difficulty", 0, 4, false).items(),
            submissions.browse(userId, null, "", "", 0, 5, false, null).items(), contests.participation(userId));
    }

    private void award(long userId, long solved) {
        jdbc.sql("""
            INSERT INTO user_achievements (user_id, achievement_code)
            SELECT :id, a.code FROM achievements a
            WHERE (a.code = 'FIRST_SOLVE' AND :solved >= 1) OR (a.code = 'SOLVE_10' AND :solved >= 10)
                OR (a.code = 'SOLVE_50' AND :solved >= 50) OR (a.code = 'SOLVE_100' AND :solved >= 100)
                OR (a.code = 'FIRST_CONTEST' AND EXISTS (
                    SELECT 1 FROM contest_submissions cs JOIN submissions s ON s.id = cs.submission_id
                    JOIN contests c ON c.id = cs.contest_id WHERE s.user_id = :id AND c.state = 'PUBLISHED'
                    AND c.ends_at <= now() AND s.verdict NOT IN ('PENDING', 'RUNNING', 'INTERNAL_ERROR')
                ))
                OR (a.code = 'STREAK_7' AND EXISTS (
                    SELECT 1 FROM (
                        SELECT day - (row_number() OVER (ORDER BY day))::integer AS streak
                        FROM (SELECT DISTINCT (occurred_at AT TIME ZONE 'UTC')::date AS day
                              FROM user_activity WHERE user_id = :id
                              AND verdict NOT IN ('PENDING', 'RUNNING', 'INTERNAL_ERROR')) dates
                    ) streaks GROUP BY streak HAVING count(*) >= 7
                ))
            ON CONFLICT DO NOTHING
            """).param("id", userId).param("solved", solved).update();
    }

    public Admin admin() {
        long accepted = jdbc.sql("SELECT count(*) FROM user_activity WHERE verdict = 'ACCEPTED'").query(Long.class).single();
        var verdicts = jdbc.sql("SELECT verdict AS label, count(*) FROM user_activity GROUP BY verdict ORDER BY count(*) DESC")
            .query((rs, row) -> new Bucket(rs.getString("label"), rs.getLong("count"))).list();
        return new Admin(totals(), accepted, verdicts, activity(0), submissions.browse(0, null, "", "", 0, 8, true, null).items());
    }

    private List<Day> activity(long userId) {
        return days("""
            SELECT (occurred_at AT TIME ZONE 'UTC')::date AS day, count(*) AS count FROM user_activity
            WHERE (:id = 0 OR user_id = :id) AND occurred_at >= now() - interval '90 days'
            GROUP BY day ORDER BY day
            """, userId);
    }

    private List<Day> days(String query, long userId) {
        return jdbc.sql(query).param("id", userId)
            .query((rs, row) -> new Day(rs.getString("day"), rs.getLong("count"))).list();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
