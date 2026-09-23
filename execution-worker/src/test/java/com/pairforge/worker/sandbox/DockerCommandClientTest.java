package com.pairforge.worker.sandbox;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DockerCommandClientTest {
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void forcedTerminationPreservesOutputLimitOrTimeoutWhenPipesClose(boolean overflow) throws Exception {
        var killed = new CountDownLatch(1);
        var process = mock(Process.class);
        when(process.getOutputStream()).thenReturn(OutputStream.nullOutputStream());
        when(process.getInputStream()).thenReturn(closingPipe(killed, overflow));
        when(process.getErrorStream()).thenReturn(closingPipe(killed, false));
        when(process.waitFor(anyLong(), any())).thenAnswer(call ->
                killed.await(call.getArgument(0, Long.class), call.getArgument(1, TimeUnit.class)));
        when(process.destroyForcibly()).thenAnswer(call -> { killed.countDown(); return process; });
        when(process.exitValue()).thenReturn(137);
        var output = new BoundedOutputCollector(8);
        var result = client(process).execute(List.of("exec"), overflow ? 2000 : 20, output);
        assertThat(killed.getCount()).isZero();
        assertThat(result.timedOut()).isEqualTo(!overflow);
        assertThat(output.overflow()).isEqualTo(overflow);
        if (overflow) assertThat(output.stdout()).isEqualTo("xxxxxxxx");
    }

    @Test void unexpectedOutputReadFailureStillFailsClosed() throws Exception {
        var process = mock(Process.class);
        when(process.getOutputStream()).thenReturn(OutputStream.nullOutputStream());
        when(process.getInputStream()).thenReturn(new InputStream() {
            @Override public int read() throws IOException { throw new IOException("Injected broken pipe"); }
        });
        when(process.getErrorStream()).thenReturn(InputStream.nullInputStream());
        when(process.waitFor(anyLong(), any())).thenReturn(true);
        assertThatThrownBy(() -> client(process).execute(List.of("exec"), 2000, new BoundedOutputCollector(8)))
                .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(UncheckedIOException.class);
    }

    private static DockerCommandClient client(Process process) {
        return new DockerCommandClient("docker") {
            @Override Process start(List<String> command) { return process; }
        };
    }
    private static InputStream closingPipe(CountDownLatch killed, boolean emitOverflow) {
        return new InputStream() {
            private boolean emitted;
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                if (emitOverflow && !emitted) {
                    emitted = true;
                    Arrays.fill(bytes, offset, offset + length, (byte) 'x');
                    return length;
                }
                try {
                    if (!killed.await(5, TimeUnit.SECONDS)) throw new IOException("Fixture was not terminated");
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                throw new IOException("Stream closed");
            }
            @Override public int read() { throw new AssertionError("Expected buffered read"); }
        };
    }
}
