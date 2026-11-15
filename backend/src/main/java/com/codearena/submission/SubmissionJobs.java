package com.codearena.submission;

import io.micrometer.core.instrument.MeterRegistry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class SubmissionJobs {
    static final int MAX_ATTEMPTS = 3;
    private static final Logger log = LoggerFactory.getLogger(SubmissionJobs.class);
    private static final String CLAIM = """
        UPDATE submissions SET verdict = 'RUNNING', started_at = now(), execution_attempts = execution_attempts + 1,
            lease_expires_at = now() + interval '3 minutes'
        WHERE verdict = 'PENDING' AND ready_at <= now() AND created_at > now() - interval '10 minutes' AND
        """;
    private static final String LEASE = " WHERE id = :id AND verdict = 'RUNNING' AND execution_attempts = :attempt";
    private static final String JOB_COLUMNS = " RETURNING id, language, code, job_payload, execution_attempts, created_at";
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final MeterRegistry meters;
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicLong oldestPendingSeconds = new AtomicLong();

    record Job(long id, String language, String code, String payload, int attempt, Instant queuedAt) {}
    record Dispatch(long id, int attempt) {}

    SubmissionJobs(JdbcClient jdbc, ObjectMapper json, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.json = json;
        this.meters = meters;
        meters.gauge("arena.execution.pending", pending);
        meters.gauge("arena.execution.running", running);
        meters.gauge("arena.execution.oldest.pending.seconds", oldestPendingSeconds);
    }

    Job claimNext() {
        return jdbc.sql(CLAIM + """
            id = (SELECT id FROM submissions WHERE verdict = 'PENDING' AND ready_at <= now()
                  AND created_at > now() - interval '10 minutes'
                  ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED)
            """ + JOB_COLUMNS).query(SubmissionJobs::map).optional().orElse(null);
    }

    Job claim(long id) {
        return jdbc.sql(CLAIM + " id = :id" + JOB_COLUMNS).param("id", id)
            .query(SubmissionJobs::map).optional().orElse(null);
    }

    List<Dispatch> reserveDispatchBatch() {
        return jdbc.sql("""
            UPDATE submissions SET dispatch_after = now() + interval '15 seconds'
            WHERE id IN (
                SELECT id FROM submissions WHERE verdict = 'PENDING' AND dispatch_after <= now() AND ready_at <= now()
                  AND created_at > now() - interval '10 minutes'
                ORDER BY dispatch_after, id LIMIT 24 FOR UPDATE SKIP LOCKED
            ) RETURNING id, execution_attempts
            """).query((rs, row) -> new Dispatch(rs.getLong("id"), rs.getInt("execution_attempts"))).list();
    }

    void confirmDispatch(List<Dispatch> confirmed) {
        if (confirmed.isEmpty()) return;
        Long[] ids = confirmed.stream().map(Dispatch::id).toArray(Long[]::new);
        Integer[] attempts = confirmed.stream().map(Dispatch::attempt).toArray(Integer[]::new);
        jdbc.sql("""
            UPDATE submissions s SET dispatch_after = now() + interval '3 minutes'
            FROM unnest(CAST(:ids AS bigint[]), CAST(:attempts AS integer[])) AS dispatched(id, attempt)
            WHERE s.id = dispatched.id AND s.execution_attempts = dispatched.attempt AND s.verdict = 'PENDING'
            """).param("ids", ids).param("attempts", attempts).update();
    }

    JudgeGateway.Spec spec(Job job) { return json.readValue(job.payload(), JudgeGateway.Spec.class); }

    boolean finish(Job job, JudgeGateway.Result result) {
        return jdbc.sql("""
            UPDATE submissions SET verdict = :verdict, elapsed_ms = :elapsed, memory_kb = :memory,
                passed_cases = :passed, total_cases = :total, details = CAST(:details AS jsonb),
                diagnostic = :diagnostic, job_payload = NULL, lease_expires_at = NULL, finished_at = now()
            """ + LEASE)
            .param("id", job.id()).param("attempt", job.attempt()).param("verdict", result.verdict())
            .param("elapsed", result.elapsedMs()).param("memory", result.memoryKb()).param("passed", result.passedCases())
            .param("total", result.totalCases()).param("details", json.writeValueAsString(result.cases()))
            .param("diagnostic", result.diagnostic()).update() == 1;
    }

    boolean retry(Job job, int delaySeconds) {
        return jdbc.sql("""
            UPDATE submissions SET verdict = 'PENDING', lease_expires_at = NULL, started_at = NULL,
                ready_at = now() + make_interval(secs => :delay), dispatch_after = now() + make_interval(secs => :delay)
            """ + LEASE).param("id", job.id()).param("attempt", job.attempt())
            .param("delay", delaySeconds).update() == 1;
    }

    @Scheduled(fixedDelay = 15000)
    void recover() {
        var counts = jdbc.sql("""
            WITH expired AS (
                SELECT id, verdict, execution_attempts, created_at FROM submissions
                WHERE (verdict = 'RUNNING' AND lease_expires_at <= now())
                   OR (verdict = 'PENDING' AND created_at <= now() - interval '10 minutes')
                ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED
            ), recovery AS (
                SELECT id, verdict = 'RUNNING' AND execution_attempts < :attempts
                    AND created_at > now() - interval '10 minutes' AS retry FROM expired
            ), updated AS (
                UPDATE submissions s SET verdict = CASE WHEN r.retry THEN 'PENDING' ELSE 'INTERNAL_ERROR' END,
                    lease_expires_at = NULL,
                    started_at = CASE WHEN r.retry THEN NULL ELSE s.started_at END,
                    ready_at = now(), dispatch_after = now(),
                    diagnostic = CASE WHEN r.retry THEN NULL ELSE 'Code execution could not finish. Start a new run.' END,
                    finished_at = CASE WHEN r.retry THEN NULL ELSE now() END,
                    job_payload = CASE WHEN r.retry THEN s.job_payload ELSE NULL END
                FROM recovery r WHERE s.id = r.id
                RETURNING s.verdict
            )
            SELECT count(*) FILTER (WHERE verdict = 'PENDING') AS requeued,
                count(*) FILTER (WHERE verdict = 'INTERNAL_ERROR') AS failed FROM updated
            """).param("attempts", MAX_ATTEMPTS)
            .query((rs, row) -> new Recovery(rs.getInt("requeued"), rs.getInt("failed"))).single();
        if (counts.requeued() > 0) meters.counter("arena.execution.recovery", "outcome", "requeued").increment(counts.requeued());
        if (counts.failed() > 0) meters.counter("arena.execution.recovery", "outcome", "expired").increment(counts.failed());
        if (counts.requeued() + counts.failed() > 0) {
            log.warn("Recovered {} execution jobs and expired {} others", counts.requeued(), counts.failed());
        }
        jdbc.sql("""
            SELECT count(*) FILTER (WHERE verdict = 'PENDING') AS pending,
                count(*) FILTER (WHERE verdict = 'RUNNING') AS running,
                coalesce(extract(epoch FROM now() - min(created_at) FILTER (WHERE verdict = 'PENDING')), 0) AS oldest
            FROM submissions WHERE verdict IN ('PENDING', 'RUNNING')
            """).query((rs, row) -> {
                pending.set(rs.getInt("pending"));
                running.set(rs.getInt("running"));
                oldestPendingSeconds.set(Math.max(0, rs.getLong("oldest")));
                return true;
            }).single();
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    void pruneRuns() {
        jdbc.sql("""
            DELETE FROM submissions WHERE id IN (
                SELECT id FROM submissions WHERE mode = 'RUN' AND verdict NOT IN ('PENDING', 'RUNNING')
                    AND created_at < now() - interval '7 days'
                ORDER BY created_at, id LIMIT 10000 FOR UPDATE SKIP LOCKED
            )
            """).update();
    }

    private record Recovery(int requeued, int failed) {}

    private static Job map(ResultSet rs, int row) throws SQLException {
        return new Job(rs.getLong("id"), rs.getString("language"), rs.getString("code"), rs.getString("job_payload"),
            rs.getInt("execution_attempts"), rs.getTimestamp("created_at").toInstant());
    }
}
