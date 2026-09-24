package com.pairforge.worker.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pairforge.worker.execution.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static com.pairforge.worker.sandbox.SandboxTestSupport.docker;

/** Controlled fixtures only, in a Linux Docker VM/ephemeral CI host. Preflight precedes every source. */
class DockerSandboxIT {
    @TempDir static Path temporary;
    static DockerExecutionRunner runner;
    static SandboxProperties p;
    static final io.micrometer.core.instrument.simple.SimpleMeterRegistry metrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    @BeforeAll static void prepare() throws Exception {
        p = SandboxTestSupport.properties(temporary, "test-" + UUID.randomUUID().toString().substring(0, 8), Map.of());
        runner = new DockerExecutionRunner(p, new DockerCommandClient("docker"), new ObjectMapper(), metrics);
        runner.initialize();
    }
    @AfterEach void clean() throws Exception {
        runner.reconcile();
        assertThat(docker("ps", "-aq", "--filter", "label=" + DockerExecutionRunner.OWNER + "=" + p.namespace())).isBlank();
        try (var paths = Files.list(temporary.resolve(p.namespace()))) { assertThat(paths.toList()).isEmpty(); }
    }
    ExecutionResult run(String language, String source) throws Exception {
        var result = runner.run(new ExecutionRepository.Job(UUID.randomUUID(), language, source, Instant.now().plusSeconds(30), 1, Instant.now().minusSeconds(2), Instant.now()));
        System.out.printf("Sandbox fixture language=%s status=%s reason=%s durationMs=%d%n", language, result.status(), result.failureReason(), result.durationMs());
        return result;
    }
    @Test void validJavaUsesStandardLibraryAndClosedStdin() throws Exception {
        var compile = metrics.timer("pairforge.execution.phase", "language", "JAVA", "phase", "compilation");
        long before = compile.count();
        var result = run("JAVA", "public class Main { public static void main(String[] a) throws Exception { System.out.println(java.util.List.of(1,2,3)); System.out.println(System.in.read()); } }");
        assertThat(result.status()).isEqualTo(ExecutionResult.Status.SUCCEEDED);
        assertThat(result.stdout()).isEqualTo("[1, 2, 3]\n-1\n"); assertThat(result.stderr()).isEmpty();
        assertThat(compile.count()).isEqualTo(before + 1);
        assertThat(compile.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isPositive();
    }
    @Test void validPythonUsesStandardLibraryAndClosedStdin() throws Exception {
        var result = run("PYTHON", "import sys, json\nprint(json.dumps([1,2,3]))\nprint(repr(sys.stdin.read()))");
        assertThat(result.status()).isEqualTo(ExecutionResult.Status.SUCCEEDED);
        assertThat(result.stdout()).isEqualTo("[1, 2, 3]\n''\n");
    }
    @Test void javaCompilationError() throws Exception {
        var result = run("JAVA", "public class Main { syntax error }");
        assertThat(result.failureReason()).isEqualTo(ExecutionResult.FailureReason.COMPILATION_ERROR);
        assertThat(result.stderr()).contains("error:");
    }
    @Test void pythonRuntimeError() throws Exception {
        var result = run("PYTHON", "raise ValueError('expected failure')");
        assertThat(result.failureReason()).isEqualTo(ExecutionResult.FailureReason.RUNTIME_ERROR);
        assertThat(result.stderr()).contains("ValueError");
    }
    @Test void javaRuntimeError() throws Exception {
        var result = run("JAVA", "public class Main { public static void main(String[] a) { throw new IllegalStateException(); } }");
        assertThat(result.failureReason()).isEqualTo(ExecutionResult.FailureReason.RUNTIME_ERROR);
    }
    @Test void infiniteLoopTimesOut() throws Exception {
        var result = run("PYTHON", "while True: pass");
        assertThat(result.status()).isEqualTo(ExecutionResult.Status.TIMED_OUT);
        assertThat(result.durationMs()).isBetween(5000L, 15000L);
    }
    @Test void simultaneousStreamsHaveOneBoundedBudgetAndTerminate() throws Exception {
        var result = run("PYTHON", "import os, threading\ndef flood(fd):\n while True: os.write(fd, b'x'*4096)\nthreading.Thread(target=flood,args=(2,)).start()\nflood(1)");
        assertThat(result.failureReason()).isEqualTo(ExecutionResult.FailureReason.OUTPUT_LIMIT);
        assertThat(result.outputTruncated()).isTrue();
        assertThat(result.stdout().getBytes(StandardCharsets.UTF_8).length + result.stderr().getBytes(StandardCharsets.UTF_8).length).isEqualTo(65536);
        assertThat(result.durationMs()).isLessThan(10000);
    }
    @Test void memoryExhaustionIsClassifiedFromCgroupMetadata() throws Exception {
        var result = run("PYTHON", "items=[]\nwhile True: items.append(bytearray(8*1024*1024))");
        assertThat(result.failureReason()).isEqualTo(ExecutionResult.FailureReason.MEMORY_LIMIT);
    }
    @Test void filesystemNetworkAndIdentityRestrictionsAreEffective() throws Exception {
        var result = run("PYTHON", """
                import os, socket, subprocess, importlib.util
                assert os.getuid() == 10001
                for path in ['/source/main.py','/etc/forbidden','/var/run/docker.sock']:
                    try: open(path,'wb'); raise AssertionError('writable protected path')
                    except OSError: pass
                assert not os.path.exists('/var/run/docker.sock')
                assert 'SPRING_DATASOURCE_PASSWORD' not in os.environ
                assert all(os.environ.get(key, '') == '' for key in ['HTTP_PROXY','HTTPS_PROXY','ALL_PROXY','http_proxy','https_proxy','all_proxy'])
                assert importlib.util.find_spec('pip') is None
                assert importlib.util.find_spec('ensurepip') is None
                s=socket.socket(); s.settimeout(0.5)
                try: s.connect(('1.1.1.1',53)); raise AssertionError('external network reachable')
                except OSError: pass
                for path in ['/work/fill','/tmp/fill','/dev/shm/fill']:
                    try:
                        with open(path,'wb') as f:
                            for _ in range(70): f.write(b'x'*1024*1024)
                        raise AssertionError('unbounded storage')
                    except OSError as e: assert e.errno == 28
                    finally:
                        if os.path.exists(path): os.remove(path)
                print('controls enforced')
                """);
        assertThat(result.status()).isEqualTo(ExecutionResult.Status.SUCCEEDED);
        assertThat(result.stdout()).contains("controls enforced");
    }
    @Test void processLimitRejectsAdditionalChildren() throws Exception {
        var result = run("PYTHON", """
                import subprocess
                children=[]
                try:
                    for _ in range(40): children.append(subprocess.Popen(['/bin/sleep','10']))
                    raise AssertionError('process limit missing')
                except OSError as e:
                    assert e.errno == 11
                    print('process limit enforced')
                finally:
                    for child in children: child.kill()
                    for child in children: child.wait()
                """);
        assertThat(result.status()).isEqualTo(ExecutionResult.Status.SUCCEEDED);
        assertThat(result.stdout()).contains("process limit enforced");
    }
    @Test void cpuQuotaActuallyThrottlesBusyProgram() throws Exception {
        var limited = SandboxTestSupport.properties(temporary, p.namespace() + "-cpu", Map.of("cpus", 0.5));
        var cpuRunner = new DockerExecutionRunner(limited, new DockerCommandClient("docker"), new ObjectMapper(), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        cpuRunner.initialize();
        var result = cpuRunner.run(new ExecutionRepository.Job(UUID.randomUUID(), "PYTHON", """
                import time
                end=time.monotonic()+1.2
                while time.monotonic()<end: pass
                stats=dict(line.split() for line in open('/sys/fs/cgroup/cpu.stat'))
                assert int(stats['nr_throttled']) > 0
                print('cpu throttled')
                """, Instant.now().plusSeconds(30), 1, Instant.now().minusSeconds(2), Instant.now()));
        cpuRunner.reconcile();
        assertThat(docker("ps", "-aq", "--filter", "label=" + DockerExecutionRunner.OWNER + "=" + limited.namespace())).isBlank();
        assertThat(result.status()).withFailMessage(result.stderr()).isEqualTo(ExecutionResult.Status.SUCCEEDED);
        assertThat(result.stdout()).contains("cpu throttled");
    }
    @Test void javaMemoryExhaustionIsContained() throws Exception {
        var result = run("JAVA", """
                public class Main {
                    public static void main(String[] a) {
                        var keep = new java.util.ArrayList<Object>();
                        byte[] heap = new byte[64*1024*1024];
                        java.util.Arrays.fill(heap,(byte)1); keep.add(heap);
                        while (true) {
                            var buffer = java.nio.ByteBuffer.allocateDirect(8*1024*1024);
                            for (int i=0; i<buffer.capacity(); i+=4096) buffer.put(i,(byte)1);
                            keep.add(buffer);
                        }
                    }
                }
                """);
        assertThat(result.failureReason()).withFailMessage("exit=%s stdout=%s stderr=%s",result.exitCode(),result.stdout(),result.stderr())
                .isEqualTo(ExecutionResult.FailureReason.MEMORY_LIMIT);
    }
    @Test void commandLikeTextRemainsSourceDataAndInvalidOutputIsSafeForPostgres() throws Exception {
        var result = run("PYTHON", "import os\nprint('$(echo host); `echo host`; --privileged')\nos.write(2,b'\\xff\\x00ok')");
        assertThat(result.status()).isEqualTo(ExecutionResult.Status.SUCCEEDED);
        assertThat(result.stdout()).isEqualTo("$(echo host); `echo host`; --privileged\n");
        assertThat(result.stderr()).isEqualTo("ok");
    }
    @Test void applicationChosenExitCodeIsNotProofOfMemoryExhaustion() throws Exception {
        assertThat(run("JAVA","public class Main { public static void main(String[] a) { System.exit(3); } }").failureReason())
                .isEqualTo(ExecutionResult.FailureReason.RUNTIME_ERROR);
    }
    @Test void measureRepresentativeLanguageMemoryPeaksWithinConfiguredLimits() throws Exception {
        for (String language : List.of("JAVA","PYTHON")) {
            String source=language.equals("JAVA")
                    ? "public class Main { public static void main(String[] a) throws Exception { System.out.print(java.nio.file.Files.readString(java.nio.file.Path.of(\"/sys/fs/cgroup/memory.peak\"))); } }"
                    : "print(open('/sys/fs/cgroup/memory.peak').read().strip())";
            var result=run(language,source);
            assertThat(result.status()).isEqualTo(ExecutionResult.Status.SUCCEEDED);
            long peak=Long.parseLong(result.stdout().trim());
            assertThat(peak).isBetween(1L,p.memory(language));
            System.out.printf("Sandbox measurement language=%s peakBytes=%d durationMs=%d memoryMiB=%d pids=%d cpus=%.1f%n",
                    language,peak,result.durationMs(),p.memory(language)/1048576,p.pids(language),p.cpus());
        }
    }
}
