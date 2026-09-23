package com.pairforge.worker.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionListener;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionConsumerTest {
    @Test void shutdownWaitsForFailureStopBeforeClosingProcessorOrReturning() throws Exception {
        var connection = mock(ConnectionFactory.class);
        var container = mock(SimpleMessageListenerContainer.class);
        var processor = mock(ExecutionProcessor.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new AtomicBoolean();
        var stops = new AtomicInteger();
        doAnswer(call -> {
            if (stops.incrementAndGet() > 1) {
                assertThat(finished).as("The failure stop must finish before another stop begins").isTrue();
                return null;
            }
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            finished.set(true);
            return null;
        }).when(container).stop();
        doAnswer(call -> { assertThat(finished).isTrue(); return null; }).when(processor).close();
        var consumer = new ExecutionConsumer(connection, new ObjectMapper(), processor,
                new WorkerProperties(true, true, 500, 100, 0), container);
        var listener = ArgumentCaptor.forClass(ConnectionListener.class);
        verify(connection).addConnectionListener(listener.capture());
        try (var closer = Executors.newSingleThreadExecutor()) {
            listener.getValue().onFailed(new IllegalStateException("Injected connection loss"));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            Future<?> stopped = closer.submit(consumer::close);
            try {
                assertThatThrownBy(() -> stopped.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                verify(processor, never()).close();
                verify(container, times(1)).stop();
            } finally { release.countDown(); }
            stopped.get(3, TimeUnit.SECONDS);
            // A late failure callback cannot schedule work on the closed executor.
            listener.getValue().onFailed(new IllegalStateException("Late connection loss"));
            verify(container, times(2)).stop();
            verify(processor).close();
        } finally { release.countDown(); consumer.close(); }
    }
}
