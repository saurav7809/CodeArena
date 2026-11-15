package com.codearena.submission;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "arena.execution.rabbitmq", havingValue = "false", matchIfMissing = true)
class DatabaseSubmissionQueue {
    private final SubmissionJobs jobs;
    private final JudgeGateway judge;
    private final SubmissionWorker worker;
    private final ExecutorService pool;
    private final Semaphore slots;
    private final int workers;

    DatabaseSubmissionQueue(SubmissionJobs jobs, JudgeGateway judge, SubmissionWorker worker,
                            @Value("${arena.execution.workers}") int workers) {
        this.jobs = jobs;
        this.judge = judge;
        this.worker = worker;
        this.workers = workers;
        this.pool = Executors.newFixedThreadPool(workers);
        this.slots = new Semaphore(workers);
    }

    @Scheduled(fixedDelay = 500)
    void dispatch() {
        if (!judge.configured()) return;
        for (int i = 0; i < workers && slots.tryAcquire(); i++) {
            boolean handedOff = false;
            try {
                var pending = jobs.claimNext();
                if (pending == null) return;
                pool.execute(() -> {
                    try { worker.run(pending); }
                    finally { slots.release(); }
                });
                handedOff = true;
            } finally {
                if (!handedOff) slots.release();
            }
        }
    }

    @PreDestroy
    void shutdown() { pool.shutdownNow(); }
}
