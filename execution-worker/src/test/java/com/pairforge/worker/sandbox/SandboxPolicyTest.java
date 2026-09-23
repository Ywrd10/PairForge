package com.pairforge.worker.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SandboxPolicyTest {
    @Test void exactCombinedBoundaryDoesNotTruncateUntilAnotherByteArrives() {
        var output = new BoundedOutputCollector(4);
        output.append(false, new byte[]{65,66}, 2); output.append(true, new byte[]{67,68}, 2);
        assertThat(output.overflow()).isFalse();
        output.append(false, new byte[]{69}, 1);
        assertThat(output.overflow()).isTrue(); assertThat(output.stdout()).isEqualTo("AB"); assertThat(output.stderr()).isEqualTo("CD");
    }
    @Test void concurrentStreamsCannotExceedRetentionBudget() throws Exception {
        var output = new BoundedOutputCollector(65536);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<?>>();
            for (boolean stderr : List.of(false,true)) futures.add(threads.submit(() -> {
                byte[] bytes = new byte[4096]; Arrays.fill(bytes, (byte)'a');
                for (int n=0; n<1000; n++) output.append(stderr,bytes,bytes.length);
            }));
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
        }
        assertThat(output.stdout().length()+output.stderr().length()).isEqualTo(65536); assertThat(output.overflow()).isTrue();
    }
    @Test void malformedUtf8NulsAndPartialCodepointsDoNotExpandOrCorruptResult() {
        var output = new BoundedOutputCollector(7);
        byte[] bytes = {65,0,(byte)0xff,(byte)0xe2,(byte)0x82,(byte)0xac,(byte)0xe2,(byte)0x82};
        output.append(false,bytes,bytes.length);
        assertThat(output.stdout()).isEqualTo("A€");
        assertThat(output.stdout().getBytes(StandardCharsets.UTF_8)).hasSize(4);
        assertThat(output.overflow()).isTrue();
    }
    @Test void mutableImagesAndUnsafeBudgetsAreRejected() {
        assertThatThrownBy(() -> SandboxTestSupport.bind(Map.of("enabled",true,"java-image","java:latest","python-image","python:latest"))).isInstanceOf(Exception.class);
        for (Map<String,Object> invalid : List.<Map<String,Object>>of(Map.of("output-bytes",65537),Map.of("source-bytes",65537),
                Map.of("namespace","../escape"),Map.of("cpus",Double.NaN),Map.of("python-pids",0),Map.of("workspace-mib",1024),Map.of("runtime-ms",0)))
            assertThatThrownBy(() -> SandboxTestSupport.bind(invalid)).isInstanceOf(Exception.class);
    }
    @Test void kernelMustEnforceEveryRequiredControl() {
        var p = SandboxTestSupport.bind(Map.of());
        var runner = new DockerExecutionRunner(p,new DockerCommandClient("docker"),new ObjectMapper());
        String valid = "10001\n134217728\n0\n32\n100000 100000\nCapEff:\t0000000000000000\nNoNewPrivs:\t1\nSeccomp:\t2\n";
        runner.verifyKernel(valid,"PYTHON");
        for (String invalid : List.of(valid.replace("10001","0"),valid.replace("134217728","max"),valid.replace("\n0\n","\nmax\n"),
                valid.replace("32\n","max\n"),valid.replace("100000 100000","max 100000"),valid.replace("0000000000000000","0000000000000001"),
                valid.replace("NoNewPrivs:\t1","NoNewPrivs:\t0"),valid.replace("Seccomp:\t2","Seccomp:\t0")))
            assertThatThrownBy(() -> runner.verifyKernel(invalid,"PYTHON")).isInstanceOf(IllegalStateException.class);
    }
    @Test void unavailableDockerHasNoLocalExecutionFallback() {
        assertThatThrownBy(() -> new DockerCommandClient("nonexistent-pairforge-docker-command").execute(List.of("info"),500,new BoundedOutputCollector(1024)))
                .isInstanceOf(java.io.IOException.class);
    }
}
