package com.codearena.submission;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class JudgeProfilesTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withUserConfiguration(HostedJudgeConfiguration.class, LocalJudgeGateway.class)
        .withBean(RestClient.Builder.class, RestClient::builder)
        .withPropertyValues(
            "arena.judge-url=http://127.0.0.1:8091",
            "arena.judge-token=local-test-token-at-least-32-characters",
            "arena.hosted-judge.url=https://judge.example.com",
            "arena.hosted-judge.token=test-key",
            "arena.hosted-judge.auth=rapidapi",
            "arena.hosted-judge.python-id=71",
            "arena.hosted-judge.java-id=91",
            "arena.hosted-judge.cpp-id=105");

    @Test
    void productionUsesOnlyHostedJudge() {
        context.withPropertyValues("spring.profiles.active=prod", "server.address=0.0.0.0").run(app -> {
            assertThat(app).hasSingleBean(JudgeGateway.class);
            assertThat(app.getBean(JudgeGateway.class)).isInstanceOf(HostedJudgeGateway.class);
        });
    }

    @Test
    void productionRejectsPublicDemoEndpoints() {
        context.withPropertyValues("spring.profiles.active=prod", "arena.hosted-judge.url=https://ce.judge0.com")
            .run(app -> assertThat(app.getStartupFailure()).hasRootCauseMessage(
                "Set CODEARENA_JUDGE0_URL to a private hosted judge HTTPS endpoint."));
    }

    @Test
    void trustedLocalJudgeRejectsNonLoopbackApiBind() {
        context.withPropertyValues("server.address=0.0.0.0").run(app ->
            assertThat(app.getStartupFailure()).hasRootCauseMessage(
                "The trusted development judge requires a loopback API bind and judge URL."));
    }
}
