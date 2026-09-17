package com.pairforge.api.execution;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionJobPublisherTest {
    final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    final ExecutionProperties config = new ExecutionProperties(10, 60, 100, 100, 20, 2, 0, 65536);
    final ExecutionJobPublisher publisher = new ExecutionJobPublisher(rabbit, config, new SimpleMeterRegistry());
    @AfterEach void clearInterrupt() { Thread.interrupted(); }
    @Test void retriesReuseExecutionIdButUseDistinctConfirmCorrelations() {
        var messages = new ArrayList<Message>(); var correlations = new ArrayList<String>();
        doAnswer(call -> {
            Message message = call.getArgument(2); CorrelationData correlation = call.getArgument(3);
            messages.add(message); correlations.add(correlation.getId());
            correlation.getFuture().complete(new CorrelationData.Confirm(messages.size() == 2, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        UUID id = UUID.randomUUID(); assertThat(publisher.publish(id)).isNull();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getBody()).isEqualTo(messages.get(1).getBody());
        assertThat(messages.get(0).getMessageProperties().getMessageId()).isEqualTo(id.toString());
        assertThat(messages.get(0).getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(correlations).doesNotHaveDuplicates();
    }
    @Test void nackExhaustionIsDefiniteFailure() {
        doAnswer(call -> { ((CorrelationData) call.getArgument(3)).getFuture().complete(new CorrelationData.Confirm(false, null)); return null; })
                .when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThat(publisher.publish(UUID.randomUUID())).isEqualTo(FailureReason.DISPATCH_FAILED);
        verify(rabbit, times(2)).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }
    @Test void mandatoryReturnIsFailureEvenWithPositiveConfirm() {
        doAnswer(call -> {
            CorrelationData c = call.getArgument(3);
            c.setReturned(new ReturnedMessage(call.getArgument(2), 312, "NO_ROUTE", ExecutionMessaging.EXCHANGE, ExecutionMessaging.JOBS));
            c.getFuture().complete(new CorrelationData.Confirm(true, null)); return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThat(publisher.publish(UUID.randomUUID())).isEqualTo(FailureReason.DISPATCH_FAILED);
    }
    @Test void locallyGeneratedNackRemainsUncertainEvenAfterBrokerNack() {
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            CorrelationData c = call.getArgument(3);
            // Spring supplies a reason for locally generated nacks; AMQP basic.nack has none.
            c.getFuture().complete(new CorrelationData.Confirm(false,
                    attempts.incrementAndGet() == 1 ? "Channel closed by application" : null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThat(publisher.publish(UUID.randomUUID())).isEqualTo(FailureReason.DISPATCH_UNCONFIRMED);
        assertThat(attempts.get()).isEqualTo(2);
    }
    @Test void timeoutIsBoundedAndUncertain() {
        long start = System.nanoTime();
        assertThat(publisher.publish(UUID.randomUUID())).isEqualTo(FailureReason.DISPATCH_UNCONFIRMED);
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(2));
        verify(rabbit, times(2)).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }
    @Test void aLaterConnectionFailureDoesNotEraseEarlierUncertainty() {
        doNothing().doThrow(new AmqpConnectException(new java.net.ConnectException("test")))
                .when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThat(publisher.publish(UUID.randomUUID())).isEqualTo(FailureReason.DISPATCH_UNCONFIRMED);
    }
    @Test void connectionFailureBeforeSendingIsDefinite() {
        doThrow(new AmqpConnectException(new java.net.ConnectException("test")))
                .when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThat(publisher.publish(UUID.randomUUID())).isEqualTo(FailureReason.DISPATCH_FAILED);
    }
    @Test void interruptionStopsRetryAndPreservesInterruptFlag() {
        Thread.currentThread().interrupt();
        assertThat(publisher.publish(UUID.randomUUID())).isEqualTo(FailureReason.DISPATCH_UNCONFIRMED);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(rabbit, times(1)).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }
}
