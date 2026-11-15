package com.codearena.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Component
class AiBudget {
    private final JdbcClient jdbc;
    private final int perUserPerMinute;
    private final int dailyLimit;

    AiBudget(JdbcClient jdbc, @Value("${arena.ai.user-per-minute}") int perUserPerMinute,
             @Value("${arena.ai.daily-limit}") int dailyLimit) {
        if (perUserPerMinute < 1 || dailyLimit < 1) throw new IllegalArgumentException("AI request limits must be positive.");
        this.jdbc = jdbc;
        this.perUserPerMinute = perUserPerMinute;
        this.dailyLimit = dailyLimit;
    }

    record Usage(long daily, long recent) {}

    @Transactional
    public void reserve(long userId) {
        jdbc.sql("SELECT id FROM users WHERE id = :id AND enabled FOR SHARE").param("id", userId)
            .query(Long.class).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "This account is disabled."));
        jdbc.sql("SELECT pg_advisory_xact_lock(534703)").query((rs, row) -> true).single();
        Usage usage = jdbc.sql("""
            SELECT count(*) AS daily,
                count(*) FILTER (WHERE user_id = :user AND requested_at >= statement_timestamp() - interval '1 minute') AS recent
            FROM ai_requests
            WHERE requested_at >= statement_timestamp() - interval '24 hours'
            """).param("user", userId).query((rs, row) -> new Usage(rs.getLong("daily"), rs.getLong("recent"))).single();
        if (usage.recent() >= perUserPerMinute) {
            throw new AiLimit("You have reached the AI request limit for this minute.", "60");
        }
        if (usage.daily() >= dailyLimit) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "The platform has reached its daily AI request limit.");
        }
        jdbc.sql("INSERT INTO ai_requests (user_id, requested_at) VALUES (:user, statement_timestamp())")
            .param("user", userId).update();
    }

    @Scheduled(fixedDelay = 43_200_000)
    void removeExpired() {
        jdbc.sql("DELETE FROM ai_requests WHERE requested_at < now() - interval '24 hours'").update();
    }

    static final class AiLimit extends ResponseStatusException {
        private final HttpHeaders headers = new HttpHeaders();

        AiLimit(String reason, String seconds) {
            super(HttpStatus.TOO_MANY_REQUESTS, reason);
            headers.set(HttpHeaders.RETRY_AFTER, seconds);
        }

        @Override
        public HttpHeaders getHeaders() { return headers; }
    }
}
