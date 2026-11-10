package com.codearena.problem;

import com.codearena.api.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProblemService {
    public static final List<String> CATEGORIES = List.of("Arrays", "Strings", "Linked Lists", "Trees", "Graphs",
        "Dynamic Programming", "Greedy", "Sorting", "Searching", "Recursion", "Backtracking", "Mathematics", "SQL");
    private static final String SOLVED = """
        EXISTS (SELECT 1 FROM submissions x WHERE x.user_id = :viewer AND x.problem_id = p.id
                AND x.mode = 'SUBMIT' AND x.verdict = 'ACCEPTED')
        """;
    private static final String STATS = """
        LEFT JOIN LATERAL (
            SELECT count(*) AS submissions, count(*) FILTER (WHERE verdict = 'ACCEPTED') AS accepted
            FROM submissions s WHERE s.problem_id = p.id AND s.mode = 'SUBMIT'
        ) totals ON true
        """;
    private static final Map<String, String> STARTERS = Map.of(
        "PYTHON", """
            import sys

            def main():
                data = sys.stdin.read().split()
                # Read the input and write your solution here.
                pass

            if __name__ == "__main__":
                main()
            """,
        "JAVA", """
            import java.io.*;
            import java.util.*;

            public class Main {
                public static void main(String[] args) throws Exception {
                    var input = new BufferedReader(new InputStreamReader(System.in));
                    // Read the input and write your solution here.
                }
            }
            """,
        "CPP", """
            #include <iostream>
            #include <vector>
            using namespace std;

            int main() {
                ios::sync_with_stdio(false);
                cin.tie(nullptr);
                // Read the input and write your solution here.
                return 0;
            }
            """);

    private final JdbcClient jdbc;

    public ProblemService(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record Card(long id, String slug, String title, String difficulty, String category, boolean solved,
                       long submissions, long accepted, boolean published, Instant createdAt) {}
    public record TestCase(int position, String input, String expectedOutput, boolean visible) {}
    public record Detail(Card problem, String description, String inputFormat, String outputFormat,
                         String constraints, String explanation, int timeLimitMs, int memoryLimitMb,
                         Map<String, String> starterCode, List<TestCase> examples, Instant updatedAt) {}
    public record TestInput(@NotNull @Size(max = 8192) String input,
                            @NotNull @Size(max = 8192) String expectedOutput, boolean visible) {}
    public record Edit(
        @NotBlank @Size(max = 100) String title,
        @NotBlank @Size(max = 100) @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") String slug,
        @NotBlank @Size(max = 10000) String description,
        @NotBlank @Pattern(regexp = "EASY|MEDIUM|HARD") String difficulty,
        @NotBlank @Size(max = 40) String category,
        @NotBlank @Size(max = 2000) String inputFormat,
        @NotBlank @Size(max = 2000) String outputFormat,
        @NotBlank @Size(max = 2000) String constraints,
        @Size(max = 2000) String explanation,
        @Min(500) @Max(5000) int timeLimitMs,
        @Min(64) @Max(512) int memoryLimitMb,
        boolean published,
        @NotNull @Size(max = 12) List<@NotNull @Valid TestInput> testCases) {}

    public PageResult<Card> browse(long viewer, String search, String difficulty, String category, String solved,
                                   String sort, int page, int size, boolean admin) {
        int offset = PageResult.offset(page, size);
        String order = switch (sort) {
            case "difficulty" -> "CASE p.difficulty WHEN 'EASY' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END, p.id";
            case "newest" -> "p.created_at DESC, p.id DESC";
            case "number" -> "p.id";
            default -> throw badRequest("Choose a supported sort order.");
        };
        String status = solved.toUpperCase(Locale.ROOT);
        if (!List.of("ALL", "SOLVED", "UNSOLVED").contains(status)) {
            throw badRequest("Choose a supported solved filter.");
        }
        String where = """
            WHERE NOT p.archived AND (:admin OR p.published)
              AND strpos(lower(p.title), lower(:search)) > 0
              AND (:difficulty = '' OR p.difficulty = :difficulty)
              AND (:category = '' OR p.category = :category)
            """;
        if (status.equals("SOLVED")) where += " AND " + SOLVED;
        if (status.equals("UNSOLVED")) where += " AND NOT " + SOLVED;
        var parameters = Map.of("admin", admin, "search", search.strip(), "difficulty",
            difficulty.toUpperCase(Locale.ROOT), "category", category, "viewer", viewer);
        long total = jdbc.sql("SELECT count(*) FROM problems p " + where).params(parameters).query(Long.class).single();
        var rows = jdbc.sql("SELECT p.*, " + SOLVED + " AS solved, totals.* FROM problems p " + STATS + where
            + " ORDER BY " + order + " LIMIT :size OFFSET :offset")
            .params(parameters).param("size", size).param("offset", offset).query(ProblemService::card).list();
        return new PageResult<>(rows, page, size, total);
    }

    public Detail byId(long id, long viewer, boolean admin) {
        return detail("p.id = :key", id, viewer, admin);
    }

    public Detail bySlug(String slug, long viewer) { return detail("p.slug = :key", slug, viewer, false); }

    private Detail detail(String condition, Object key, long viewer, boolean admin) {
        var row = jdbc.sql("SELECT p.*, " + SOLVED + " AS solved, totals.* FROM problems p " + STATS
            + " WHERE NOT p.archived AND (:admin OR p.published) AND " + condition)
            .param("key", key).param("viewer", viewer).param("admin", admin).query((rs, index) -> new Detail(
                card(rs, index), rs.getString("description"), rs.getString("input_format"), rs.getString("output_format"),
                rs.getString("constraints_text"), rs.getString("explanation"), rs.getInt("time_limit_ms"),
                rs.getInt("memory_limit_mb"), STARTERS, List.of(), rs.getTimestamp("updated_at").toInstant()))
            .optional().orElseThrow(ProblemService::missing);
        return new Detail(row.problem(), row.description(), row.inputFormat(), row.outputFormat(), row.constraints(),
            row.explanation(), row.timeLimitMs(), row.memoryLimitMb(), row.starterCode(),
            cases(row.problem().id(), admin), row.updatedAt());
    }

    public List<TestCase> cases(long problemId, boolean includeHidden) {
        return jdbc.sql("""
            SELECT position, input_text, expected_output, visible FROM problem_test_cases
            WHERE problem_id = :id AND (:hidden OR visible) ORDER BY position
            """).param("id", problemId).param("hidden", includeHidden).query((rs, row) -> new TestCase(
                rs.getInt("position"), rs.getString("input_text"), rs.getString("expected_output"),
                rs.getBoolean("visible"))).list();
    }

    @Transactional
    public Detail create(Edit request) {
        validate(request);
        long id = fields("""
            INSERT INTO problems (title, slug, description, difficulty, category, input_format, output_format,
                constraints_text, explanation, time_limit_ms, memory_limit_mb, published)
            VALUES (:title, :slug, :description, :difficulty, :category, :input, :output, :constraints, :explanation,
                :time, :memory, :published) RETURNING id
            """, request).query(Long.class).single();
        writeCases(id, request.testCases());
        return byId(id, 0, true);
    }

    @Transactional
    public Detail update(long id, Edit request) {
        lock(id);
        validate(request);
        if (!request.published()) ensureNoActiveContest(id);
        fields("""
            UPDATE problems SET title = :title, slug = :slug, description = :description, difficulty = :difficulty,
                category = :category, input_format = :input, output_format = :output, constraints_text = :constraints,
                explanation = :explanation, time_limit_ms = :time, memory_limit_mb = :memory, published = :published,
                updated_at = now()
            WHERE id = :id
            """, request).param("id", id).update();
        jdbc.sql("DELETE FROM problem_test_cases WHERE problem_id = :id").param("id", id).update();
        writeCases(id, request.testCases());
        return byId(id, 0, true);
    }

    @Transactional
    public void archive(long id) {
        lock(id);
        ensureNoActiveContest(id);
        jdbc.sql("UPDATE problems SET archived = true, published = false, updated_at = now() WHERE id = :id")
            .param("id", id).update();
    }

    public void lock(long id) {
        jdbc.sql("SELECT id FROM problems WHERE id = :id AND NOT archived FOR UPDATE").param("id", id)
            .query(Long.class).optional().orElseThrow(ProblemService::missing);
    }

    private void ensureNoActiveContest(long id) {
        boolean inContest = jdbc.sql("""
            SELECT EXISTS (SELECT 1 FROM contest_problems cp JOIN contests c ON c.id = cp.contest_id
            WHERE cp.problem_id = :id AND c.state = 'PUBLISHED' AND c.ends_at > now())
            """).param("id", id).query(Boolean.class).single();
        if (inContest) throw new ResponseStatusException(HttpStatus.CONFLICT, "This problem belongs to a published contest.");
    }

    private JdbcClient.StatementSpec fields(String sql, Edit request) {
        return jdbc.sql(sql).param("title", request.title().strip()).param("slug", request.slug())
            .param("description", request.description().strip()).param("difficulty", request.difficulty())
            .param("category", request.category()).param("input", request.inputFormat().strip())
            .param("output", request.outputFormat().strip()).param("constraints", request.constraints().strip())
            .param("explanation", request.explanation() == null ? "" : request.explanation().strip())
            .param("time", request.timeLimitMs()).param("memory", request.memoryLimitMb()).param("published", request.published());
    }

    private void writeCases(long id, List<TestInput> cases) {
        for (int i = 0; i < cases.size(); i++) {
            var test = cases.get(i);
            jdbc.sql("""
                INSERT INTO problem_test_cases (problem_id, position, input_text, expected_output, visible)
                VALUES (:id, :position, :input, :output, :visible)
                """).param("id", id).param("position", i + 1).param("input", test.input())
                .param("output", test.expectedOutput()).param("visible", test.visible()).update();
        }
    }

    private static void validate(Edit request) {
        if (!CATEGORIES.contains(request.category())) throw badRequest("Choose a supported category.");
        if (request.published() && (!request.testCases().stream().anyMatch(TestInput::visible)
            || request.testCases().stream().allMatch(TestInput::visible))) {
            throw badRequest("A published problem needs a sample test and a hidden test.");
        }
    }

    private static Card card(ResultSet rs, int row) throws SQLException {
        return new Card(rs.getLong("id"), rs.getString("slug"), rs.getString("title"), rs.getString("difficulty"),
            rs.getString("category"), rs.getBoolean("solved"), rs.getLong("submissions"), rs.getLong("accepted"),
            rs.getBoolean("published"), rs.getTimestamp("created_at").toInstant());
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Problem not found.");
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
