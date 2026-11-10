package com.codearena.submission;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class HostedJudgeGatewayTest {
    private static final String BASE = "https://judge.example.com";
    private static final String BATCH = BASE + "/submissions/batch";
    private static final String TOKEN_RESPONSE = "[{\"token\":\"sample-token\"},{\"token\":\"hidden-token\"}]";
    private MockRestServiceServer server;
    private RestClient client;
    private HostedJudgeSettings settings;
    private HostedJudgeGateway gateway;

    @BeforeEach
    void connect() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
        settings = new HostedJudgeSettings(BASE, "test-judge-key", HostedJudgeSettings.Auth.TOKEN, 71, 91, 105);
        gateway = new HostedJudgeGateway(client, settings, Duration.ofSeconds(2), Duration.ZERO);
    }

    @Test
    void gradesInInputOrderAndReturnsOnlyVisibleCaseOutput() {
        server.expect(requestTo(BATCH + "?base64_encoded=true")).andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.submissions[0].language_id").value(71))
            .andExpect(jsonPath("$.submissions[0].source_code").value(encode("print(input())")))
            .andExpect(jsonPath("$.submissions[1].stdin").value(encode("hidden input")))
            .andExpect(jsonPath("$.submissions[0].enable_network").value(false))
            .andExpect(jsonPath("$.submissions[1].enable_network").value(false))
            .andExpect(jsonPath("$.submissions[0].cpu_time_limit").value(2.0))
            .andExpect(jsonPath("$.submissions[1].memory_limit").value(131072))
            .andExpect(jsonPath("$.submissions[0].expected_output").doesNotExist())
            .andExpect(jsonPath("$.submissions[1].expected_output").doesNotExist())
            .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(TOKEN_RESPONSE));
        server.expect(requestTo(startsWith(BATCH + "?"))).andExpect(method(HttpMethod.GET))
            .andExpect(queryParam("tokens", "sample-token,hidden-token"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(startsWith(BATCH + "?"))).andExpect(method(HttpMethod.GET)).andRespond(withSuccess("""
            {"submissions":[{"token":"sample-token","status":{"id":2}},{"token":"hidden-token","status":{"id":1}}]}
            """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(BATCH + "?"))).andExpect(method(HttpMethod.GET)).andRespond(withSuccess("""
            {"submissions":[
                {"token":"hidden-token","status":{"id":3},"stdout":"%s","time":"0.047","memory":15000},
                {"token":"sample-token","status":{"id":3},"stdout":"%s","time":"0.016","memory":13000}
            ]}
            """.formatted(encode("hidden input"), encode("  sample\n\tanswer\r\n")), MediaType.APPLICATION_JSON));

        JudgeGateway.Result result = gateway.execute(job("PYTHON"));
        assertThat(result.verdict()).isEqualTo("WRONG_ANSWER");
        assertThat(result.passedCases()).isEqualTo(1);
        assertThat(result.totalCases()).isEqualTo(2);
        assertThat(result.elapsedMs()).isEqualTo(47);
        assertThat(result.memoryKb()).isEqualTo(15000);
        assertThat(result.cases()).singleElement().satisfies(test -> {
            assertThat(test.position()).isEqualTo(1);
            assertThat(test.verdict()).isEqualTo("ACCEPTED");
            assertThat(test.stdout()).contains("sample").doesNotContain("hidden input");
        });
        server.verify();
    }

    @Test
    void compilationDiagnosticsComeFromVisibleCasesOnly() {
        server.expect(requestTo(BATCH + "?base64_encoded=true")).andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.submissions[0].language_id").value(91))
            .andRespond(withSuccess(TOKEN_RESPONSE, MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(BATCH + "?"))).andRespond(withSuccess("""
            {"submissions":[
                {"token":"hidden-token","status":{"id":6},"compile_output":"%s"},
                {"token":"sample-token","status":{"id":6},"compile_output":"%s"}
            ]}
            """.formatted(encode("private diagnostic"), encode("syntax error")), MediaType.APPLICATION_JSON));

        JudgeGateway.Result result = gateway.execute(job("JAVA"));
        assertThat(result.verdict()).isEqualTo("COMPILATION_ERROR");
        assertThat(result.diagnostic()).isEqualTo("syntax error");
        assertThat(result.cases()).hasSize(1);
        server.verify();
    }

    @Test
    void doesNotReplayAFailedPaidSubmission() {
        server.expect(requestTo(BATCH + "?base64_encoded=true")).andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThatThrownBy(() -> gateway.execute(job("CPP"))).isInstanceOf(RestClientResponseException.class);
        server.verify();
    }

    @Test
    void pollingDeadlineReturnsAnInfrastructureVerdict() {
        server.expect(requestTo(BATCH + "?base64_encoded=true"))
            .andRespond(withSuccess(TOKEN_RESPONSE, MediaType.APPLICATION_JSON));
        var expired = new HostedJudgeGateway(client, settings, Duration.ZERO, Duration.ZERO);
        JudgeGateway.Result result = expired.execute(job("PYTHON"));
        assertThat(result.verdict()).isEqualTo("INTERNAL_ERROR");
        assertThat(result.cases()).isEmpty();
        server.verify();
    }

    private static JudgeGateway.Job job(String language) {
        return new JudgeGateway.Job(language, "print(input())", 2000, 128, List.of(
            new JudgeGateway.Test(1, "sample input", "sample answer", true),
            new JudgeGateway.Test(2, "hidden input", "hidden expected answer", false)));
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
