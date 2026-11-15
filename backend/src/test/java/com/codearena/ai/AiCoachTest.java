package com.codearena.ai;

import com.codearena.career.CareerService;
import com.codearena.problem.ProblemService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AiCoachTest {
    private final ProblemService problems = mock(ProblemService.class);
    private final CareerService career = mock(CareerService.class);
    private final AiBudget budget = mock(AiBudget.class);
    private MockRestServiceServer server;
    private AiCoach coach;

    @BeforeEach
    void configure() {
        var builder = RestClient.builder().baseUrl("https://api.openai.com/v1");
        server = MockRestServiceServer.bindTo(builder).build();
        var provider = new AiProvider(builder.build(), "test-key", "test-model", 1, new SimpleMeterRegistry());
        coach = new AiCoach(provider, budget, problems, career, JsonMapper.builder().build());
    }

    @Test
    void reviewSendsOnlyTheDraftAndVisibleSamplesAndReadsTheAssistantMessage() {
        var problem = new ProblemService.Card(1, "example", "An example", "EASY", "Arrays",
            false, 0, 0, true, Instant.EPOCH);
        var detail = new ProblemService.Detail(problem, "Print the first number.", "One number.", "Print it.",
            "Always positive.", "", 2000, 128, Map.of(),
            List.of(new ProblemService.TestCase(1, "42", "42", true),
                new ProblemService.TestCase(2, "6007", "6007", false)), Instant.EPOCH, true);
        when(problems.byId(1, 4, false)).thenReturn(detail);
        server.expect(requestTo("https://api.openai.com/v1/responses"))
            .andExpect(header("Authorization", "Bearer test-key"))
            .andExpect(jsonPath("$.store").value(false))
            .andExpect(jsonPath("$.input", containsString("print(42)")))
            .andExpect(jsonPath("$.input", containsString("\"expectedOutput\":\"42\"")))
            .andExpect(request -> assertThat(((org.springframework.mock.http.client.MockClientHttpRequest) request)
                .getBodyAsString()).doesNotContain("6007"))
            .andRespond(withSuccess("""
                {"status":"completed","output":[{"type":"reasoning"},{"type":"message","role":"assistant",
                  "content":[{"type":"output_text","text":"Read the number from input."}]}]}
                """, MediaType.APPLICATION_JSON));

        var reply = coach.practice(4, new AiCoach.PracticeRequest(1L, AiCoach.Action.REVIEW, null, "PYTHON", "print(42)"));
        assertThat(reply.text()).isEqualTo("Read the number from input.");
        verify(budget).reserve(4);
        server.verify();
    }

    @Test
    void careerPlanUsesTheSelectedTrackAndItsCompletedSkills() {
        var skill = new CareerService.Skill("api", "HTTP APIs", "Build endpoints.", List.of());
        var track = new CareerService.Track("backend", "Backend", "Build services.",
            List.of(new CareerService.Milestone("Implement", "Ship an API.", List.of(skill))));
        when(career.track("backend")).thenReturn(track);
        when(career.completedSkills(4)).thenReturn(Set.of("api", "different-track"));
        server.expect(requestTo("https://api.openai.com/v1/responses"))
            .andExpect(jsonPath("$.input", containsString("\"completedSkills\":[\"HTTP APIs\"]")))
            .andExpect(jsonPath("$.input", containsString("\"weeklyHours\":6")))
            .andExpect(jsonPath("$.input", containsString("Internship")))
            .andRespond(withSuccess("""
                {"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Week 1: Build a project."}]}]}
                """, MediaType.APPLICATION_JSON));

        var reply = coach.career(4, new AiCoach.CareerRequest("backend", AiCoach.Experience.SOME_PRACTICE, 6, "Internship", true));
        assertThat(reply.text()).contains("Week 1");
        verify(budget).reserve(4);
        verify(career).savePlan(4, "backend", reply.text());
        server.verify();
    }

    @Test
    void anIncompleteOpenAiResponseCannotOverwriteAnExistingPlan() {
        var track = new CareerService.Track("backend", "Backend", "Build services.", List.of());
        when(career.track("backend")).thenReturn(track);
        when(career.completedSkills(4)).thenReturn(Set.of());
        server.expect(requestTo("https://api.openai.com/v1/responses")).andRespond(withSuccess("""
            {"status":"incomplete","output":[{"type":"message","role":"assistant",
              "content":[{"type":"output_text","text":"Week 1"}]}]}
            """, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> coach.career(4, new AiCoach.CareerRequest("backend", AiCoach.Experience.BEGINNER, 4, "", true)))
            .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(503));
        verify(career, never()).savePlan(anyLong(), anyString(), anyString());
        server.verify();
    }
}
