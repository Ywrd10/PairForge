package com.pairforge.worker.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pairforge.worker.execution.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static com.pairforge.worker.sandbox.SandboxTestSupport.docker;
import static org.mockito.Mockito.*;

class DockerSandboxLifecycleIT {
    @TempDir Path temporary;
    SandboxProperties p;
    DockerExecutionRunner runner;
    final FaultClient client = new FaultClient();
    static class FaultClient extends DockerCommandClient {
        volatile boolean denyControls, unavailable, ignorePhaseDeadline, expirePreparation, interruptExecution, loseDockerOnInterrupt;
        final AtomicInteger sourceCommands = new AtomicInteger();
        FaultClient() { super("docker"); }
        @Override public Result execute(List<String> args, long timeout, BoundedOutputCollector capture) throws Exception {
            if (unavailable) throw new IOException("Injected Docker unavailability");
            boolean source = args.getFirst().equals("exec") && (args.contains("/source/Main.java") || args.contains("/source/main.py") || args.getLast().equals("Main"));
            if (source) sourceCommands.incrementAndGet();
            if (source && interruptExecution) {
                if (loseDockerOnInterrupt) unavailable = true;
                Thread.currentThread().interrupt(); throw new InterruptedException("Injected shutdown");
            }
            if (expirePreparation && args.getFirst().equals("create")) { Thread.sleep(2100); return new Result(-1,true); }
            if (denyControls && args.getFirst().equals("inspect")) {
                var actual = new BoundedOutputCollector(262144);
                Result result = super.execute(args,timeout,actual);
                var tree = new ObjectMapper().readTree(actual.stdout());
                ((ObjectNode)tree.get(0).get("HostConfig")).put("Privileged",true);
                byte[] bytes = tree.toString().getBytes(StandardCharsets.UTF_8); capture.append(false,bytes,bytes.length);
                return result;
            }
            return super.execute(args,source && ignorePhaseDeadline ? 30000 : timeout,capture);
        }
    }
    void initialize(Map<String,Object> overrides) throws Exception {
        p = SandboxTestSupport.properties(temporary,"life-"+UUID.randomUUID().toString().substring(0,8),overrides);
        runner = new DockerExecutionRunner(p,client,new ObjectMapper()); runner.initialize();
    }
    @AfterEach void cleanup() throws Exception {
        client.unavailable = false; client.denyControls = false;
        if (runner != null) {
            runner.reconcile();
            assertThat(docker("ps","-aq","--filter","label="+DockerExecutionRunner.OWNER+"="+p.namespace())).isBlank();
            try (var paths=Files.list(temporary.resolve(p.namespace()))) { assertThat(paths.toList()).isEmpty(); }
        }
    }
    ExecutionRepository.Job job(String language,String source) { return new ExecutionRepository.Job(UUID.randomUUID(),language,source,Instant.now().plusSeconds(30),1); }
    @Test void missingControlFailsClosedBeforeSourceCommand() throws Exception {
        initialize(Map.of()); client.denyControls = true;
        assertThatThrownBy(() -> runner.run(job("JAVA","public class Main {}"))).hasMessageContaining("controls were not applied");
        assertThat(client.sourceCommands.get()).isZero();
    }
    @Test void compilationHasItsOwnDeadline() throws Exception {
        initialize(Map.of("compilation-ms",100));
        var result = runner.run(job("JAVA","public class Main { public static void main(String[] a) { System.out.println(1); } }"));
        assertThat(result.status()).isEqualTo(ExecutionResult.Status.TIMED_OUT);
        assertThat(client.sourceCommands.get()).isEqualTo(1); // javac only; never java after compile timeout.
    }
    @Test void preparationHasItsOwnDeadlineAndNeverStartsSource() throws Exception {
        initialize(Map.of("preparation-ms",2000)); client.expirePreparation = true;
        assertThat(runner.run(job("PYTHON","print(1)")).status()).isEqualTo(ExecutionResult.Status.TIMED_OUT);
        assertThat(client.sourceCommands.get()).isZero();
    }
    @Test void invalidSourceAndExpiredOverallDeadlineNeverStartSource() throws Exception {
        initialize(Map.of());
        assertThatThrownBy(() -> runner.run(job("PYTHON","a".repeat(65537)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runner.run(job("RUBY","print(1)"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(runner.run(new ExecutionRepository.Job(UUID.randomUUID(),"PYTHON","print(1)",Instant.now().minusSeconds(1),1)).status())
                .isEqualTo(ExecutionResult.Status.TIMED_OUT);
        assertThat(client.sourceCommands.get()).isZero();
    }
    @Test void periodicReconciliationKillsOverdueActivityIndependentlyOfBlockedExec() throws Exception {
        initialize(Map.of()); client.ignorePhaseDeadline = true;
        UUID id=UUID.randomUUID();
        try (var threads=Executors.newSingleThreadExecutor()) {
            var result=threads.submit(() -> runner.run(new ExecutionRepository.Job(id,"PYTHON","while True: pass",Instant.now().plusSeconds(3),1)));
            await().atMost(Duration.ofSeconds(5)).until(() -> client.sourceCommands.get() == 1);
            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).until(() -> { runner.reconcile(); return result.isDone(); });
            assertThat(result.get(5,TimeUnit.SECONDS).status()).isEqualTo(ExecutionResult.Status.TIMED_OUT);
            runner.stop(id); runner.stop(id);
        }
    }
    @Test void periodicOrphanCleanupRemovesOnlyOwnedContainersAndWorkspaces() throws Exception {
        initialize(Map.of()); UUID id=UUID.randomUUID(), directoryOnly=UUID.randomUUID();
        Path dir=temporary.resolve(p.namespace()).resolve(id.toString()); Files.createDirectory(dir); Files.writeString(dir.resolve("main.py"),"");
        Path stale=temporary.resolve(p.namespace()).resolve(directoryOnly.toString()); Files.createDirectory(stale); Files.writeString(stale.resolve("Main.java"),"");
        var capture=new BoundedOutputCollector(4096);
        assertThat(client.execute(runner.createArguments(id,"PYTHON",Instant.now().minusSeconds(1)),5000,capture).exitCode()).isZero();
        docker("start","pf-"+p.namespace()+"-"+id);
        String foreign="pf-foreign-"+UUID.randomUUID();
        docker("create","--name",foreign,"--label",DockerExecutionRunner.OWNER+"=another-worker",p.pythonImage());
        try {
            runner.reconcile();
            assertThat(docker("ps","-aq","--filter","name=^/"+foreign+"$")).isNotBlank();
        } finally { docker("rm",foreign); }
    }
    @Test void dockerLossCannotBeReportedAsSuccessfulCleanup() throws Exception {
        initialize(Map.of()); UUID id=UUID.randomUUID();
        client.unavailable = true;
        assertThatThrownBy(() -> runner.stop(id)).isInstanceOf(IOException.class);
        client.unavailable = false; runner.stop(id);
    }
    @Test void shutdownInterruptStillCleansContainerAndPreservesInterrupt() throws Exception {
        initialize(Map.of()); client.interruptExecution = true;
        try (var thread=Executors.newSingleThreadExecutor()) {
            var stopped=thread.submit(() -> {
                assertThatThrownBy(() -> runner.run(job("PYTHON","print(1)"))).isInstanceOf(InterruptedException.class);
                return Thread.currentThread().isInterrupted();
            });
            assertThat(stopped.get(15,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(docker("ps","-aq","--filter","label="+DockerExecutionRunner.OWNER+"="+p.namespace())).isBlank();
    }
    @Test void interruptionWithDockerLossCannotCompleteUntilRecoveryCleansRealResources() throws Exception {
        initialize(Map.of());
        var job = job("PYTHON", "print('not rerun')");
        var repository = mock(ExecutionRepository.class);
        when(repository.state(job.id())).thenReturn(Optional.of(new ExecutionRepository.State(job.id(), "QUEUED", 0)));
        when(repository.claim(eq(job.id()), anyLong())).thenReturn(Optional.of(job));
        when(repository.interrupted()).thenReturn(List.of(new ExecutionRepository.State(job.id(), "RUNNING", 1)));
        var events = mock(ExecutionEventPublisher.class);
        var processor = new ExecutionProcessor(repository, runner, new WorkerProperties(true, true, 30000, 10000, 0), events);
        client.interruptExecution = true; client.loseDockerOnInterrupt = true;
        try {
            assertThatThrownBy(() -> processor.process(job.id())).isInstanceOf(IllegalStateException.class);
            verify(repository, never()).complete(any(), anyLong(), any());
            assertThat(docker("ps", "-aq", "--filter", "label=" + DockerExecutionRunner.OWNER + "=" + p.namespace())).isNotBlank();
            assertThat(temporary.resolve(p.namespace()).resolve(job.id().toString()).resolve("main.py")).exists();
            client.unavailable = false;
            when(repository.complete(job.id(), 1, ExecutionResult.interrupted())).thenAnswer(call -> {
                // Assert ordering at the persistence boundary, not just cleanup at test teardown.
                assertThat(docker("ps", "-aq", "--filter", "label=" + DockerExecutionRunner.OWNER + "=" + p.namespace())).isBlank();
                assertThat(temporary.resolve(p.namespace()).resolve(job.id().toString())).doesNotExist();
                return 1;
            });
            processor.recover();
            verify(repository).complete(job.id(), 1, ExecutionResult.interrupted());
            assertThat(client.sourceCommands.get()).isEqualTo(1);
        } finally { client.unavailable = false; processor.close(); }
    }
    @Test void noncanonicalWorkspaceIsNotDeletedOrSilentlyIgnored() throws Exception {
        initialize(Map.of());
        Path unexpected=temporary.resolve(p.namespace()).resolve("1-1-1-1-1");
        Files.createDirectory(unexpected);
        try {
            assertThatThrownBy(runner::reconcile).isInstanceOf(IllegalStateException.class).hasMessageContaining("Noncanonical");
            assertThat(unexpected).exists();
        } finally { Files.delete(unexpected); }
    }
    @Test void wrongLanguageImageFailsStartupBeforeAdmittingSource() throws Exception {
        var images=SandboxTestSupport.properties(temporary,"images",Map.of());
        assertThatThrownBy(() -> initialize(Map.of("java-image",images.pythonImage())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Docker control operation failed");
        assertThat(client.sourceCommands.get()).isZero();
    }
}
