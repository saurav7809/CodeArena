package com.codearena.submission;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class SubmissionAdmission {
    private final JdbcClient jdbc;
    private final int maxActive;
    private final int perUserPerMinute;
    private final int dailyCases;

    SubmissionAdmission(JdbcClient jdbc, @Value("${arena.execution.max-active:0}") int maxActive,
                        @Value("${arena.execution.per-user-per-minute:0}") int perUserPerMinute,
                        @Value("${arena.execution.daily-cases:0}") int dailyCases) {
        this.jdbc = jdbc;
        this.maxActive = maxActive;
        this.perUserPerMinute = perUserPerMinute;
        this.dailyCases = dailyCases;
    }

    record Usage(long active, long userJobs, long cases) {}

    void check(long userId, int requestedCases) {
        if (maxActive <= 0) return;
        // This transaction also inserts the job, so separate API instances share the same limits.
        jdbc.sql("SELECT pg_advisory_xact_lock(534702)").query((rs, row) -> true).single();
        Usage usage = jdbc.sql("""
            SELECT
                (SELECT count(*) FROM submissions WHERE verdict IN ('PENDING', 'RUNNING')) AS active,
                (SELECT count(*) FROM submissions
                 WHERE user_id = :user AND created_at >= statement_timestamp() - interval '1 minute') AS user_jobs,
                (SELECT coalesce(sum(total_cases), 0) FROM submissions
                 WHERE created_at >= statement_timestamp() - interval '24 hours') AS cases
            """).param("user", userId)
            .query((rs, row) -> new Usage(rs.getLong("active"), rs.getLong("user_jobs"), rs.getLong("cases"))).single();
        if (usage.active() >= maxActive) throw limited("The judge queue is full. Try again shortly.");
        if (perUserPerMinute > 0 && usage.userJobs() >= perUserPerMinute) {
            throw limited("You have reached the execution limit for this minute.");
        }
        if (dailyCases > 0 && usage.cases() + requestedCases > dailyCases) {
            throw limited("The platform has reached its daily test execution budget.");
        }
    }

    private static ResponseStatusException limited(String message) {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, message);
    }
}
