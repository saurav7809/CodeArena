package com.codearena.submission;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "arena.execution.rabbitmq", havingValue = "true")
class RabbitSubmissionQueue {
    static final String QUEUE = "codearena.execution";
    private static final Logger log = LoggerFactory.getLogger(RabbitSubmissionQueue.class);
    private final RabbitTemplate rabbit;
    private final SubmissionJobs jobs;
    private final JudgeGateway judge;
    private final SubmissionWorker worker;
    private final MeterRegistry meters;

    RabbitSubmissionQueue(RabbitTemplate rabbit, SubmissionJobs jobs, JudgeGateway judge, SubmissionWorker worker,
                          MeterRegistry meters) {
        this.rabbit = rabbit;
        this.jobs = jobs;
        this.judge = judge;
        this.worker = worker;
        this.meters = meters;
    }

    @Bean
    static Queue executionQueue() { return new Queue(QUEUE, true); }

    @Bean
    static SimpleRabbitListenerContainerFactory executionListenerFactory(SimpleRabbitListenerContainerFactoryConfigurer configurer,
        ConnectionFactory connection, @Value("${arena.execution.shutdown-timeout-ms}") long shutdownTimeout) {
        var factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connection);
        factory.setContainerCustomizer(container -> {
            container.setShutdownTimeout(shutdownTimeout);
            container.setForceStop(true);
        });
        return factory;
    }

    @Scheduled(fixedDelayString = "${arena.execution.dispatch-interval-ms}")
    void dispatch() {
        if (!judge.configured()) return;
        var published = new LinkedHashMap<SubmissionJobs.Dispatch, CorrelationData>();
        var batch = jobs.reserveDispatchBatch();
        for (var job : batch) {
            var confirmation = new CorrelationData();
            try {
                rabbit.convertAndSend("", QUEUE, Long.toString(job.id()), message -> {
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    message.getMessageProperties().setExpiration("180000");
                    return message;
                }, confirmation);
                published.put(job, confirmation);
            } catch (AmqpException error) {
                log.warn("Could not publish to RabbitMQ. Pending submissions will be retried.");
                break;
            }
        }
        if (published.isEmpty()) {
            if (!batch.isEmpty()) meters.counter("arena.execution.publish", "outcome", "retry").increment(batch.size());
            return;
        }
        var confirmations = published.values().stream().map(CorrelationData::getFuture).toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(confirmations).get(4, TimeUnit.SECONDS);
        } catch (TimeoutException | ExecutionException error) {
            log.warn("RabbitMQ did not confirm every submission. Pending submissions will be retried.");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return;
        }
        var confirmedIds = published.entrySet().stream().filter(entry -> confirmed(entry.getValue()))
            .map(java.util.Map.Entry::getKey).toList();
        jobs.confirmDispatch(confirmedIds);
        if (!confirmedIds.isEmpty()) meters.counter("arena.execution.publish", "outcome", "confirmed").increment(confirmedIds.size());
        if (confirmedIds.size() < batch.size()) {
            meters.counter("arena.execution.publish", "outcome", "retry").increment(batch.size() - confirmedIds.size());
        }
    }

    private boolean confirmed(CorrelationData delivery) {
        if (delivery.getFuture().isCompletedExceptionally()) return false;
        var result = delivery.getFuture().getNow(null);
        return result != null && result.ack() && delivery.getReturned() == null;
    }

    @RabbitListener(queues = QUEUE, containerFactory = "executionListenerFactory")
    void execute(Message message) {
        if (!judge.configured()) return;
        byte[] body = message.getBody();
        long id;
        try {
            if (body.length == 0 || body.length > 19) throw new NumberFormatException();
            id = Long.parseLong(new String(body, StandardCharsets.US_ASCII));
            if (id <= 0) throw new NumberFormatException();
        } catch (NumberFormatException error) {
            throw new AmqpRejectAndDontRequeueException("Invalid execution job ID.");
        }
        var pending = jobs.claim(id);
        if (pending != null) worker.run(pending);
    }
}
