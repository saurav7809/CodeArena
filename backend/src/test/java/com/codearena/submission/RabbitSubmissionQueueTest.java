package com.codearena.submission;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RabbitSubmissionQueueTest {
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final SubmissionJobs jobs = mock(SubmissionJobs.class);
    private final JudgeGateway judge = mock(JudgeGateway.class);
    private final SubmissionWorker worker = mock(SubmissionWorker.class);
    private final RabbitSubmissionQueue queue = new RabbitSubmissionQueue(rabbit, jobs, judge, worker, new SimpleMeterRegistry());

    @BeforeEach
    void enableJudge() { when(judge.configured()).thenReturn(true); }

    @Test
    void onlyRoutedAndConfirmedIdsAreMarkedDispatched() {
        var confirmed = new SubmissionJobs.Dispatch(1, 0);
        when(jobs.reserveDispatchBatch()).thenReturn(List.of(confirmed, new SubmissionJobs.Dispatch(2, 0),
            new SubmissionJobs.Dispatch(3, 1)));
        publish("1", confirm -> confirm.getFuture().complete(new CorrelationData.Confirm(true, null)));
        publish("2", confirm -> {
            confirm.setReturned(new ReturnedMessage(message("2"), 312, "NO_ROUTE", "", RabbitSubmissionQueue.QUEUE));
            confirm.getFuture().complete(new CorrelationData.Confirm(true, null));
        });
        publish("3", confirm -> confirm.getFuture().complete(new CorrelationData.Confirm(false, "nack")));

        queue.dispatch();

        verify(jobs).confirmDispatch(List.of(confirmed));
    }

    @Test
    void aRedeliveredIdOnlyRunsIfItCanStillBeClaimed() {
        var pending = new SubmissionJobs.Job(3, "PYTHON", "print(1)", "{}", 1, Instant.EPOCH);
        when(jobs.claim(3)).thenReturn(pending).thenReturn(null);
        queue.execute(message("3"));
        queue.execute(message("3"));
        assertThatThrownBy(() -> queue.execute(message("malformed"))).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(worker).run(pending);
        verifyNoMoreInteractions(worker);
    }

    private void publish(String id, Consumer<CorrelationData> complete) {
        doAnswer(call -> {
            MessagePostProcessor properties = call.getArgument(3);
            assertThat(properties.postProcessMessage(message(id)).getMessageProperties().getDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
            complete.accept(call.getArgument(4));
            return null;
        }).when(rabbit).convertAndSend(eq(""), eq(RabbitSubmissionQueue.QUEUE), eq(id), any(MessagePostProcessor.class),
            any(CorrelationData.class));
    }

    private static Message message(String body) { return new Message(body.getBytes(UTF_8)); }
}
