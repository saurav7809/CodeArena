package com.codearena.submission;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;

@Component
class SubmissionWorker {
    private static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    private static final Set<String> USER_VERDICTS = Set.of("ACCEPTED", "WRONG_ANSWER", "COMPILATION_ERROR",
        "RUNTIME_ERROR", "TIME_LIMIT_EXCEEDED", "MEMORY_LIMIT_EXCEEDED");
    private static final Logger log = LoggerFactory.getLogger(SubmissionWorker.class);
    private final SubmissionJobs jobs;
    private final JudgeGateway judge;
    private final MeterRegistry meters;

    SubmissionWorker(SubmissionJobs jobs, JudgeGateway judge, MeterRegistry meters,
                     @Value("${arena.execution.workers}") int workers) {
        if (workers < 1 || workers > 8) throw new IllegalArgumentException("Set CODEARENA_EXECUTION_WORKERS between 1 and 8.");
        this.jobs = jobs;
        this.judge = judge;
        this.meters = meters;
    }

    void run(SubmissionJobs.Job job) {
        long started = System.nanoTime();
        if (job.attempt() == 1) {
            var wait = Duration.between(job.queuedAt(), Instant.now());
            if (!wait.isNegative()) meters.timer("arena.execution.queue.wait").record(wait);
        }
        try {
            evaluate(job);
        } finally {
            meters.timer("arena.execution.duration", "language", job.language())
                .record(Duration.ofNanos(System.nanoTime() - started));
        }
    }

    private void evaluate(SubmissionJobs.Job job) {
        JudgeGateway.Spec spec;
        try {
            spec = jobs.spec(job);
        } catch (JacksonException error) {
            log.warn("Execution job {} has an unreadable payload", job.id());
            complete(job, failure(0, "The stored execution request was invalid."));
            return;
        }
        if (spec == null || spec.cases() == null || spec.cases().isEmpty()) {
            complete(job, failure(0, "The stored execution request was invalid."));
            return;
        }
        JudgeGateway.Result result;
        try {
            result = judge.execute(new JudgeGateway.Job(job.language(), job.code(),
                spec.timeLimitMs(), spec.memoryLimitMb(), spec.cases()));
        } catch (JudgeGateway.TemporarilyUnavailable error) {
            if (job.attempt() < SubmissionJobs.MAX_ATTEMPTS) {
                if (jobs.retry(job, job.attempt() * 5)) meters.counter("arena.execution.recovery", "outcome", "judge_busy").increment();
                return;
            }
            complete(job, failure(spec.cases().size(), "The judge is busy. Start a new run shortly."));
            return;
        } catch (RuntimeException error) {
            if (Thread.currentThread().isInterrupted()) return;
            log.warn("Execution job {} attempt {} failed with {}", job.id(), job.attempt(), error.getClass().getSimpleName());
            complete(job, failure(spec.cases().size(), "Code execution is temporarily unavailable. Start a new run."));
            return;
        }
        complete(job, visibleResult(spec, result));
    }

    private void complete(SubmissionJobs.Job job, JudgeGateway.Result result) {
        if (jobs.finish(job, result)) {
            meters.counter("arena.execution.completed", "verdict", result.verdict()).increment();
        }
    }

    static JudgeGateway.Result visibleResult(JudgeGateway.Spec spec, JudgeGateway.Result result) {
        int total = spec.cases().size();
        var invalid = failure(total, "The judge returned an invalid result. Start a new run.");
        if (result == null || result.verdict() == null || result.totalCases() != total || result.passedCases() < 0
            || result.passedCases() > total || result.elapsedMs() < 0 || negative(result.memoryKb())) return invalid;
        if (INTERNAL_ERROR.equals(result.verdict())) {
            return failure(total, "Code execution is temporarily unavailable. Start a new run.");
        }
        if (!USER_VERDICTS.contains(result.verdict()) || ("ACCEPTED".equals(result.verdict()) != (result.passedCases() == total))) {
            return invalid;
        }
        var visible = new HashMap<Integer, JudgeGateway.Test>();
        for (var test : spec.cases()) {
            if (test.visible()) visible.put(test.position(), test);
        }
        var cases = new ArrayList<JudgeGateway.CaseResult>();
        if (result.cases() != null) {
            if (result.cases().size() > total) return invalid;
            for (var test : result.cases()) {
                if (test == null) return invalid;
                var sample = visible.remove(test.position());
                if (sample == null) continue;
                if (test.verdict() == null || !USER_VERDICTS.contains(test.verdict()) || test.elapsedMs() < 0
                    || negative(test.memoryKb())) return invalid;
                String expected = sample.expectedOutput();
                cases.add(new JudgeGateway.CaseResult(test.position(), test.verdict(), clip(test.stdout(), 8192),
                    clip(test.stderr(), 4096), test.elapsedMs(), test.memoryKb(), expected == null ? null : clip(expected, 8192)));
            }
        }
        boolean compileError = "COMPILATION_ERROR".equals(result.verdict());
        if (!compileError && !visible.isEmpty()) return invalid;
        return new JudgeGateway.Result(result.verdict(), result.elapsedMs(), result.memoryKb(), result.passedCases(), total,
            cases, compileError ? clip(result.diagnostic(), 4096) : null);
    }

    private static boolean negative(Integer value) { return value != null && value < 0; }

    private static JudgeGateway.Result failure(int total, String diagnostic) {
        return new JudgeGateway.Result(INTERNAL_ERROR, 0, null, 0, total, List.of(), diagnostic);
    }

    private static String clip(String value, int limit) {
        if (value == null) return "";
        int end = Math.min(value.length(), limit);
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end).replace('\0', '\uFFFD');
    }
}
