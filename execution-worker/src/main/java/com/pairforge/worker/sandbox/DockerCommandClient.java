package com.pairforge.worker.sandbox;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Runs only the trusted Docker CLI locally. Submitted source never enters command arguments. */
public class DockerCommandClient {
    public record Result(int exitCode, boolean timedOut) {}
    private final String executable;
    public DockerCommandClient(String executable) { this.executable = executable; }
    public Result execute(List<String> args, long timeoutMs, BoundedOutputCollector capture) throws Exception {
        if (timeoutMs <= 0) return new Result(-1, true);
        var command = new ArrayList<String>(); command.add(executable); command.addAll(args);
        Process process = start(command);
        process.getOutputStream().close();
        var readers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<?> stdout = readers.submit(() -> drain(process.getInputStream(), false, capture));
            Future<?> stderr = readers.submit(() -> drain(process.getErrorStream(), true, capture));
            boolean timedOut = false;
            boolean terminatedForLimit = false;
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            try {
                while (!process.waitFor(10, TimeUnit.MILLISECONDS)) {
                    if (capture.overflow() || System.nanoTime() >= deadline) {
                        timedOut = !capture.overflow();
                        terminatedForLimit = true;
                        process.destroyForcibly(); break;
                    }
                }
                if (!process.waitFor(1000, TimeUnit.MILLISECONDS)) throw new IOException("Docker CLI would not stop");
                awaitDrain(stdout, terminatedForLimit); awaitDrain(stderr, terminatedForLimit);
                return new Result(process.exitValue(), timedOut);
            } finally {
                process.destroyForcibly();
                process.getInputStream().close(); process.getErrorStream().close();
                stdout.cancel(true); stderr.cancel(true);
            }
        } finally { readers.shutdownNow(); }
    }
    Process start(List<String> command) throws IOException { return new ProcessBuilder(command).start(); }
    private static void awaitDrain(Future<?> reader, boolean terminatedForLimit)
            throws InterruptedException, ExecutionException, TimeoutException {
        try { reader.get(1000, TimeUnit.MILLISECONDS); }
        catch (ExecutionException error) {
            // Forced CLI termination can close a pipe while its reader is inside read().
            // Only tolerate this after our own limit kill and confirmed process exit;
            // the caller still reports overflow/timeout and verifies container cleanup.
            if (!terminatedForLimit || !(error.getCause() instanceof UncheckedIOException)) throw error;
        }
    }
    private static void drain(InputStream stream, boolean stderr, BoundedOutputCollector capture) {
        try (stream) {
            byte[] bytes = new byte[4096]; int count;
            while ((count = stream.read(bytes)) != -1) capture.append(stderr, bytes, count);
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }
}
