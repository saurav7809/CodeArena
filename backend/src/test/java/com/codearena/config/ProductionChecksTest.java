package com.codearena.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionChecksTest {
    @Test
    void acceptsAnExactHttpsOriginAndVerifiedDatabaseTlsWithoutABroker() {
        assertThatCode(() -> ProductionChecks.check(env())).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://frontend.example.com", "https://frontend.example.com/",
        "https://frontend.example.com/account", "*"})
    void rejectsInvalidFrontendOrigins(String origin) {
        assertThatThrownBy(() -> ProductionChecks.check(env().withProperty("arena.frontend-origin", origin)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exact HTTPS origin");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sslmode=require", "SSLMODE=verify-full", "sslmode=verify-full&sslmode=disable",
        "sslmode=verify-full&sslfactory=org.postgresql.ssl.NonValidatingFactory",
        "sslmode=verify-full&sslhostnameverifier=example", "sslmode=verify-full&password=example"})
    void rejectsDatabaseSettingsThatSkipVerificationOrEmbedCredentials(String options) {
        var values = env().withProperty("spring.datasource.url", "jdbc:postgresql://postgres.example.com/codearena?" + options);
        assertThatThrownBy(() -> ProductionChecks.check(values)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sslmode=verify-full");
    }

    @Test
    void requiresAnExecutionBudget() {
        assertThatThrownBy(() -> ProductionChecks.check(env().withProperty("arena.execution.daily-cases", "0")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("daily-cases");
    }

    @ParameterizedTest
    @ValueSource(strings = {"amqp://rabbit.example.com", "amqps://user:pass@rabbit.example.com",
        "amqps://a.example.com,b.example.com", "amqps://rabbit.example.com/vhost"})
    void rejectsUnsafeBrokerAddresses(String address) {
        assertThatThrownBy(() -> ProductionChecks.check(broker().withProperty("spring.rabbitmq.addresses", address)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CODEARENA_RABBITMQ_URL");
    }

    @Test
    void acceptsAnOptionalBrokerWithVerifiedTls() {
        assertThatCode(() -> ProductionChecks.check(broker())).doesNotThrowAnyException();
        assertThatThrownBy(() -> ProductionChecks.check(broker().withProperty("spring.rabbitmq.ssl.verify-hostname", "false")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("hostname verification");
    }

    private static MockEnvironment broker() {
        return env().withProperty("arena.execution.rabbitmq", "true")
            .withProperty("spring.rabbitmq.addresses", "amqps://rabbit.example.com:5671")
            .withProperty("spring.rabbitmq.username", "arena")
            .withProperty("spring.rabbitmq.password", "test-rabbit-password")
            .withProperty("spring.rabbitmq.ssl.enabled", "true")
            .withProperty("spring.rabbitmq.ssl.validate-server-certificate", "true")
            .withProperty("spring.rabbitmq.ssl.verify-hostname", "true");
    }

    private static MockEnvironment env() {
        return new MockEnvironment()
            .withProperty("arena.frontend-origin", "https://frontend.example.com")
            .withProperty("spring.datasource.url", "jdbc:postgresql://postgres.example.com/codearena?sslmode=verify-full")
            .withProperty("spring.datasource.username", "arena")
            .withProperty("spring.datasource.password", "test-db-password")
            .withProperty("arena.execution.max-active", "16")
            .withProperty("arena.execution.per-user-per-minute", "6")
            .withProperty("arena.execution.daily-cases", "400");
    }
}
