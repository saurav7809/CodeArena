package com.codearena.submission;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class SubmissionWorker {
    private static final Logger log = LoggerFactory.getLogger(SubmissionWorker.class);
    private final SubmissionService submissions;
    private final JudgeGateway judge;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();

    SubmissionWorker(SubmissionService submissions, JudgeGateway judge) {
        this.submissions = submissions;
        this.judge = judge;
    }

    @Scheduled(fixedDelay = 500)
    void dispatch() {
        if (!judge.configured() || !busy.compareAndSet(false, true)) return;
        boolean started = false;
        try {
            var pending = submissions.claim();
            if (pending == null) return;
            worker.submit(() -> run(pending));
            started = true;
        } finally {
            if (!started) busy.set(false);
        }
    }

    private void run(SubmissionService.Pending pending) {
        try {
            var spec = pending.spec();
            var job = new JudgeGateway.Job(pending.language(), pending.code(), spec.timeLimitMs(), spec.memoryLimitMb(), spec.cases());
            submissions.finish(pending, judge.execute(job));
        } catch (RuntimeException error) {
            log.warn("Local judge job {} failed with {}", pending.id(), error.getClass().getSimpleName());
            submissions.fail(pending.id(), "The local judge is unavailable. Check the judge terminal and retry.");
        } finally {
            busy.set(false);
        }
    }

    @Scheduled(fixedDelay = 30000)
    void recover() { submissions.recoverStaleJobs(); }

    @PreDestroy
    void shutdown() { worker.shutdownNow(); }
}
