package com.codearena.submission;

import com.codearena.api.PageResult;
import com.codearena.contest.ContestService;
import com.codearena.problem.ProblemService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class SubmissionService {
    private static final java.util.regex.Pattern REQUEST_KEY = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{16,80}");
    private static final String FROM = """
        FROM submissions s JOIN problems p ON p.id = s.problem_id JOIN users u ON u.id = s.user_id
        LEFT JOIN contest_submissions cs ON cs.submission_id = s.id
        """;
    private static final String SELECT = """
        SELECT s.id, s.problem_id, s.language, s.mode, s.verdict, s.elapsed_ms, s.memory_kb, s.passed_cases,
            s.total_cases, s.created_at, p.title, p.slug, u.username, cs.contest_id
        """;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final ProblemService problems;
    private final ContestService contests;
    private final JudgeGateway judge;
    private final SubmissionAdmission admission;

    public SubmissionService(JdbcClient jdbc, ObjectMapper json, ProblemService problems, ContestService contests,
                             JudgeGateway judge, SubmissionAdmission admission) {
        this.jdbc = jdbc;
        this.json = json;
        this.problems = problems;
        this.contests = contests;
        this.judge = judge;
        this.admission = admission;
    }

    public record Request(@NotNull @Positive Long problemId, @NotBlank @Pattern(regexp = "JAVA|PYTHON|CPP") String language,
                          @NotBlank @Size(max = 24000) String code, @Size(max = 8192) String customInput,
                          @Positive Long contestId) {}
    public record Card(long id, long problemId, String problemTitle, String slug, String username, String language,
                       String mode, String verdict, Integer elapsedMs, Integer memoryKb, Integer passedCases,
                       Integer totalCases, Instant createdAt, Long contestId) {}
    public record Detail(Card submission, String code, String diagnostic, List<JudgeGateway.CaseResult> cases) {}

    @Transactional
    public Card queue(long userId, Request request, boolean submit, String key) {
        if (submit && request.customInput() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use Run for custom input.");
        }
        if (key != null && !REQUEST_KEY.matcher(key).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use 16 to 80 letters, numbers, hyphens, or underscores in Idempotency-Key.");
        }
        String hash = key == null ? null : fingerprint(request, submit);
        jdbc.sql("SELECT id FROM users WHERE id = :id AND enabled FOR UPDATE").param("id", userId).query(Long.class)
            .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "This account is disabled."));
        if (key != null) {
            var existing = jdbc.sql("SELECT id, request_hash FROM submissions WHERE user_id = :user AND request_key = :key")
                .param("user", userId).param("key", key).query((rs, row) -> {
                    if (!hash.equals(rs.getString("request_hash"))) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "This request key was already used for a different job.");
                    }
                    return rs.getLong("id");
                }).optional();
            if (existing.isPresent()) return card(existing.get(), userId);
        }
        if (!judge.configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Code execution is unavailable.");
        }
        long queued = jdbc.sql("""
            SELECT count(*) FROM submissions WHERE user_id = :id AND verdict IN ('PENDING', 'RUNNING')
            """).param("id", userId).query(Long.class).single();
        if (queued >= 3) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Wait for a queued job to finish.");
        if (request.contestId() != null) contests.checkEligibility(request.contestId(), request.problemId(), userId);
        problems.lockForExecution(request.problemId());
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
        admission.check(userId, tests.size());
        String payload = json.writeValueAsString(new JudgeGateway.Spec(problem.timeLimitMs(), problem.memoryLimitMb(), tests));
        long id = jdbc.sql("""
            INSERT INTO submissions (user_id, problem_id, language, mode, code, total_cases, job_payload, request_key, request_hash, created_at)
            VALUES (:user, :problem, :language, :mode, :code, :total, CAST(:payload AS jsonb), :key, :hash, statement_timestamp())
            RETURNING id
            """).param("user", userId).param("problem", request.problemId()).param("language", request.language())
            .param("mode", submit ? "SUBMIT" : "RUN").param("code", request.code()).param("total", tests.size())
            .param("payload", payload).param("key", key).param("hash", hash).query(Long.class).single();
        if (submit && request.contestId() != null) {
            jdbc.sql("INSERT INTO contest_submissions (submission_id, contest_id) VALUES (:id, :contest)")
                .param("id", id).param("contest", request.contestId()).update();
        }
        return card(id, userId);
    }

    private Card card(long id, long viewer) {
        return jdbc.sql(SELECT + FROM + " WHERE s.id = :id AND s.user_id = :viewer")
            .param("id", id).param("viewer", viewer).query(SubmissionService::mapCard).single();
    }

    public record Result(Card submission, String diagnostic, List<JudgeGateway.CaseResult> cases) {}

    public Result result(long id, long viewer) {
        return jdbc.sql(SELECT + ", s.diagnostic, s.details " + FROM + " WHERE s.id = :id AND s.user_id = :viewer")
            .param("id", id).param("viewer", viewer)
            .query((rs, row) -> new Result(mapCard(rs, row), rs.getString("diagnostic"),
                readCases(rs.getString("details"))))
            .optional().orElseThrow(SubmissionService::missing);
    }

    public Detail detail(long id, long viewer, boolean admin) {
        return jdbc.sql(SELECT + ", s.code, s.diagnostic, s.details " + FROM + " WHERE s.id = :id AND (:admin OR s.user_id = :viewer)")
            .param("id", id).param("viewer", viewer).param("admin", admin)
            .query((rs, row) -> new Detail(mapCard(rs, row), rs.getString("code"), rs.getString("diagnostic"),
                readCases(rs.getString("details"))))
            .optional().orElseThrow(SubmissionService::missing);
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

    private static Card mapCard(ResultSet rs, int row) throws SQLException {
        return new Card(rs.getLong("id"), rs.getLong("problem_id"), rs.getString("title"), rs.getString("slug"),
            rs.getString("username"), rs.getString("language"), rs.getString("mode"), rs.getString("verdict"),
            rs.getObject("elapsed_ms", Integer.class), rs.getObject("memory_kb", Integer.class),
            rs.getObject("passed_cases", Integer.class), rs.getObject("total_cases", Integer.class),
            rs.getTimestamp("created_at").toInstant(), rs.getObject("contest_id", Long.class));
    }

    private String fingerprint(Request request, boolean submit) {
        String canonical = (submit ? "submit\n" : "run\n") + json.writeValueAsString(request);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable.", error);
        }
    }

    private List<JudgeGateway.CaseResult> readCases(String value) {
        return value == null ? List.of() : List.of(json.readValue(value, JudgeGateway.CaseResult[].class));
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Submission not found.");
    }
}
