package com.pairforge.worker.execution;

import com.pairforge.worker.ExecutionWorkerApplication;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.MountableFile;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Testcontainers
class ExecutionWorkerIT {
    static final String WORKER = "pairforge_worker_test";
    static final String PASSWORD = UUID.randomUUID().toString();
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withPassword(UUID.randomUUID().toString()).withEnv("WORKER_DB_USER", WORKER).withEnv("WORKER_DB_PASSWORD", PASSWORD)
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("..", "scripts", "provision-worker.sql").toAbsolutePath()), "/tmp/provision-worker.sql");
    @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1.8")
            .withAdminUser("test").withAdminPassword(UUID.randomUUID().toString());
    static final UUID USER = UUID.randomUUID(), ROOM = UUID.randomUUID();
    static JdbcTemplate adminDb;
    static CachingConnectionFactory broker;
    static RabbitAdmin admin;
    static RabbitTemplate rabbit;
    ConfigurableApplicationContext app;
    FakeRunner fake;
    @TempDir Path temporary;
    static final ExecutionResult SUCCESS = new ExecutionResult(ExecutionResult.Status.SUCCEEDED, "hello", "", 0, 1L, null, false);
    static class FakeRunner implements ExecutionRunner {
        final AtomicInteger calls = new AtomicInteger(), stops = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        volatile boolean block, failCleanup, pauseDatabase, denyResultWrite;
        volatile ExecutionRepository.Job snapshot;
        @Override public ExecutionResult run(ExecutionRepository.Job job) throws Exception {
            calls.incrementAndGet(); snapshot = job; entered.countDown();
            if (block) release.await();
            if (denyResultWrite) adminDb.execute("revoke update (status, stdout, stderr, exit_code, duration_ms, started_at, completed_at, deadline_at, state_revision, failure_reason, output_truncated) on executions from " + WORKER);
            if (pauseDatabase) PG.getDockerClient().pauseContainerCmd(PG.getContainerId()).exec();
            return SUCCESS;
        }
        @Override public void stop(UUID id) {
            stops.incrementAndGet();
            if (failCleanup) throw new IllegalStateException("Injected cleanup failure");
            release.countDown();
        }
    }
    @BeforeAll static void initialize() throws Exception {
        Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("filesystem:../backend/src/main/resources/db/migration").load().migrate();
        assertThat(PG.execInContainer("psql", "-U", PG.getUsername(), "-d", PG.getDatabaseName(), "-f", "/tmp/provision-worker.sql").getExitCode()).isZero();
        adminDb = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        adminDb.update("insert into users(id,email,password_hash,created_at) values(?,?,'test-only',now())", USER, USER + "@example.test");
        adminDb.update("insert into rooms(id,owner_id,name,language,invitation_token_hash,created_at,updated_at) values(?,?,'Worker test','PYTHON',?,now(),now())", ROOM, USER, "a".repeat(64));
        broker = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        broker.setUsername(RABBIT.getAdminUsername()); broker.setPassword(RABBIT.getAdminPassword());
        admin = new RabbitAdmin(broker); rabbit = new RabbitTemplate(broker);
        admin.declareExchange(new DirectExchange("pairforge.execution", true, false));
        admin.declareQueue(new org.springframework.amqp.core.Queue(ExecutionConsumer.QUEUE, true));
        admin.declareBinding(new Binding(ExecutionConsumer.QUEUE, Binding.DestinationType.QUEUE, "pairforge.execution", ExecutionConsumer.QUEUE, Map.of()));
    }
    @BeforeEach void clean() { adminDb.update("delete from executions"); admin.purgeQueue(ExecutionConsumer.QUEUE); fake = new FakeRunner(); }
    @AfterEach void stop() { if (app != null) { app.close(); app = null; } fake.release.countDown(); }
    @AfterAll static void closeBroker() { if (broker != null) broker.destroy(); }
    void start() { start(5000); }
    void start(long deadlineMs) {
        Map<String, Object> p = new HashMap<>();
        p.put("spring.datasource.url", PG.getJdbcUrl()); p.put("spring.datasource.username", WORKER); p.put("spring.datasource.password", PASSWORD);
        p.put("spring.rabbitmq.host", RABBIT.getHost()); p.put("spring.rabbitmq.port", RABBIT.getAmqpPort());
        p.put("spring.rabbitmq.username", RABBIT.getAdminUsername()); p.put("spring.rabbitmq.password", RABBIT.getAdminPassword());
        p.put("pairforge.worker.enabled", true); p.put("pairforge.worker.previous-worker-stopped", true);
        p.put("pairforge.worker.deadline-ms", deadlineMs); p.put("pairforge.worker.cleanup-timeout-ms", 500);
        p.put("pairforge.worker.retry-backoff-ms", 10); p.put("server.port", 0); p.put("logging.level.root", "WARN");
        app = new SpringApplicationBuilder(ExecutionWorkerApplication.class).initializers(context -> {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("testWorker", p));
            context.getBeanFactory().registerSingleton("testRunner", fake);
        }).run();
    }
    ExecutionRepository repository() { return app.getBean(ExecutionRepository.class); }
    UUID row(String status) {
        UUID id = UUID.randomUUID();
        adminDb.update("insert into executions(id,room_id,submitted_by,language,source_code,status,created_at) values(?,?,?,'PYTHON','immutable source',?,now())", id, ROOM, USER, status);
        if (status.equals("RUNNING")) adminDb.update("update executions set started_at=now(),deadline_at=now()+interval '1 minute',state_revision=1 where id=?", id);
        return id;
    }
    String status(UUID id) { return adminDb.queryForObject("select status from executions where id=?", String.class, id); }
    long revision(UUID id) { return adminDb.queryForObject("select state_revision from executions where id=?", Long.class, id); }
    void send(UUID id) { sendBody("{\"schemaVersion\":1,\"executionId\":\"" + id + "\"}"); }
    void sendBody(String value) {
        var p = new MessageProperties(); p.setContentType("application/json"); p.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        rabbit.send("pairforge.execution", ExecutionConsumer.QUEUE, new Message(value.getBytes(java.nio.charset.StandardCharsets.UTF_8), p));
    }
    void terminal(UUID id, String expected) { await().atMost(Duration.ofSeconds(12)).untilAsserted(() -> assertThat(status(id)).isEqualTo(expected)); }
    void drained() { await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(admin.getQueueInfo(ExecutionConsumer.QUEUE).getMessageCount()).isZero()); }
    int probe(String name) throws Exception {
        int port = ((ServletWebServerApplicationContext) app).getWebServer().getPort();
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health/" + name))
                .timeout(Duration.ofSeconds(8)).build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }
    @Test void consumesImmutableSnapshotAndPersistsBeforeAckWithDuplicates() throws Exception {
        start(); UUID id = row("QUEUED"); fake.block = true;
        send(id); send(id); send(id);
        assertThat(fake.entered.await(5, TimeUnit.SECONDS)).isTrue();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var queue = admin.getQueueInfo(ExecutionConsumer.QUEUE);
            assertThat(queue.getConsumerCount()).isEqualTo(1);
            assertThat(queue.getMessageCount()).isEqualTo(2); // Prefetch one leaves both duplicates on the broker.
        });
        assertThat(status(id)).isEqualTo("RUNNING"); assertThat(revision(id)).isEqualTo(1);
        assertThat(fake.snapshot.source()).isEqualTo("immutable source"); assertThat(fake.snapshot.language()).isEqualTo("PYTHON");
        fake.release.countDown(); terminal(id, "SUCCEEDED");
        send(id); drained();
        assertThat(fake.calls.get()).isEqualTo(1); assertThat(revision(id)).isEqualTo(2);
        assertThat(adminDb.queryForObject("select stdout from executions where id=?", String.class, id)).isEqualTo("hello");
        assertThat(probe("readiness")).isEqualTo(200);
    }
    @ParameterizedTest @ValueSource(strings = {"SUCCEEDED", "FAILED", "TIMED_OUT", "RUNNING"})
    void duplicatesOfNonQueuedRowsDoNotRun(String status) {
        start(); UUID id = row(status); send(id);
        // A sentinel proves the previous message was handled, not merely prefetched.
        UUID sentinel = row("QUEUED"); send(sentinel); terminal(sentinel, "SUCCEEDED");
        assertThat(status(id)).isEqualTo(status); assertThat(fake.calls.get()).isEqualTo(1);
    }
    @Test void invalidAndMissingJobsAreRejectedWithoutRequeue() {
        start(); sendBody("{}"); sendBody(" ".repeat(1025)); send(UUID.randomUUID());
        UUID valid = row("QUEUED"); send(valid); terminal(valid, "SUCCEEDED"); drained();
        assertThat(fake.calls.get()).isEqualTo(1);
    }
    @Test void concurrentClaimsAndLateResultsCannotOverwriteTerminalState() throws Exception {
        start(); UUID id = row("QUEUED");
        try (var threads = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<Optional<ExecutionRepository.Job>>>();
            for (int i = 0; i < 8; i++) futures.add(threads.submit(() -> repository().claim(id, 5000)));
            var winners = new ArrayList<ExecutionRepository.Job>();
            for (var result : futures) result.get(10, TimeUnit.SECONDS).ifPresent(winners::add);
            assertThat(winners).hasSize(1);
            assertThat(repository().complete(id, 1, ExecutionResult.interrupted())).isEqualTo(1);
            assertThat(repository().complete(id, 1, SUCCESS)).isZero(); assertThat(status(id)).isEqualTo("FAILED");
        }
    }
    @Test void deadlineStopsRunnerBeforeTerminalTimeout() {
        fake.block = true; start(200); UUID id = row("QUEUED"); send(id); terminal(id, "TIMED_OUT");
        assertThat(fake.stops.get()).isEqualTo(1); assertThat(fake.calls.get()).isEqualTo(1);
    }
    @Test void cleanupFailurePausesConsumptionAndKeepsRunningRow() throws Exception {
        fake.block = true; fake.failCleanup = true; start(200); UUID id = row("QUEUED"); send(id);
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(probe("readiness")).isEqualTo(503));
        assertThat(status(id)).isEqualTo("RUNNING");
        assertThat(probe("liveness")).isEqualTo(200);
    }
    @Test void startupCleanupFailureDoesNotConsumeNewWorkOrFalselyCompleteInterruptedWork() throws Exception {
        UUID interrupted = row("RUNNING"), queued = row("QUEUED");
        fake.failCleanup = true; start(); send(queued);
        assertThat(probe("readiness")).isEqualTo(503); assertThat(probe("liveness")).isEqualTo(200);
        assertThat(status(interrupted)).isEqualTo("RUNNING"); assertThat(status(queued)).isEqualTo("QUEUED");
        assertThat(fake.calls.get()).isZero(); assertThat(fake.stops.get()).isEqualTo(1);
        assertThat(admin.getQueueInfo(ExecutionConsumer.QUEUE).getConsumerCount()).isZero();
    }
    @Test void databaseLossBeforeClaimPausesWithoutRunningAndDoesNotAutoResume() throws Exception {
        start(); UUID id = row("QUEUED");
        PG.getDockerClient().pauseContainerCmd(PG.getContainerId()).exec();
        try {
            send(id);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(app.getBean(ExecutionConsumer.class).health().getStatus().getCode()).isEqualTo("DOWN"));
        } finally { PG.getDockerClient().unpauseContainerCmd(PG.getContainerId()).exec(); }
        assertThat(fake.calls.get()).isZero(); assertThat(status(id)).isEqualTo("QUEUED");
        assertThat(probe("readiness")).isEqualTo(503); assertThat(probe("liveness")).isEqualTo(200);
        app.close(); fake = new FakeRunner(); start(); terminal(id, "SUCCEEDED"); assertThat(fake.calls.get()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void lostResultPersistenceNeverAcknowledgesOrRerunsAndRestartRecovers(boolean unavailableDatabase) throws Exception {
        start(); UUID id = row("QUEUED"); fake.pauseDatabase = unavailableDatabase; fake.denyResultWrite = !unavailableDatabase;
        try {
            send(id);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(app.getBean(ExecutionConsumer.class).health().getStatus().getCode()).isEqualTo("DOWN"));
        } finally {
            if (unavailableDatabase) PG.getDockerClient().unpauseContainerCmd(PG.getContainerId()).exec();
            else assertThat(PG.execInContainer("psql", "-U", PG.getUsername(), "-d", PG.getDatabaseName(), "-f", "/tmp/provision-worker.sql").getExitCode()).isZero();
        }
        assertThat(fake.calls.get()).isEqualTo(1);
        app.close();
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(admin.getQueueInfo(ExecutionConsumer.QUEUE).getMessageCount()).isEqualTo(1));
        // A socket timeout does not prove rollback: buffered writes can commit when PostgreSQL resumes.
        String saved = status(id);
        if (unavailableDatabase) assertThat(saved).isIn("RUNNING", "SUCCEEDED");
        else assertThat(saved).isEqualTo("RUNNING");
        fake = new FakeRunner(); start();
        await().atMost(Duration.ofSeconds(12)).untilAsserted(() -> assertThat(status(id)).isIn("SUCCEEDED", "FAILED"));
        drained(); assertThat(fake.calls.get()).isZero(); assertThat(revision(id)).isEqualTo(2);
        if (!unavailableDatabase) assertThat(status(id)).isEqualTo("FAILED");
        if (saved.equals("SUCCEEDED")) assertThat(status(id)).isEqualTo("SUCCEEDED");
        if (status(id).equals("FAILED")) {
            assertThat(fake.stops.get()).isEqualTo(1);
            assertThat(adminDb.queryForObject("select failure_reason from executions where id=?", String.class, id)).isEqualTo("INFRASTRUCTURE_INTERRUPTION");
        } else assertThat(adminDb.queryForObject("select stdout from executions where id=?", String.class, id)).isEqualTo("hello");
    }
    @Test void brokerLossLatchesReadinessDownUntilOperatorRestart() throws Exception {
        start(); assertThat(probe("readiness")).isEqualTo(200);
        try { assertThat(RABBIT.execInContainer("rabbitmqctl", "stop_app").getExitCode()).isZero(); }
        finally { assertThat(RABBIT.execInContainer("rabbitmqctl", "start_app").getExitCode()).isZero(); }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(probe("readiness")).isEqualTo(503));
        UUID id = row("QUEUED"); send(id);
        assertThat(probe("liveness")).isEqualTo(200);
        app.close(); fake = new FakeRunner(); start(); terminal(id, "SUCCEEDED");
    }
    @Test void restrictedWorkerRoleCannotMutateSnapshotsOrReadAccounts() {
        start(); var jdbc = app.getBean(JdbcTemplate.class); UUID id = row("QUEUED");
        for (String sql : List.of("select * from users", "select * from room_members", "delete from executions", "insert into executions select * from executions",
                "update executions set source_code='changed'", "update executions set language='JAVA'", "update executions set room_id=room_id",
                "create table forbidden(id int)")) assertThatThrownBy(() -> jdbc.execute(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(repository().claim(id, 5000)).isPresent();
        assertThat(repository().complete(id, 1, SUCCESS)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void killedWorkerProcessRedeliversWithoutRerunningStartedOrCompletedJob(boolean afterCommit) throws Exception {
        UUID id = row("QUEUED"); Path log = temporary.resolve("child.log");
        var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), TestWorkerProcess.class.getName());
        var env = command.environment();
        env.put("TEST_KILL_AFTER_COMMIT", Boolean.toString(afterCommit));
        env.put("DATABASE_URL", PG.getJdbcUrl()); env.put("DATABASE_USER", WORKER); env.put("DATABASE_PASSWORD", PASSWORD);
        env.put("RABBITMQ_HOST", RABBIT.getHost()); env.put("RABBITMQ_PORT", RABBIT.getAmqpPort().toString());
        env.put("RABBITMQ_USER", RABBIT.getAdminUsername()); env.put("RABBITMQ_PASSWORD", RABBIT.getAdminPassword());
        Process child = command.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            send(id);
            String marker = afterCommit ? "TEST_RESULT_COMMITTED" : "TEST_RUNNER_ENTERED";
            await().atMost(Duration.ofSeconds(30)).until(() -> Files.exists(log) && Files.readString(log).contains(marker));
            assertThat(status(id)).isEqualTo(afterCommit ? "SUCCEEDED" : "RUNNING");
            child.destroyForcibly(); assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
        } finally { if (child.isAlive()) { child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS); } }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(admin.getQueueInfo(ExecutionConsumer.QUEUE).getMessageCount()).isEqualTo(1));
        start(); terminal(id, afterCommit ? "SUCCEEDED" : "FAILED"); drained(); assertThat(fake.calls.get()).isZero();
        assertThat(revision(id)).isEqualTo(2);
        if (afterCommit) assertThat(adminDb.queryForObject("select stdout from executions where id=?", String.class, id)).isEqualTo("committed");
        UUID sentinel = row("QUEUED"); send(sentinel); terminal(sentinel, "SUCCEEDED");
        assertThat(fake.calls.get()).isEqualTo(1); assertThat(fake.snapshot.id()).isEqualTo(sentinel);
    }
}
