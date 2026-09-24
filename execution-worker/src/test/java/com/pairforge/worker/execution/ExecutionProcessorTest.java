package com.pairforge.worker.execution;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionProcessorTest {
    final UUID id = UUID.randomUUID();
    final ExecutionRepository repository = mock(ExecutionRepository.class);
    final ExecutionRunner runner = mock(ExecutionRunner.class);
    final ExecutionResult success = new ExecutionResult(ExecutionResult.Status.SUCCEEDED, "ok", "", 0, 1L, null, false);
    final WorkerProperties properties = new WorkerProperties(true, true, 500, 100, 0);
    final ExecutionEventPublisher events = mock(ExecutionEventPublisher.class);
    final io.micrometer.core.instrument.simple.SimpleMeterRegistry metrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    final ExecutionProcessor processor = new ExecutionProcessor(repository, runner, properties, events, metrics);
    @BeforeEach void queued() throws Exception {
        when(repository.state(id)).thenReturn(Optional.of(new ExecutionRepository.State(id, "QUEUED", 0)));
        when(repository.claim(eq(id), anyLong())).thenAnswer(call -> Optional.of(new ExecutionRepository.Job(id, "PYTHON", "source", Instant.now().plusSeconds(5), 1, Instant.now().minusSeconds(2), Instant.now())));
        when(repository.complete(eq(id), eq(1L), any())).thenReturn(1);
        when(runner.run(any())).thenReturn(success);
    }
    @AfterEach void close() { processor.close(); Thread.interrupted(); }
    @Test void publicationFailureDoesNotRetryExecutionOrPreventDurableCompletion() throws Exception {
        when(repository.event(id)).thenReturn(new ExecutionEvent(1,id,UUID.randomUUID(),"RUNNING",1));
        when(events.publish(any())).thenThrow(new IllegalStateException("notification failure"));
        assertThat(processor.process(id)).isEqualTo(ExecutionProcessor.Outcome.ACK);
        var order=inOrder(repository,events,runner);
        order.verify(repository).claim(eq(id),anyLong()); order.verify(repository).event(id); order.verify(events).publish(any());
        order.verify(runner).run(any()); order.verify(repository).complete(id,1,success); order.verify(repository).event(id); order.verify(events).publish(any());
        verify(runner,times(1)).run(any()); verify(events,times(2)).failed(id);
    }
    @Test void retriesPersistenceWithoutRerunningSource() throws Exception {
        when(repository.complete(id, 1, success)).thenThrow(outage()).thenThrow(outage()).thenReturn(1);
        assertThat(processor.process(id)).isEqualTo(ExecutionProcessor.Outcome.ACK);
        verify(repository, times(3)).complete(id, 1, success); verify(runner, times(1)).run(any());
        assertThat(metrics.get("pairforge.execution.completed").counter().count()).isEqualTo(1);
        assertThat(metrics.get("pairforge.execution.queue.wait").timer().count()).isEqualTo(1);
        assertThat(metrics.get("pairforge.execution.duration").timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(1);
    }
    @Test void duplicateAndUnknownCommitDoNotInventCompletionMetrics() throws Exception {
        when(repository.complete(id, 1, success)).thenThrow(outage()).thenReturn(0);
        when(repository.state(id)).thenReturn(Optional.of(new ExecutionRepository.State(id, "QUEUED", 0)),
                Optional.of(new ExecutionRepository.State(id, "SUCCEEDED", 2)));
        processor.process(id); processor.process(id);
        assertThat(metrics.find("pairforge.execution.completed").counter()).isNull();
        assertThat(metrics.get("pairforge.execution.queue.wait").timer().count()).isEqualTo(1);
        verify(runner, times(1)).run(any());
    }
    @Test void cleanupFailureIsMeasuredWithoutInventingATerminalOutcome() throws Exception {
        doThrow(new IllegalStateException("sensitive detail")).when(runner).reconcile();
        assertThatThrownBy(processor::reconcile).isInstanceOf(IllegalStateException.class);
        assertThat(metrics.get("pairforge.execution.cleanup.failures").tag("operation", "reconcile").counter().count()).isEqualTo(1);
        assertThat(metrics.find("pairforge.execution.completed").counter()).isNull();
        doNothing().when(runner).reconcile();
    }
    @Test void exhaustionDoesNotReturnAnAcknowledgement() throws Exception {
        when(repository.complete(id, 1, success)).thenThrow(outage());
        assertThatThrownBy(() -> processor.process(id)).isInstanceOf(DataAccessResourceFailureException.class);
        verify(repository, times(3)).complete(id, 1, success); verify(runner, times(1)).run(any());
    }
    @Test void readsAndClaimsExhaustTheirBudgetWithoutStartingSource() throws Exception {
        when(repository.state(id)).thenThrow(outage());
        assertThatThrownBy(() -> processor.process(id)).isInstanceOf(DataAccessResourceFailureException.class);
        verify(repository, times(3)).state(id);
        verify(repository, never()).claim(any(), anyLong());
        reset(repository);
        when(repository.state(id)).thenReturn(Optional.of(new ExecutionRepository.State(id, "QUEUED", 0)));
        when(repository.claim(eq(id), anyLong())).thenThrow(outage());
        assertThatThrownBy(() -> processor.process(id)).isInstanceOf(DataAccessResourceFailureException.class);
        verify(repository, times(3)).claim(eq(id), anyLong());
        verify(runner, never()).run(any());
        verify(repository, never()).complete(any(), anyLong(), any());
    }
    @Test void uncertainClaimCannotStartTheRunnerOnARunningRow() throws Exception {
        when(repository.claim(eq(id), anyLong())).thenThrow(outage()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> processor.process(id)).isInstanceOf(IllegalStateException.class);
        verify(runner, never()).run(any());
    }
    @Test void failedClaimBeforeCommitCanBeRetriedOnceItActuallySucceeds() throws Exception {
        when(repository.claim(eq(id), anyLong())).thenThrow(outage())
                .thenReturn(Optional.of(new ExecutionRepository.Job(id, "JAVA", "source", Instant.now().plusSeconds(5), 1, Instant.now().minusSeconds(2), Instant.now())));
        assertThat(processor.process(id)).isEqualTo(ExecutionProcessor.Outcome.ACK);
        verify(runner, times(1)).run(any());
    }
    @Test void committedResultWithLostResponseIsRecoveredWithoutOverwritingIt() throws Exception {
        when(repository.complete(id, 1, success)).thenThrow(outage()).thenReturn(0);
        when(repository.state(id)).thenReturn(Optional.of(new ExecutionRepository.State(id, "QUEUED", 0)))
                .thenReturn(Optional.of(new ExecutionRepository.State(id, "SUCCEEDED", 2)));
        assertThat(processor.process(id)).isEqualTo(ExecutionProcessor.Outcome.ACK);
        verify(runner, times(1)).run(any());
    }
    @Test void runnerExceptionRequiresCleanupBeforePersistingInterruption() throws Exception {
        when(runner.run(any())).thenThrow(new IllegalStateException("test failure"));
        assertThat(processor.process(id)).isEqualTo(ExecutionProcessor.Outcome.ACK);
        var order = inOrder(runner, repository);
        order.verify(runner).stop(id); order.verify(repository).complete(id, 1, ExecutionResult.interrupted());
    }
    @Test void deadlineStopsActivityBeforeRecordingTimeout() throws Exception {
        when(repository.claim(eq(id), anyLong())).thenAnswer(call -> Optional.of(new ExecutionRepository.Job(id, "PYTHON", "source", Instant.now().plusMillis(150), 1, Instant.now().minusSeconds(2), Instant.now())));
        var gate = new CountDownLatch(1);
        when(runner.run(any())).thenAnswer(call -> { gate.await(); return success; });
        doAnswer(call -> { gate.countDown(); return null; }).when(runner).stop(id);
        assertThat(processor.process(id)).isEqualTo(ExecutionProcessor.Outcome.ACK);
        verify(repository).complete(id, 1, ExecutionResult.timedOut());
    }
    @Test void failedCleanupLeavesRunningStateForOperatorRecovery() throws Exception {
        when(runner.run(any())).thenThrow(new IllegalStateException());
        doThrow(new IllegalStateException()).when(runner).stop(id);
        assertThatThrownBy(() -> processor.process(id)).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).complete(any(), anyLong(), any());
    }
    @Test void futureDatabaseClockCannotExtendConfiguredBudget() throws Exception {
        when(repository.claim(eq(id), anyLong())).thenReturn(Optional.of(
                new ExecutionRepository.Job(id, "PYTHON", "source", Instant.now().plusSeconds(60), 1, Instant.now().minusSeconds(2), Instant.now())));
        var gate = new CountDownLatch(1);
        when(runner.run(any())).thenAnswer(call -> { gate.await(); return success; });
        doAnswer(call -> { gate.countDown(); return null; }).when(runner).stop(id);
        Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () ->
                assertThat(processor.process(id)).isEqualTo(ExecutionProcessor.Outcome.ACK));
        verify(repository).complete(id, 1, ExecutionResult.timedOut());
    }
    @Test void slowClaimConsumesLocalBudgetWithoutStartingTheRunner() throws Exception {
        when(repository.claim(eq(id), anyLong())).thenAnswer(call -> {
            Thread.sleep(650);
            return Optional.of(new ExecutionRepository.Job(id, "PYTHON", "source", Instant.now().plusSeconds(60), 1, Instant.now().minusSeconds(2), Instant.now()));
        });
        assertThat(processor.process(id)).isEqualTo(ExecutionProcessor.Outcome.ACK);
        verify(runner, never()).run(any()); verify(runner).stop(id);
        verify(repository).complete(id, 1, ExecutionResult.timedOut());
    }
    @Test void expiredDurableDeadlineDoesNotStartTheRunner() throws Exception {
        when(repository.claim(eq(id), anyLong())).thenReturn(Optional.of(
                new ExecutionRepository.Job(id, "PYTHON", "source", Instant.now().minusSeconds(1), 1, Instant.now().minusSeconds(2), Instant.now())));
        assertThat(processor.process(id)).isEqualTo(ExecutionProcessor.Outcome.ACK);
        verify(runner, never()).run(any()); verify(runner).stop(id);
        verify(repository).complete(id, 1, ExecutionResult.timedOut());
    }
    @Test void hangingCleanupIsBoundedAndDoesNotWriteTerminalState() throws Exception {
        when(runner.run(any())).thenThrow(new IllegalStateException());
        doAnswer(call -> { new CountDownLatch(1).await(); return null; }).when(runner).stop(id);
        assertThatThrownBy(() -> processor.process(id)).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).complete(any(), anyLong(), any());
    }
    @Test void startupRecoveryStopsThenConditionallyFailsInterruptedRows() throws Exception {
        when(repository.interrupted()).thenReturn(List.of(new ExecutionRepository.State(id, "RUNNING", 1)));
        processor.recover();
        var order = inOrder(runner, repository);
        order.verify(runner).stop(id); order.verify(repository).complete(id, 1, ExecutionResult.interrupted());
        verify(runner, never()).run(any());
    }
    private static RuntimeException outage() { return new DataAccessResourceFailureException("Injected outage"); }
}
