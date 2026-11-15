package com.codearena.submission;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "arena.execution.rabbitmq=false", "arena.judge-token=", "arena.admin-password="
})
@EnabledIfEnvironmentVariable(named = "CODEARENA_TEST_DB_URL", matches = ".+/codearena_test(?:\\?.*)?")
@Transactional
class SubmissionJobsIntegrationTest {
    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("CODEARENA_TEST_DB_URL"));
        registry.add("arena.jwt-secret", () -> "generated-test-signing-key-" + UUID.randomUUID());
    }

    @Autowired JdbcClient jdbc;
    @Autowired SubmissionJobs jobs;

    @Test
    void recoveredJobRejectsTheOldAttemptAndOldPublishConfirmation() {
        long id = insertJob();
        var firstDispatch = new SubmissionJobs.Dispatch(id, 0);
        assertThat(jobs.reserveDispatchBatch()).contains(firstDispatch);
        var first = jobs.claim(id);
        assertThat(first.attempt()).isEqualTo(1);
        assertThat(jobs.claim(id)).isNull();
        expireLease(id);
        jobs.recover();
        jobs.confirmDispatch(List.of(firstDispatch));
        assertThat(jobs.reserveDispatchBatch()).contains(new SubmissionJobs.Dispatch(id, 1));
        var second = jobs.claim(id);
        assertThat(second.attempt()).isEqualTo(2);
        var result = new JudgeGateway.Result("ACCEPTED", 10, null, 1, 1, List.of(), null);
        assertThat(jobs.finish(first, result)).isFalse();
        assertThat(jobs.finish(second, result)).isTrue();
        assertThat(jobs.claim(id)).isNull();
    }

    @Test
    void confirmedJobDefersDispatchAndAnExhaustedLeaseFinishes() {
        long id = insertJob();
        var dispatch = new SubmissionJobs.Dispatch(id, 0);
        assertThat(jobs.reserveDispatchBatch()).contains(dispatch);
        jobs.confirmDispatch(List.of(dispatch));
        assertThat(jdbc.sql("SELECT dispatch_after > now() + interval '2 minutes' FROM submissions WHERE id = :id")
            .param("id", id).query(Boolean.class).single()).isTrue();
        var attempt = jobs.claim(id);
        jdbc.sql("UPDATE submissions SET execution_attempts = :attempts WHERE id = :id")
            .param("id", id).param("attempts", SubmissionJobs.MAX_ATTEMPTS).update();
        expireLease(id);
        jobs.recover();
        assertThat(jobs.claim(id)).isNull();
        assertThat(jdbc.sql("SELECT verdict FROM submissions WHERE id = :id AND job_payload IS NULL")
            .param("id", id).query(String.class).single()).isEqualTo("INTERNAL_ERROR");
        assertThat(attempt.queuedAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void problemAcceptanceChangesOnlyForFinalJudgedSubmissions() {
        long id = insertJob();
        jdbc.sql("UPDATE submissions SET mode = 'SUBMIT' WHERE id = :id").param("id", id).update();
        long baseline = acceptedFor(id);
        var job = jobs.claim(id);
        assertThat(acceptedFor(id)).isEqualTo(baseline);
        var result = new JudgeGateway.Result("ACCEPTED", 20, null, 1, 1, List.of(), null);
        assertThat(jobs.finish(job, result)).isTrue();
        assertThat(jobs.finish(job, result)).isFalse();
        assertThat(acceptedFor(id)).isEqualTo(baseline + 1);
    }

    private long acceptedFor(long submissionId) {
        return jdbc.sql("""
            SELECT coalesce(ps.accepted, 0) FROM submissions s
            LEFT JOIN problem_statistics ps ON ps.problem_id = s.problem_id WHERE s.id = :id
            """).param("id", submissionId).query(Long.class).single();
    }

    private void expireLease(long id) {
        jdbc.sql("UPDATE submissions SET lease_expires_at = now() - interval '1 second' WHERE id = :id")
            .param("id", id).update();
    }

    private long insertJob() {
        String name = "lease_" + UUID.randomUUID().toString().substring(0, 8);
        long user = jdbc.sql("""
            INSERT INTO users (username, email, password_hash) VALUES (:name, :email, 'test') RETURNING id
            """).param("name", name).param("email", name + "@example.test").query(Long.class).single();
        return jdbc.sql("""
            INSERT INTO submissions (user_id, problem_id, language, code, mode, total_cases, job_payload)
            SELECT :user, p.id, 'PYTHON', 'print(1)', 'RUN', 1,
              '{"timeLimitMs":500,"memoryLimitMb":64,"cases":[{"position":1,"input":"","expectedOutput":"1","visible":true}]}'::jsonb
            FROM problems p WHERE slug = 'deduplicate-catalog'
            RETURNING id
            """).param("user", user).query(Long.class).single();
    }
}
