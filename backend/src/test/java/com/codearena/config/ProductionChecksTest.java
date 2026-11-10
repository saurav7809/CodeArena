package com.codearena.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionChecksTest {
    @Test
    void acceptsAnExactHttpsOriginAndVerifiedDatabaseTls() {
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
