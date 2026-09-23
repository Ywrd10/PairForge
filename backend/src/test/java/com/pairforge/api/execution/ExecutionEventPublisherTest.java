package com.pairforge.api.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionEventPublisherTest {
    final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final ExecutionEventPublisher publisher = new ExecutionEventPublisher(rabbit,new ObjectMapper(),metrics,new ExecutionEventProperties(2,50,0));
    final ExecutionEvent event = new ExecutionEvent(1,UUID.randomUUID(),UUID.randomUUID(),"SUCCEEDED",2);
    @Test void persistentConfirmedNotificationContainsNoSourceOrOutput() {
        doAnswer(call -> {
            Message message = call.getArgument(2);
            assertThat(message.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(message.getMessageProperties().getMessageId()).isEqualTo(event.executionId()+":2");
            var tree = new ObjectMapper().readTree(message.getBody());
            assertThat(tree.size()).isEqualTo(5);
            assertThat(tree.get("stateRevision").asLong()).isEqualTo(2);
            ((CorrelationData)call.getArgument(3)).getFuture().complete(new CorrelationData.Confirm(true,null));
            return null;
        }).when(rabbit).send(eq("pairforge.execution"),eq("execution.events"),any(Message.class),any(CorrelationData.class));
        assertThat(publisher.publish(event)).isTrue();
    }
    @Test void nackReturnTimeoutAndConnectionLossHaveFiniteAttempts() {
        for (String failure : List.of("nack","return","timeout","exception")) {
            reset(rabbit);
            doAnswer(call -> {
                var correlation = (CorrelationData)call.getArgument(3);
                if (failure.equals("exception")) throw new IllegalStateException("broker unavailable");
                if (failure.equals("return")) correlation.setReturned(new ReturnedMessage(call.getArgument(2),312,"NO_ROUTE","pairforge.execution","execution.events"));
                if (!failure.equals("timeout")) correlation.getFuture().complete(new CorrelationData.Confirm(!failure.equals("nack"),null));
                return null;
            }).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
            assertThat(publisher.publish(event)).isFalse();
            verify(rabbit,times(2)).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        }
        assertThat(metrics.get("pairforge.execution.events.publish.failures").counter().count()).isEqualTo(4);
    }
    @Test void retriesKeepLogicalEventIdentityAndInterruptDoesNotDisappear() {
        doAnswer(call -> {
            ((CorrelationData)call.getArgument(3)).getFuture().complete(new CorrelationData.Confirm(false,null)); return null;
        }).doAnswer(call -> {
            ((CorrelationData)call.getArgument(3)).getFuture().complete(new CorrelationData.Confirm(true,null)); return null;
        }).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        assertThat(publisher.publish(event)).isTrue();
        reset(rabbit);
        Thread.currentThread().interrupt();
        try { assertThat(publisher.publish(event)).isFalse(); assertThat(Thread.currentThread().isInterrupted()).isTrue(); }
        finally { Thread.interrupted(); }
    }
}
