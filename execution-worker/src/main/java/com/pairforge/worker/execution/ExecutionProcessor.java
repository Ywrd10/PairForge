package com.pairforge.worker.execution;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

public class ExecutionProcessor implements AutoCloseable {
    public enum Outcome { ACK, REJECT }
    private final ExecutionRepository repository;
    private final ExecutionRunner runner;
    private final WorkerProperties properties;
    private final ExecutorService runs = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("execution-runner").factory());
    private final ExecutorService cleanup = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("execution-cleanup").factory());
    public ExecutionProcessor(ExecutionRepository repository, ExecutionRunner runner, WorkerProperties properties) {
        this.repository = repository; this.runner = runner; this.properties = properties;
    }
    public Outcome process(UUID id) {
        var state = retry(() -> repository.state(id));
        if (state.isEmpty()) return Outcome.REJECT;
        if (state.get().terminal() || state.get().status().equals("RUNNING")) return Outcome.ACK;
        Optional<ExecutionRepository.Job> claimed = Optional.empty();
        boolean uncertainClaim = false;
        long claimStarted = System.nanoTime();
        for (int attempt = 1; attempt <= 3; attempt++) {
            try { claimed = repository.claim(id, properties.deadlineMs()); break; }
            catch (RuntimeException error) {
                uncertainClaim = true;
                if (attempt == 3) throw error;
                backoff(attempt);
            }
        }
        if (claimed.isEmpty()) {
            // A lost commit response may have claimed this row without starting the runner.
            if (uncertainClaim) throw new IllegalStateException("Claim outcome requires restart recovery");
            return Outcome.ACK;
        }
        var job = claimed.get();
        final ExecutionResult result = run(job, claimStarted);
        persist(job.id(), job.revision(), result);
        return Outcome.ACK;
    }
    public void recover() {
        try { runner.initialize(); }
        catch (Exception error) { throw new IllegalStateException("Runner initialization failed", error); }
        for (var state : retry(repository::interrupted)) {
            stop(state.id());
            persist(state.id(), state.revision(), ExecutionResult.interrupted());
        }
    }
    public void reconcile() throws Exception { runner.reconcile(); }
    public long reconciliationMs() { return runner.reconciliationMs(); }
    private ExecutionResult run(ExecutionRepository.Job job, long claimStarted) {
        // Database/worker clocks may differ. A monotonic local budget prevents skew
        // or slow claim retries from extending the configured overall deadline.
        long localRemaining = properties.deadlineMs()
                - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - claimStarted);
        long remaining = Math.min(localRemaining, Duration.between(Instant.now(), job.deadline()).toMillis());
        if (remaining <= 0) { stop(job.id()); return ExecutionResult.timedOut(); }
        var exited = new CountDownLatch(1);
        var future = runs.submit(() -> {
            try { return Objects.requireNonNull(runner.run(job), "Runner result"); }
            finally { exited.countDown(); }
        });
        try { return future.get(remaining, TimeUnit.MILLISECONDS); }
        catch (TimeoutException error) {
            stopAndJoin(job.id(), future, exited);
            return ExecutionResult.timedOut();
        } catch (ExecutionException error) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Runner failed executionId={} type={}",
                    job.id(), error.getCause().getClass().getSimpleName());
            stopAndJoin(job.id(), future, exited);
            return ExecutionResult.interrupted();
        } catch (InterruptedException error) {
            // Shutdown is not permission to acknowledge or report a successful cleanup.
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Worker interrupted; restart recovery required");
        }
    }
    private void stopAndJoin(UUID id, Future<?> future, CountDownLatch exited) {
        stop(id);
        future.cancel(true);
        try {
            if (!exited.await(properties.cleanupTimeoutMs(), TimeUnit.MILLISECONDS))
                throw new IllegalStateException("Runner did not stop");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Cleanup interrupted");
        }
    }
    private void stop(UUID id) {
        awaitCleanup(() -> { runner.stop(id); return null; });
    }
    private void awaitCleanup(Callable<Void> operation) {
        var stopped = cleanup.submit(operation);
        try { stopped.get(properties.cleanupTimeoutMs(), TimeUnit.MILLISECONDS); }
        catch (InterruptedException error) {
            stopped.cancel(true); Thread.currentThread().interrupt(); throw new IllegalStateException("Cleanup interrupted");
        } catch (ExecutionException | TimeoutException error) {
            stopped.cancel(true); throw new IllegalStateException("Cleanup could not be verified");
        }
    }
    private void persist(UUID id, long revision, ExecutionResult result) {
        // Retain this bounded result through retries; never invoke the runner again.
        retry(() -> {
            if (repository.complete(id, revision, result) == 0) {
                var current = repository.state(id).orElseThrow();
                if (!current.terminal()) throw new IllegalStateException("Execution ownership changed");
            }
            return true;
        });
    }
    private <T> T retry(Supplier<T> operation) {
        for (int attempt = 1; ; attempt++) {
            try { return operation.get(); }
            catch (RuntimeException error) { if (attempt == 3) throw error; backoff(attempt); }
        }
    }
    private void backoff(int attempt) {
        try { Thread.sleep(properties.retryBackoffMs() * attempt); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("Retry interrupted"); }
    }
    @Override public void close() {
        runs.shutdownNow();
        try {
            // Keep the shutdown hook alive long enough for the sandbox's bounded finally-cleanup.
            if (!runs.awaitTermination(properties.cleanupTimeoutMs(), TimeUnit.MILLISECONDS))
                throw new IllegalStateException("Runner shutdown could not be verified; restart recovery required");
            // Listener cancellation and executor shutdown can both interrupt a run's finally block.
            // A stopped host thread alone is not proof its container was removed.
            awaitCleanup(() -> { runner.reconcile(); return null; });
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Worker shutdown interrupted; restart recovery required");
        } finally { cleanup.shutdownNow(); }
    }
}
