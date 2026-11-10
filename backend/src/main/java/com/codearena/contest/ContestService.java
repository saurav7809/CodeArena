package com.codearena.contest;

import com.codearena.api.PageResult;
import com.codearena.problem.ProblemService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ContestService {
    private static final String STATUS = """
        CASE WHEN c.state IN ('DRAFT', 'CANCELLED') THEN c.state
             WHEN now() < c.starts_at THEN 'UPCOMING'
             WHEN now() < c.ends_at THEN 'ONGOING' ELSE 'PAST' END
        """;
    private static final String SELECT = """
        SELECT c.*, (%s) AS status,
            (SELECT count(*) FROM contest_participants cp WHERE cp.contest_id = c.id) AS participant_count,
            (SELECT count(*) FROM contest_problems cp WHERE cp.contest_id = c.id) AS problem_count,
            EXISTS (SELECT 1 FROM contest_participants cp WHERE cp.contest_id = c.id AND cp.user_id = :viewer) AS registered
        FROM contests c
        """.formatted(STATUS);
    private static final String RANKS = """
        WITH judged AS (
            SELECT s.* FROM submissions s JOIN contest_submissions cs ON cs.submission_id = s.id
            WHERE cs.contest_id = :id AND s.verdict NOT IN ('PENDING', 'RUNNING', 'INTERNAL_ERROR')
        ), first_solves AS (
            SELECT DISTINCT ON (user_id, problem_id) user_id, problem_id, id, created_at
            FROM judged WHERE verdict = 'ACCEPTED' ORDER BY user_id, problem_id, created_at, id
        ), scores AS (
            SELECT f.user_id, count(*) AS solved, sum(cp.points) AS points,
                sum(floor(extract(epoch FROM (f.created_at - c.starts_at)) / 60)
                    + c.penalty_minutes * (SELECT count(*) FROM judged j
                      WHERE j.user_id = f.user_id AND j.problem_id = f.problem_id AND j.verdict <> 'ACCEPTED'
                        AND (j.created_at, j.id) < (f.created_at, f.id))) AS penalty
            FROM first_solves f JOIN contest_problems cp ON cp.contest_id = :id AND cp.problem_id = f.problem_id
            JOIN contests c ON c.id = :id GROUP BY f.user_id
        )
        SELECT rank() OVER (ORDER BY coalesce(s.points, 0) DESC, coalesce(s.penalty, 0)) AS rank,
            u.username, coalesce(s.solved, 0) AS solved, coalesce(s.points, 0) AS points,
            coalesce(s.penalty, 0) AS penalty,
            coalesce((SELECT round(100.0 * count(*) FILTER (WHERE j.verdict = 'ACCEPTED')
                / nullif(count(*), 0), 1) FROM judged j WHERE j.user_id = u.id), 0) AS acceptance
        FROM contest_participants cp JOIN users u ON u.id = cp.user_id
        LEFT JOIN scores s ON s.user_id = u.id
        WHERE cp.contest_id = :id AND u.enabled
        ORDER BY rank, u.username LIMIT :size OFFSET :offset
        """;
    private final JdbcClient jdbc;
    private final ProblemService problems;

    public ContestService(JdbcClient jdbc, ProblemService problems) {
        this.jdbc = jdbc;
        this.problems = problems;
    }

    public record Card(long id, String name, String description, Instant startsAt, Instant endsAt, int penaltyMinutes,
                       String state, String status, long participantCount, long problemCount, boolean registered) {}
    public record Entry(long problemId, String slug, String title, String difficulty, int points, boolean published) {}
    public record Detail(Card contest, List<Entry> problems) {}
    public record ProblemPoints(@Positive long problemId, @Min(1) @Max(1000) int points) {}
    public record Edit(@NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 3000) String description,
                       @NotNull Instant startsAt, @NotNull Instant endsAt, @Min(0) @Max(120) int penaltyMinutes,
                       @NotBlank @Pattern(regexp = "DRAFT|PUBLISHED|CANCELLED") String state,
                       @NotNull @Size(max = 12) List<@NotNull @Valid ProblemPoints> problems) {}
    public record Rank(long rank, String username, long solved, long points, long penalty, double acceptance) {}
    public record Participant(String username, Instant registeredAt) {}

    public PageResult<Card> browse(long viewer, String status, int page, int size, boolean admin) {
        int offset = PageResult.offset(page, size);
        String filter = status.toUpperCase(Locale.ROOT);
        if (!List.of("ALL", "UPCOMING", "ONGOING", "PAST", "DRAFT", "CANCELLED").contains(filter)) {
            throw badRequest("Choose a supported contest status.");
        }
        String where = " WHERE (:admin OR c.state = 'PUBLISHED') AND (:status = 'ALL' OR (" + STATUS + ") = :status)";
        long total = jdbc.sql("SELECT count(*) FROM contests c" + where).param("admin", admin).param("status", filter)
            .query(Long.class).single();
        var items = jdbc.sql(SELECT + where + " ORDER BY c.starts_at, c.id LIMIT :size OFFSET :offset")
            .param("viewer", viewer).param("admin", admin).param("status", filter).param("size", size).param("offset", offset)
            .query(ContestService::mapCard).list();
        return new PageResult<>(items, page, size, total);
    }

    public Detail detail(long id, long viewer, boolean admin) {
        Card contest = find(id, viewer, admin);
        boolean showProblems = admin || contest.status().equals("PAST")
            || (contest.status().equals("ONGOING") && contest.registered());
        return new Detail(contest, showProblems ? entries(id) : List.of());
    }

    @Transactional
    public Detail register(long id, long viewer) {
        lock(id, "FOR SHARE");
        Card contest = find(id, viewer, false);
        if (contest.status().equals("PAST")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Registration has closed.");
        }
        jdbc.sql("""
            INSERT INTO contest_participants (contest_id, user_id) VALUES (:id, :viewer) ON CONFLICT DO NOTHING
            """).param("id", id).param("viewer", viewer).update();
        return detail(id, viewer, false);
    }

    public void checkEligibility(long contestId, long problemId, long viewer) {
        lock(contestId, "FOR SHARE");
        Card contest = find(contestId, viewer, false);
        if (!contest.status().equals("ONGOING") || !contest.registered()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Register and wait for the contest to begin before submitting.");
        }
        boolean linked = jdbc.sql("SELECT EXISTS (SELECT 1 FROM contest_problems WHERE contest_id = :id AND problem_id = :problem)")
            .param("id", contestId).param("problem", problemId).query(Boolean.class).single();
        if (!linked) throw badRequest("That problem is outside this contest.");
    }

    public PageResult<Rank> leaderboard(long id, int page, int size) {
        int offset = PageResult.offset(page, size);
        find(id, 0, false);
        long total = jdbc.sql("""
            SELECT count(*) FROM contest_participants cp JOIN users u ON u.id = cp.user_id
            WHERE cp.contest_id = :id AND u.enabled
            """).param("id", id).query(Long.class).single();
        var rows = jdbc.sql(RANKS).param("id", id).param("size", size).param("offset", offset).query((rs, row) ->
            new Rank(rs.getLong("rank"), rs.getString("username"), rs.getLong("solved"), rs.getLong("points"),
                rs.getLong("penalty"), rs.getDouble("acceptance"))).list();
        return new PageResult<>(rows, page, size, total);
    }

    public PageResult<Participant> participants(long id, int page, int size) {
        int offset = PageResult.offset(page, size);
        Card contest = find(id, 0, true);
        var rows = jdbc.sql("""
            SELECT u.username, cp.registered_at FROM contest_participants cp JOIN users u ON u.id = cp.user_id
            WHERE cp.contest_id = :id ORDER BY cp.registered_at, u.id LIMIT :size OFFSET :offset
            """).param("id", id).param("size", size).param("offset", offset).query((rs, row) ->
                new Participant(rs.getString("username"), rs.getTimestamp("registered_at").toInstant())).list();
        return new PageResult<>(rows, page, size, contest.participantCount());
    }

    public List<Card> participation(long userId) {
        return jdbc.sql(SELECT + """
            JOIN contest_participants joined ON joined.contest_id = c.id AND joined.user_id = :viewer
            WHERE c.state <> 'DRAFT' ORDER BY c.starts_at DESC LIMIT 8
            """).param("viewer", userId).query(ContestService::mapCard).list();
    }

    @Transactional
    public Detail create(Edit edit) {
        validate(edit);
        lockProblems(edit);
        long id = fields("""
            INSERT INTO contests (name, description, starts_at, ends_at, penalty_minutes, state)
            VALUES (:name, :description, :start, :end, :penalty, :state) RETURNING id
            """, edit).query(Long.class).single();
        writeEntries(id, edit.problems());
        return detail(id, 0, true);
    }

    @Transactional
    public Detail update(long id, Edit edit) {
        lock(id, "FOR UPDATE");
        Card current = find(id, 0, true);
        validate(edit);
        boolean started = !current.state().equals("DRAFT") && !current.startsAt().isAfter(Instant.now());
        if (started) {
            var previous = entries(id).stream().map(item -> new ProblemPoints(item.problemId(), item.points())).toList();
            if (!edit.startsAt().equals(current.startsAt()) || !edit.endsAt().equals(current.endsAt())
                || edit.penaltyMinutes() != current.penaltyMinutes() || !previous.equals(edit.problems())
                || edit.state().equals("DRAFT") || (current.state().equals("CANCELLED") && !edit.state().equals("CANCELLED"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The schedule, problems, and scoring are locked after the contest starts.");
            }
        } else {
            lockProblems(edit);
            jdbc.sql("DELETE FROM contest_problems WHERE contest_id = :id").param("id", id).update();
            writeEntries(id, edit.problems());
        }
        fields("""
            UPDATE contests SET name = :name, description = :description, starts_at = :start, ends_at = :end,
                penalty_minutes = :penalty, state = :state, updated_at = now() WHERE id = :id
            """, edit).param("id", id).update();
        return detail(id, 0, true);
    }

    private Card find(long id, long viewer, boolean admin) {
        return jdbc.sql(SELECT + " WHERE c.id = :id AND (:admin OR c.state = 'PUBLISHED')")
            .param("id", id).param("viewer", viewer).param("admin", admin)
            .query(ContestService::mapCard).optional().orElseThrow(ContestService::missing);
    }

    private void lock(long id, String lockMode) {
        jdbc.sql("SELECT id FROM contests WHERE id = :id " + lockMode).param("id", id)
            .query(Long.class).optional().orElseThrow(ContestService::missing);
    }

    private void lockProblems(Edit edit) {
        for (long problemId : edit.problems().stream().map(ProblemPoints::problemId).sorted().toList()) {
            problems.lock(problemId);
            problems.byId(problemId, 0, !edit.state().equals("PUBLISHED"));
        }
    }

    private List<Entry> entries(long id) {
        return jdbc.sql("""
            SELECT p.id, p.slug, p.title, p.difficulty, p.published, cp.points
            FROM contest_problems cp JOIN problems p ON p.id = cp.problem_id
            WHERE cp.contest_id = :id ORDER BY cp.position
            """).param("id", id).query((rs, row) -> new Entry(rs.getLong("id"), rs.getString("slug"),
                rs.getString("title"), rs.getString("difficulty"), rs.getInt("points"), rs.getBoolean("published"))).list();
    }

    private void writeEntries(long id, List<ProblemPoints> entries) {
        for (int i = 0; i < entries.size(); i++) {
            ProblemPoints entry = entries.get(i);
            jdbc.sql("INSERT INTO contest_problems (contest_id, problem_id, position, points) VALUES (:id, :problem, :position, :points)")
                .param("id", id).param("problem", entry.problemId()).param("position", i + 1).param("points", entry.points()).update();
        }
    }

    private JdbcClient.StatementSpec fields(String sql, Edit edit) {
        return jdbc.sql(sql).param("name", edit.name().strip()).param("description", edit.description().strip())
            .param("start", Timestamp.from(edit.startsAt())).param("end", Timestamp.from(edit.endsAt()))
            .param("penalty", edit.penaltyMinutes()).param("state", edit.state());
    }

    private static void validate(Edit edit) {
        if (!edit.startsAt().isBefore(edit.endsAt())) throw badRequest("The contest must end after it starts.");
        if (edit.state().equals("PUBLISHED") && edit.problems().isEmpty()) throw badRequest("Add a problem before publishing.");
        var seen = new HashSet<Long>();
        for (ProblemPoints item : edit.problems()) {
            if (!seen.add(item.problemId())) throw badRequest("Add each problem only once.");
        }
    }

    private static Card mapCard(ResultSet rs, int row) throws SQLException {
        return new Card(rs.getLong("id"), rs.getString("name"), rs.getString("description"),
            rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant(),
            rs.getInt("penalty_minutes"), rs.getString("state"), rs.getString("status"),
            rs.getLong("participant_count"), rs.getLong("problem_count"), rs.getBoolean("registered"));
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Contest not found.");
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
