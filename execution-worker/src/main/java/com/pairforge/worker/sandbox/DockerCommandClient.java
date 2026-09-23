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
        Process process = new ProcessBuilder(command).start();
        process.getOutputStream().close();
        var readers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<?> stdout = readers.submit(() -> drain(process.getInputStream(), false, capture));
            Future<?> stderr = readers.submit(() -> drain(process.getErrorStream(), true, capture));
            boolean timedOut = false;
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            try {
                while (!process.waitFor(10, TimeUnit.MILLISECONDS)) {
                    if (capture.overflow() || System.nanoTime() >= deadline) {
                        timedOut = !capture.overflow();
                        process.destroyForcibly(); break;
                    }
                }
                if (!process.waitFor(1000, TimeUnit.MILLISECONDS)) throw new IOException("Docker CLI would not stop");
                stdout.get(1000, TimeUnit.MILLISECONDS); stderr.get(1000, TimeUnit.MILLISECONDS);
                return new Result(process.exitValue(), timedOut);
            } finally {
                process.destroyForcibly();
                process.getInputStream().close(); process.getErrorStream().close();
                stdout.cancel(true); stderr.cancel(true);
            }
        } finally { readers.shutdownNow(); }
    }
    private static void drain(InputStream stream, boolean stderr, BoundedOutputCollector capture) {
        try (stream) {
            byte[] bytes = new byte[4096]; int count;
            while ((count = stream.read(bytes)) != -1) capture.append(stderr, bytes, count);
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }
}
