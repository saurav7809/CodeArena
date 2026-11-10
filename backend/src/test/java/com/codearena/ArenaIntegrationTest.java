package com.codearena;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"arena.execution.max-active=50", "arena.execution.per-user-per-minute=3",
    "arena.execution.daily-cases=10000"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@EnabledIfEnvironmentVariable(named = "CODEARENA_TEST_DB_URL", matches = ".+/codearena_test(?:\\?.*)?")
class ArenaIntegrationTest {
    private static final String TEST_SIGNING_KEY = UUID.randomUUID().toString();

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("CODEARENA_TEST_DB_URL"));
        registry.add("arena.jwt-secret", () -> TEST_SIGNING_KEY);
        registry.add("arena.admin-password", () -> "");
    }

    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;

    @Test
    void catalogUsesMigrationsAndKeepsHiddenTestsPrivate() throws Exception {
        http.perform(get("/api/health")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(header().string("Cache-Control", "no-store"));
        http.perform(get("/api/problems").param("difficulty", "EASY").param("category", "Arrays"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").isNumber())
            .andExpect(jsonPath("$.items[0].category").value("Arrays"));
        http.perform(get("/api/problems/slug/deduplicate-catalog"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.examples.length()").value(2))
            .andExpect(jsonPath("$.examples[0].visible").value(true))
            .andExpect(jsonPath("$.starterCode.JAVA").isString())
            .andExpect(jsonPath("$.starterCode.CPP").isString());
    }

    @Test
    void registrationLoginProfileAndLogoutUseStoredAccount() throws Exception {
        http.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
        http.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString());

        String username = unique("learner");
        JsonNode user = register(username);
        String token = user.get("token").asText();
        assertThat(user.get("user").get("role").asText()).isEqualTo("USER");
        body(post("/api/auth/login"), null, Map.of("identifier", username + "@example.test", "password", "Practice!123"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.user.username").value(username));
        body(put("/api/users/me"), token, Map.of("fullName", "Arena Learner", "bio", "Practicing arrays."))
            .andExpect(status().isOk()).andExpect(jsonPath("$.bio").value("Practicing arrays."));
        http.perform(get("/api/users/" + username)).andExpect(status().isOk()).andExpect(jsonPath("$.email").doesNotExist());
        http.perform(post("/api/auth/logout").header("Authorization", bearer(token))).andExpect(status().isNoContent());
        http.perform(get("/api/auth/me").header("Authorization", bearer(token))).andExpect(status().isUnauthorized());
    }

    @Test
    void adminCanCreateEditArchiveProblemsAndDisableAccounts() throws Exception {
        JsonNode user = register(unique("member"));
        String token = user.get("token").asText();
        http.perform(get("/api/admin/dashboard").header("Authorization", bearer(token))).andExpect(status().isForbidden());
        String admin = admin();
        String slug = unique("test-problem").replace('_', '-');
        var edit = new java.util.HashMap<String, Object>(Map.ofEntries(
            Map.entry("title", "Test problem"), Map.entry("slug", slug), Map.entry("difficulty", "EASY"),
            Map.entry("category", "Arrays"), Map.entry("description", "Print the input."),
            Map.entry("inputFormat", "One integer."), Map.entry("outputFormat", "The integer."),
            Map.entry("constraints", "The integer is positive."), Map.entry("timeLimitMs", 2000),
            Map.entry("memoryLimitMb", 128), Map.entry("published", true), Map.entry("testCases", List.of(
                Map.of("input", "4", "expectedOutput", "4", "visible", true),
                Map.of("input", "1794", "expectedOutput", "1794", "visible", false)))));
        long problemId = read(body(post("/api/admin/problems"), admin, edit).andExpect(status().isCreated()))
            .path("problem").path("id").asLong();
        http.perform(get("/api/problems/slug/" + slug)).andExpect(status().isOk())
            .andExpect(jsonPath("$.examples.length()").value(1));
        edit.put("title", "Edited test problem");
        body(put("/api/admin/problems/" + problemId), admin, edit).andExpect(status().isOk())
            .andExpect(jsonPath("$.problem.title").value("Edited test problem"));
        http.perform(delete("/api/admin/problems/" + problemId).header("Authorization", bearer(admin)))
            .andExpect(status().isNoContent());
        http.perform(get("/api/problems/slug/" + slug)).andExpect(status().isNotFound());

        body(put("/api/admin/users/" + user.path("user").path("id").asLong() + "/access"), admin,
            Map.of("role", "USER", "enabled", false)).andExpect(status().isOk());
        http.perform(get("/api/users/me").header("Authorization", bearer(token))).andExpect(status().isUnauthorized());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "CODEARENA_TEST_JUDGE", matches = "1")
    void realJudgePersistsVerdictsUpdatesDashboardAndScoresOnlyContestSubmits() throws Exception {
        String username = unique("contestant");
        String token = register(username).path("token").asText();
        String admin = admin();
        long problem = read(http.perform(get("/api/problems/slug/deduplicate-catalog")).andExpect(status().isOk()))
            .path("problem").path("id").asLong();
        Instant now = Instant.now();
        long contest = read(body(post("/api/admin/contests"), admin, Map.of(
            "name", unique("Integration round"), "description", "A real integration test round.",
            "startsAt", now.minusSeconds(120).toString(), "endsAt", now.plusSeconds(1200).toString(),
            "state", "PUBLISHED", "penaltyMinutes", 7, "problems", List.of(Map.of("problemId", problem, "points", 120))))
            .andExpect(status().isCreated())).path("contest").path("id").asLong();
        var good = Map.of("problemId", problem, "contestId", contest, "language", "PYTHON", "code",
            "import sys\n_, *values = sys.stdin.read().split()\nprint(' '.join(dict.fromkeys(values)))");
        body(post("/api/submissions/submit"), token, good).andExpect(status().isForbidden());
        http.perform(post("/api/contests/" + contest + "/register").header("Authorization", bearer(token)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.contest.registered").value(true));

        long run = queue("run", token, good);
        JsonNode ran = awaitVerdict(run, token);
        assertThat(ran.path("submission").path("verdict").asText()).isEqualTo("ACCEPTED");
        assertThat(ran.path("cases").size()).isEqualTo(2);
        http.perform(get("/api/submissions").header("Authorization", bearer(token)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));

        long wrong = queue("submit", token, Map.of("problemId", problem, "contestId", contest, "language", "PYTHON",
            "code", "print('incorrect')"));
        assertThat(awaitVerdict(wrong, token).path("submission").path("verdict").asText()).isEqualTo("WRONG_ANSWER");

        long accepted = queue("submit", token, good);
        JsonNode result = awaitVerdict(accepted, token);
        assertThat(result.path("submission").path("verdict").asText()).isEqualTo("ACCEPTED");
        assertThat(result.path("submission").path("totalCases").asInt()).isEqualTo(4);
        assertThat(result.path("cases").size()).isEqualTo(2);
        assertThat(result.toString()).doesNotContain("Desk desk DESK");
        http.perform(get("/api/submissions").header("Authorization", bearer(token)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
        http.perform(get("/api/users/me/stats").header("Authorization", bearer(token)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.counts.solved").value(1))
            .andExpect(jsonPath("$.counts.acceptance").value(50.0));
        JsonNode ranks = read(http.perform(get("/api/contests/" + contest + "/leaderboard")).andExpect(status().isOk()));
        JsonNode rank = ranks.path("items").get(0);
        assertThat(rank.path("username").asText()).isEqualTo(username);
        assertThat(rank.path("points").asInt()).isEqualTo(120);
        assertThat(rank.path("penalty").asInt()).isGreaterThanOrEqualTo(7);
        body(post("/api/submissions/run"), token, good).andExpect(status().isTooManyRequests());
        http.perform(get("/api/submissions/" + accepted).header("Authorization", bearer(register(unique("observer")).path("token").asText())))
            .andExpect(status().isNotFound());
    }

    private long queue(String action, String token, Map<String, ?> data) throws Exception {
        return read(body(post("/api/submissions/" + action), token, data).andExpect(status().isAccepted())).path("id").asLong();
    }

    private JsonNode awaitVerdict(long id, String token) throws Exception {
        for (int attempt = 0; attempt < 120; attempt++) {
            JsonNode response = read(http.perform(get("/api/submissions/" + id).header("Authorization", bearer(token)))
                .andExpect(status().isOk()));
            if (!List.of("PENDING", "RUNNING").contains(response.path("submission").path("verdict").asText())) return response;
            Thread.sleep(250);
        }
        throw new AssertionError("The submission did not finish.");
    }

    private JsonNode register(String username) throws Exception {
        return read(body(post("/api/auth/register"), null, Map.of("fullName", "Test Learner", "username", username,
            "email", username + "@example.test", "password", "Practice!123", "confirmPassword", "Practice!123"))
            .andExpect(status().isCreated()));
    }

    private String admin() throws Exception {
        String username = unique("testadmin");
        long id = register(username).path("user").path("id").asLong();
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE id = :id").param("id", id).update();
        return read(body(post("/api/auth/login"), null, Map.of("identifier", username, "password", "Practice!123"))
            .andExpect(status().isOk())).path("token").asText();
    }

    private ResultActions body(MockHttpServletRequestBuilder request, String token, Map<String, ?> data) throws Exception {
        if (token != null) request.header("Authorization", bearer(token));
        return http.perform(request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(data)));
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsByteArray());
    }

    private static String bearer(String token) { return "Bearer " + token; }

    private static String unique(String prefix) { return prefix + "_" + UUID.randomUUID().toString().substring(0, 8); }
}
