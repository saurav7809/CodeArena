package com.codearena.submission;

import com.codearena.api.PageResult;
import com.codearena.contest.ContestService;
import com.codearena.problem.ProblemService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SubmissionService {
    static final Set<String> FINAL = Set.of("ACCEPTED", "WRONG_ANSWER", "COMPILATION_ERROR", "RUNTIME_ERROR",
        "TIME_LIMIT_EXCEEDED", "MEMORY_LIMIT_EXCEEDED", "INTERNAL_ERROR");
    private static final String FROM = """
        FROM submissions s JOIN problems p ON p.id = s.problem_id JOIN users u ON u.id = s.user_id
        LEFT JOIN contest_submissions cs ON cs.submission_id = s.id
        """;
    private static final String SELECT = "SELECT s.*, p.title, p.slug, u.username, cs.contest_id ";
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final ProblemService problems;
    private final ContestService contests;
    private final JudgeGateway judge;

    public SubmissionService(JdbcClient jdbc, ObjectMapper json, ProblemService problems, ContestService contests,
                             JudgeGateway judge) {
        this.jdbc = jdbc;
        this.json = json;
        this.problems = problems;
        this.contests = contests;
        this.judge = judge;
    }

    public record Request(@NotNull @Positive Long problemId, @NotBlank @Pattern(regexp = "JAVA|PYTHON|CPP") String language,
                          @NotBlank @Size(max = 24000) String code, @Size(max = 8192) String customInput,
                          @Positive Long contestId) {}
    public record Card(long id, long problemId, String problemTitle, String slug, String username, String language,
                       String mode, String verdict, Integer elapsedMs, Integer memoryKb, Integer passedCases,
                       Integer totalCases, Instant createdAt, Long contestId) {}
    public record Detail(Card submission, String code, String diagnostic, List<JudgeGateway.CaseResult> cases) {}
    record Pending(long id, String language, String code, JudgeGateway.Spec spec) {}

    @Transactional
    public Card queue(long userId, Request request, boolean submit) {
        if (!judge.configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Configure the local judge before running code.");
        }
        if (submit && request.customInput() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use Run for custom input.");
        }
        jdbc.sql("SELECT id FROM users WHERE id = :id AND enabled FOR UPDATE").param("id", userId).query(Long.class)
            .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "This account is disabled."));
        long queued = jdbc.sql("""
            SELECT count(*) FROM submissions WHERE user_id = :id AND verdict IN ('PENDING', 'RUNNING')
            """).param("id", userId).query(Long.class).single();
        if (queued >= 3) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Wait for a queued job to finish.");
        if (request.contestId() != null) contests.checkEligibility(request.contestId(), request.problemId(), userId);
        problems.lock(request.problemId());
        var problem = problems.byId(request.problemId(), userId, false);
        List<JudgeGateway.Test> tests;
        if (request.customInput() != null) {
            tests = List.of(new JudgeGateway.Test(1, request.customInput(), null, true));
        } else {
            var cases = submit ? problems.cases(request.problemId(), true) : problem.examples();
            tests = cases.stream().map(test -> new JudgeGateway.Test(
                test.position(), test.input(), test.expectedOutput(), test.visible())).toList();
        }
        if (tests.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "This problem has no runnable tests.");
        String payload = encode(new JudgeGateway.Spec(problem.timeLimitMs(), problem.memoryLimitMb(), tests));
        long id = jdbc.sql("""
            INSERT INTO submissions (user_id, problem_id, language, mode, code, total_cases, job_payload)
            VALUES (:user, :problem, :language, :mode, :code, :total, CAST(:payload AS jsonb)) RETURNING id
            """).param("user", userId).param("problem", request.problemId()).param("language", request.language())
            .param("mode", submit ? "SUBMIT" : "RUN").param("code", request.code()).param("total", tests.size())
            .param("payload", payload).query(Long.class).single();
        if (submit && request.contestId() != null) {
            jdbc.sql("INSERT INTO contest_submissions (submission_id, contest_id) VALUES (:id, :contest)")
                .param("id", id).param("contest", request.contestId()).update();
        }
        return detail(id, userId, false).submission();
    }

    public Detail detail(long id, long viewer, boolean admin) {
        return jdbc.sql(SELECT + FROM + " WHERE s.id = :id AND (:admin OR s.user_id = :viewer)")
            .param("id", id).param("viewer", viewer).param("admin", admin)
            .query((rs, row) -> new Detail(mapCard(rs, row), rs.getString("code"), rs.getString("diagnostic"),
                rs.getString("details") == null ? List.of() : readCases(rs.getString("details"))))
            .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Submission not found."));
    }

    public PageResult<Card> browse(long viewer, Long problemId, String language, String verdict, int page, int size,
                                   boolean admin, Long userId) {
        int offset = PageResult.offset(page, size);
        String where = """
            WHERE s.mode = 'SUBMIT' AND (:admin OR s.user_id = :viewer)
              AND (:problem = 0 OR s.problem_id = :problem)
              AND (:user = 0 OR s.user_id = :user)
              AND (:language = '' OR s.language = :language)
              AND (:verdict = '' OR s.verdict = :verdict)
            """;
        var parameters = java.util.Map.of("admin", admin, "viewer", viewer, "problem", problemId == null ? 0L : problemId,
            "user", userId == null ? 0L : userId, "language", language.toUpperCase(Locale.ROOT),
            "verdict", verdict.toUpperCase(Locale.ROOT));
        long total = jdbc.sql("SELECT count(*) " + FROM + where).params(parameters).query(Long.class).single();
        var rows = jdbc.sql(SELECT + FROM + where + " ORDER BY s.created_at DESC, s.id DESC LIMIT :size OFFSET :offset")
            .params(parameters).param("size", size).param("offset", offset).query(SubmissionService::mapCard).list();
        return new PageResult<>(rows, page, size, total);
    }

    Pending claim() {
        return jdbc.sql("""
            UPDATE submissions SET verdict = 'RUNNING', started_at = now() WHERE id = (
                SELECT id FROM submissions WHERE verdict = 'PENDING' ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
            ) RETURNING id, language, code, job_payload
            """).query((rs, row) -> new Pending(rs.getLong("id"), rs.getString("language"), rs.getString("code"),
                decode(rs.getString("job_payload"), JudgeGateway.Spec.class))).optional().orElse(null);
    }

    void finish(Pending pending, JudgeGateway.Result result) {
        if (result == null || result.verdict() == null || !FINAL.contains(result.verdict())
            || result.totalCases() != pending.spec().cases().size() || result.passedCases() < 0
            || result.passedCases() > result.totalCases()) {
            fail(pending.id(), "The judge returned an invalid result.");
            return;
        }
        Set<Integer> visible = pending.spec().cases().stream().filter(JudgeGateway.Test::visible)
            .map(JudgeGateway.Test::position).collect(Collectors.toSet());
        var cases = result.cases() == null ? List.of() : result.cases().stream()
            .filter(test -> visible.contains(test.position())).toList();
        String diagnostic = switch (result.verdict()) {
            case "COMPILATION_ERROR", "INTERNAL_ERROR" -> result.diagnostic();
            default -> null;
        };
        jdbc.sql("""
            UPDATE submissions SET verdict = :verdict, elapsed_ms = :elapsed, memory_kb = :memory,
                passed_cases = :passed, total_cases = :total, details = CAST(:details AS jsonb),
                diagnostic = :diagnostic, job_payload = NULL, finished_at = now()
            WHERE id = :id AND verdict = 'RUNNING'
            """).param("id", pending.id()).param("verdict", result.verdict()).param("elapsed", result.elapsedMs())
            .param("memory", result.memoryKb()).param("passed", result.passedCases()).param("total", result.totalCases())
            .param("details", encode(cases)).param("diagnostic", diagnostic).update();
    }

    void fail(long id, String diagnostic) {
        jdbc.sql("""
            UPDATE submissions SET verdict = 'INTERNAL_ERROR', diagnostic = :diagnostic, job_payload = NULL,
                finished_at = now() WHERE id = :id AND verdict = 'RUNNING'
            """).param("id", id).param("diagnostic", diagnostic).update();
    }

    void recoverStaleJobs() {
        jdbc.sql("""
            UPDATE submissions SET verdict = 'INTERNAL_ERROR', diagnostic = 'The execution worker stopped before reporting.',
                job_payload = NULL, finished_at = now()
            WHERE verdict = 'RUNNING' AND started_at < now() - interval '3 minutes'
            """).update();
    }

    private static Card mapCard(ResultSet rs, int row) throws SQLException {
        return new Card(rs.getLong("id"), rs.getLong("problem_id"), rs.getString("title"), rs.getString("slug"),
            rs.getString("username"), rs.getString("language"), rs.getString("mode"), rs.getString("verdict"),
            rs.getObject("elapsed_ms", Integer.class), rs.getObject("memory_kb", Integer.class),
            rs.getObject("passed_cases", Integer.class), rs.getObject("total_cases", Integer.class),
            rs.getTimestamp("created_at").toInstant(), rs.getObject("contest_id", Long.class));
    }

    private String encode(Object data) {
        try { return json.writeValueAsString(data); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Could not store judge data.", error); }
    }

    private <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Could not read judge data.", error); }
    }

    private List<JudgeGateway.CaseResult> readCases(String value) {
        return List.of(decode(value, JudgeGateway.CaseResult[].class));
    }
}
