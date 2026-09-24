package com.pairforge.api.execution;

import com.fasterxml.jackson.databind.*;
import com.pairforge.api.auth.TokenService;
import com.pairforge.api.common.Language;
import com.pairforge.api.room.*;
import com.pairforge.api.user.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.junit.jupiter.Container;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class ExecutionIT {
    @Test void committedSubmissionAndDispatchFailureHaveSafeMetricsAndStructuredLogs(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        var owner = actor(); var room = room(owner);
        String secretSource = "print('private-source-" + UUID.randomUUID() + "')";
        var submitted = metrics.counter("pairforge.execution.submitted", "language", "PYTHON");
        var failed = metrics.counter("pairforge.execution.completed", "status", "FAILED", "reason", "DISPATCH_FAILED");
        double beforeSubmitted = submitted.count(), beforeFailed = failed.count();
        var before = jdbc.queryForObject("select clock_timestamp()", java.sql.Timestamp.class).toInstant();
        doReturn(FailureReason.DISPATCH_FAILED).when(publisher).publish(any());
        var response = request("POST", path(room), source(secretSource), owner.token());
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(submitted.count()).isEqualTo(beforeSubmitted + 1);
        assertThat(failed.count()).isEqualTo(beforeFailed + 1);
        var row = executions.findByRoomIdOrderByCreatedAtDescIdDesc(room, org.springframework.data.domain.PageRequest.of(0, 1)).getContent().getFirst();
        var after = jdbc.queryForObject("select clock_timestamp()", java.sql.Timestamp.class).toInstant();
        assertThat(row.getCreatedAt()).isBetween(before, after);
        String line = output.getAll().lines().filter(value -> value.contains("Execution committed") && value.contains(row.getId().toString())).findFirst().orElseThrow();
        var event = json.readTree(line);
        assertThat(event.path("executionId").asText()).isEqualTo(row.getId().toString());
        assertThat(event.path("requestId").asText()).isEqualTo(response.headers().firstValue("X-Request-ID").orElseThrow());
        assertThat(output.getAll()).doesNotContain(secretSource, owner.token(), KEY, PG.getPassword(), RABBIT.getAdminPassword());
    }
    @Test void invitationAndPasswordBodiesStayOutOfStructuredLogs(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        var owner = actor(); var member = actor();
        var created = rooms.create(owner.id(), new RoomDtos.CreateRequest("Private", Language.PYTHON));
        String invitation = created.invitationToken();
        var joined = request("POST", "/api/rooms/" + created.room().id() + "/join",
                json.writeValueAsString(Map.of("invitationToken", invitation)), member.token());
        assertThat(joined.statusCode()).isEqualTo(200);
        String password = "never-log-password-" + UUID.randomUUID();
        // Invalid email avoids BCrypt work while still exercising the real JSON boundary.
        assertThat(request("POST", "/api/auth/login", json.writeValueAsString(Map.of("email", "invalid", "password", password)), null).statusCode()).isEqualTo(400);
        assertThat(output.getAll()).doesNotContain(invitation, password, member.token());
    }
    @Test void dispatchFailurePreservesDatabaseTimestampOrdering() throws Exception {
        var owner = actor(); var room = room(owner);
        doAnswer(call -> {
            UUID id = call.getArgument(0);
            jdbc.update("update executions set created_at=clock_timestamp()+interval '10 seconds' where id=?", id);
            return FailureReason.DISPATCH_FAILED;
        }).when(publisher).publish(any());
        assertThat(request("POST", path(room), source("print(1)"), owner.token()).statusCode()).isEqualTo(503);
        var row = executions.findByRoomIdOrderByCreatedAtDescIdDesc(room, org.springframework.data.domain.PageRequest.of(0, 1)).getContent().getFirst();
        assertThat(row.getCompletedAt()).isAfterOrEqualTo(row.getCreatedAt());
    }
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17.11-bookworm").withPassword(UUID.randomUUID().toString());
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.11-bookworm").withExposedPorts(6379);
    @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1.8")
            .withAdminUser("test").withAdminPassword(UUID.randomUUID().toString());
    static final String KEY = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl); r.add("spring.datasource.username", PG::getUsername); r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost); r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("spring.rabbitmq.host", RABBIT::getHost); r.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        r.add("spring.rabbitmq.username", RABBIT::getAdminUsername); r.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        r.add("pairforge.auth.key-hex", () -> KEY);
        r.add("pairforge.execution.user-limit", () -> 3); r.add("pairforge.execution.window-seconds", () -> 60);
        r.add("pairforge.execution.max-outstanding", () -> 3); r.add("pairforge.execution.admission-wait-ms", () -> 2000);
        r.add("pairforge.execution.confirm-timeout-ms", () -> 300); r.add("pairforge.execution.retry-backoff-ms", () -> 5);
    }
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired RoomService rooms;
    @Autowired RoomMemberRepository members;
    @Autowired TokenService tokens;
    @Autowired ExecutionRepository executions;
    @Autowired ExecutionService service;
    @Autowired ExecutionAdmission admission;
    @Autowired ObjectMapper json;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired io.micrometer.core.instrument.MeterRegistry metrics;
    @MockitoSpyBean ExecutionJobPublisher publisher;
    @MockitoSpyBean ExecutionEventPublisher eventPublisher;
    @MockitoSpyBean org.springframework.messaging.simp.SimpMessagingTemplate sockets;
    @Autowired org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer executionEventListener;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    record Actor(UUID id, String token) {}
    Actor actor() {
        var user = users.saveAndFlush(new User(UUID.randomUUID() + "@example.test", "unused-test-hash"));
        return new Actor(user.getId(), tokens.issue(user.getId()).accessToken());
    }
    UUID room(Actor owner) { return rooms.create(owner.id(), new RoomDtos.CreateRequest("Execution", Language.JAVA)).room().id(); }
    String path(UUID room) { return "/api/rooms/" + room + "/executions"; }
    String source(String text) throws Exception { return json.writeValueAsString(Map.of("source", text, "language", "PYTHON")); }
    HttpResponse<String> request(String method, String path, String body, String token) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode body(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(response.body()).doesNotContain(KEY, "password_hash", "stackTrace");
        return json.readTree(response.body());
    }
    @BeforeEach void clean() { executions.deleteAll(); admin.purgeQueue(ExecutionMessaging.JOBS); }

    @Test void queuedEventIsCommittedAndRealRabbitConsumerBroadcastsIt() throws Exception {
        var owner=actor(); var room=room(owner);
        doAnswer(call -> {
            ExecutionEvent event=call.getArgument(0);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("select status from executions where id=?",String.class,event.executionId())).isEqualTo(event.status());
            return call.callRealMethod();
        }).when(eventPublisher).publish(any());
        var receipt=body(request("POST",path(room),source("print(1)"),owner.token()),202);
        UUID id=UUID.fromString(receipt.get("executionId").asText());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(sockets).convertAndSend(
                "/topic/rooms/"+room+"/executions",new ExecutionEvent(1,id,room,"QUEUED",0)));
    }
    @Test void databaseLossDiscardsNotificationAfterBoundedAttemptsButRestRecoversResult() throws Exception {
        var owner = actor(); var room = room(owner);
        var receipt = body(request("POST", path(room), source("print(1)"), owner.token()), 202);
        UUID id = UUID.fromString(receipt.get("executionId").asText());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(sockets).convertAndSend(
                "/topic/rooms/" + room + "/executions", new ExecutionEvent(1, id, room, "QUEUED", 0)));
        jdbc.update("update executions set status='SUCCEEDED',state_revision=2,stdout='durable result',completed_at=now() where id=?", id);
        var failures = metrics.counter("pairforge.execution.events.consume.failures", "reason", "unavailable");
        double before = failures.count();
        PG.getDockerClient().pauseContainerCmd(PG.getContainerId()).exec();
        try {
            assertThat(eventPublisher.publish(new ExecutionEvent(1, id, room, "SUCCEEDED", 2))).isTrue();
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(failures.count()).isEqualTo(before + 1));
        } finally { PG.getDockerClient().unpauseContainerCmd(PG.getContainerId()).exec(); }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(body(request("GET", "/api/executions/" + id, null, owner.token()), 200).get("stdout").asText()).isEqualTo("durable result"));
        verify(sockets, never()).convertAndSend("/topic/rooms/" + room + "/executions", new ExecutionEvent(1, id, room, "SUCCEEDED", 2));
        verify(publisher, times(1)).publish(id);
        assertThat(executions.count()).isEqualTo(1);
        assertThat(admin.getQueueInfo(ExecutionMessaging.EVENTS).getMessageCount()).isZero();
    }
    @Test void dependencyRecoveryCannotBypassDurableOutstandingCapacity() throws Exception {
        UUID first = null;
        for (int i = 0; i < 3; i++) {
            var owner = actor();
            var receipt = body(request("POST", path(room(owner)), source("pass"), owner.token()), 202);
            if (first == null) first = UUID.fromString(receipt.get("executionId").asText());
        }
        var next = actor(); var room = room(next);
        boolean redisPaused = false;
        try {
            assertThat(RABBIT.execInContainer("rabbitmqctl", "stop_app").getExitCode()).isZero();
            REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec(); redisPaused = true;
            assertThat(body(request("POST", path(room), source("pass"), next.token()), 503).get("code").asText()).isEqualTo("DEPENDENCY_UNAVAILABLE");
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec(); redisPaused = false;
            assertThat(body(request("POST", path(room), source("pass"), next.token()), 503).get("code").asText()).isEqualTo("EXECUTION_CAPACITY");
            assertThat(executions.count()).isEqualTo(3);
            verify(publisher, times(3)).publish(any());
        } finally {
            if (redisPaused) REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
            assertThat(RABBIT.execInContainer("rabbitmqctl", "start_app").getExitCode()).isZero();
        }
        // The documented operator procedure frees capacity only through a conditional terminal write.
        assertThat(jdbc.update("update executions set status='FAILED',failure_reason='DISPATCH_FAILED',completed_at=now(),state_revision=state_revision+1 where id=? and status='QUEUED'", first)).isEqualTo(1);
        body(request("POST", path(room), source("pass"), next.token()), 202);
        assertThat(executions.countByStatusIn(List.of(ExecutionStatus.QUEUED, ExecutionStatus.RUNNING))).isEqualTo(3);
        assertThat(executions.count()).isEqualTo(4);
    }
    @Test void eventRouteFailureDoesNotFailDispatchOrLoseSavedResults() throws Exception {
        var binding=new Binding(ExecutionMessaging.EVENTS,Binding.DestinationType.QUEUE,ExecutionMessaging.EXCHANGE,ExecutionMessaging.EVENTS,Map.of());
        admin.removeBinding(binding);
        try {
            var owner=actor(); var room=room(owner);
            var receipt=body(request("POST",path(room),source("print(1)"),owner.token()),202);
            UUID id=UUID.fromString(receipt.get("executionId").asText());
            assertThat(rabbit.receive(ExecutionMessaging.JOBS,2000)).isNotNull();
            jdbc.update("update executions set status='SUCCEEDED',state_revision=2,stdout='saved',completed_at=now() where id=?",id);
            assertThat(eventPublisher.publish(new ExecutionEvent(1,id,room,"SUCCEEDED",2))).isFalse();
            assertThat(body(request("GET","/api/executions/"+id,null,owner.token()),200).get("stdout").asText()).isEqualTo("saved");
        } finally { admin.declareBinding(binding); }
    }
    @Test void reconnectingApiConsumerHandlesBacklogWithoutRegressingCommittedState() throws Exception {
        var owner=actor(); var room=room(owner);
        executionEventListener.stop();
        UUID id;
        try {
            var receipt=body(request("POST",path(room),source("print(1)"),owner.token()),202);
            id=UUID.fromString(receipt.get("executionId").asText());
            jdbc.update("update executions set status='SUCCEEDED',state_revision=2,stdout='saved',completed_at=now() where id=?",id);
            assertThat(eventPublisher.publish(new ExecutionEvent(1,id,room,"SUCCEEDED",2))).isTrue();
            assertThat(eventPublisher.publish(new ExecutionEvent(1,id,room,"RUNNING",1))).isTrue();
        } finally { executionEventListener.start(); }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> verify(sockets).convertAndSend(
                "/topic/rooms/"+room+"/executions",new ExecutionEvent(1,id,room,"SUCCEEDED",2)));
        verify(sockets,never()).convertAndSend("/topic/rooms/"+room+"/executions",new ExecutionEvent(1,id,room,"QUEUED",0));
    }

    @Test void commitsVisibleSnapshotBeforeConfirmedPersistentIdOnlyPublication() throws Exception {
        var user = actor(); var room = room(user);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("select source_code from executions where id=?", String.class, (UUID) call.getArgument(0))).isEqualTo("client snapshot");
            return call.callRealMethod();
        }).when(publisher).publish(any(UUID.class));
        var response = request("POST", path(room), source("client snapshot"), user.token());
        var receipt = body(response, 202); var id = receipt.get("executionId").asText();
        assertThat(receipt.get("status").asText()).isEqualTo("QUEUED");
        assertThat(receipt.get("stateRevision").asLong()).isZero();
        assertThat(response.headers().firstValue("Location")).contains("/api/executions/" + id);
        var message = rabbit.receive(ExecutionMessaging.JOBS, 2000);
        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        var job = json.readTree(message.getBody()); assertThat(job.size()).isEqualTo(2);
        assertThat(job.get("schemaVersion").asInt()).isEqualTo(1); assertThat(job.get("executionId").asText()).isEqualTo(id);
        assertThat(executions.count()).isEqualTo(1);
        var detail = body(request("GET", "/api/executions/" + id, null, user.token()), 200);
        assertThat(detail.get("source").asText()).isEqualTo("client snapshot");
        assertThat(detail.get("language").asText()).isEqualTo("PYTHON");
    }
    @Test void authorizedHistoryIsBoundedOrderedAndDoesNotExposeSourceOrOutput() throws Exception {
        var owner = actor(); var room = room(owner); var member = actor(); var stranger = actor();
        members.saveAndFlush(new RoomMember(room, member.id()));
        var first = body(request("POST", path(room), source("first"), owner.token()), 202).get("executionId").asText();
        var second = body(request("POST", path(room), source("second"), owner.token()), 202).get("executionId").asText();
        var page = body(request("GET", path(room) + "?size=1", null, member.token()), 200);
        assertThat(page.get("hasNext").asBoolean()).isTrue();
        assertThat(page.at("/items/0/id").asText()).isEqualTo(second);
        assertThat(page.at("/items/0").has("source")).isFalse(); assertThat(page.at("/items/0").has("stdout")).isFalse();
        assertThat(body(request("GET", path(room) + "?size=1&page=1", null, owner.token()), 200).at("/items/0/id").asText()).isEqualTo(first);
        body(request("GET", path(room), null, stranger.token()), 404);
        var denied = body(request("GET", "/api/executions/" + first, null, stranger.token()), 404);
        var missing = body(request("GET", "/api/executions/" + UUID.randomUUID(), null, stranger.token()), 404);
        assertThat(denied.get("code")).isEqualTo(missing.get("code")); assertThat(denied.get("message")).isEqualTo(missing.get("message"));
        for (String query : List.of("?size=0", "?page=-1", "?size=101", "?page=2147483647&size=100", "?page=no"))
            body(request("GET", path(room) + query, null, owner.token()), 400);
        body(request("POST", path(room), source("forbidden"), stranger.token()), 404);
        body(request("POST", path(room), source("unauthenticated"), null), 401);
        body(request("GET", "/api/executions/invalid", null, owner.token()), 400);
        assertThat(executions.count()).isEqualTo(2);
    }
    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{\"source\":5,\"language\":\"JAVA\"}", "{\"source\":\"a\",\"language\":0}",
            "{\"source\":\"a\",\"language\":\"RUBY\"}", "{\"source\":\"a\",\"language\":\"JAVA\",\"userId\":\"forged\"}",
            "{\"source\":\"\\u0000\",\"language\":\"JAVA\"}", "{\"source\":\"\\ud800\",\"language\":\"JAVA\"}"})
    void invalidContractDoesNotPersistOrPublish(String input) throws Exception {
        var owner = actor(); body(request("POST", path(room(owner)), input, owner.token()), 400);
        assertThat(executions.count()).isZero(); verify(publisher, never()).publish(any());
    }
    @Test void utf8SourceLimitAndWireLimitAreIndependentIncludingChunkedRequests() throws Exception {
        var owner = actor(); var room = room(owner);
        body(request("POST", path(room), source("😀".repeat(16384)), owner.token()), 202);
        body(request("POST", path(room), source("😀".repeat(16385)), owner.token()), 413);
        String escaped = "{\"source\":\"" + "\\u0041".repeat(65536) + "\",\"language\":\"JAVA\"}";
        body(request("POST", path(room), escaped, owner.token()), 202);
        String tooLarge = " ".repeat(400001);
        body(request("POST", path(room), tooLarge, owner.token()), 413);
        var chunked = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path(room)))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + owner.token()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(tooLarge.getBytes(java.nio.charset.StandardCharsets.UTF_8)))).build();
        body(http.send(chunked, HttpResponse.BodyHandlers.ofString()), 413);
        assertThat(executions.count()).isEqualTo(2);
    }
    @Test void perUserLimitSurvivesNewRequestsAndExpiresWithoutBeingExtendedByRejections() throws Exception {
        var owner = actor(); var room = room(owner);
        for (int i = 0; i < 3; i++) { body(request("POST", path(room), source("pass"), owner.token()), 202); executions.deleteAll(); }
        long ttlBeforeRejection = redis.getExpire("execution:submission:" + owner.id(), TimeUnit.MILLISECONDS);
        var denied = request("POST", path(room), source("pass"), owner.token());
        assertThat(body(denied, 429).get("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(ttlBeforeRejection).isBetween(1L, 60000L);
        assertThat(Long.parseLong(denied.headers().firstValue("Retry-After").orElseThrow())).isBetween(1L, 60L);
        assertThat(redis.getExpire("execution:submission:" + owner.id(), TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(ttlBeforeRejection);
        // Exercise real expiry without making three HTTP requests race a two-second window on CI.
        assertThat(redis.expire("execution:submission:" + owner.id(), Duration.ofMillis(300))).isTrue();
        await().atMost(Duration.ofSeconds(3)).until(() -> Boolean.FALSE.equals(redis.hasKey("execution:submission:" + owner.id())));
        body(request("POST", path(room), source("pass"), owner.token()), 202);
    }
    @Test void perUserAdmissionIsAtomicUnderConcurrentAttempts() throws Exception {
        UUID user = UUID.randomUUID();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> {
                start.await();
                try { admission.check(user); return 202; }
                catch (com.pairforge.api.common.ApiException error) { return error.status(); }
            }));
            start.countDown();
            var statuses = new ArrayList<Integer>();
            for (var future : futures) statuses.add(future.get(10, TimeUnit.SECONDS));
            assertThat(statuses).containsOnly(202, 429);
            assertThat(statuses.stream().filter(s -> s == 202).count()).isEqualTo(3);
            assertThat(redis.opsForValue().get("execution:submission:" + user)).isEqualTo("3");
        }
    }
    @Test void confirmedPersistentJobSurvivesBrokerRestart() throws Exception {
        var owner = actor();
        String id = body(request("POST", path(room(owner)), source("pass"), owner.token()), 202).get("executionId").asText();
        try {
            assertThat(RABBIT.execInContainer("rabbitmqctl", "stop_app").getExitCode()).isZero();
        } finally {
            assertThat(RABBIT.execInContainer("rabbitmqctl", "start_app").getExitCode()).isZero();
        }
        await().atMost(Duration.ofSeconds(15)).ignoreExceptions().untilAsserted(() ->
                assertThat(admin.getQueueInfo(ExecutionMessaging.JOBS).getMessageCount()).isEqualTo(1));
        var message = rabbit.receive(ExecutionMessaging.JOBS, 2000);
        assertThat(message).isNotNull();
        assertThat(json.readTree(message.getBody()).get("executionId").asText()).isEqualTo(id);
        assertThat(executions.findById(UUID.fromString(id)).orElseThrow().getStatus()).isEqualTo(ExecutionStatus.QUEUED);
    }
    @Test void concurrentGlobalAdmissionNeverExceedsDurableOutstandingLimit() throws Exception {
        var owner = actor(); var room = room(owner); var actors = new ArrayList<Actor>();
        for (int i = 0; i < 8; i++) { var a = actor(); members.saveAndFlush(new RoomMember(room, a.id())); actors.add(a); }
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<Integer>>();
            for (var a : actors) futures.add(pool.submit(() -> request("POST", path(room), source("pass"), a.token()).statusCode()));
            var statuses = new ArrayList<Integer>(); for (var future : futures) statuses.add(future.get(15, TimeUnit.SECONDS));
            assertThat(statuses).containsOnly(202, 503); assertThat(statuses.stream().filter(s -> s == 202).count()).isEqualTo(3);
            assertThat(executions.count()).isEqualTo(3);
        }
        var first = executions.findAll().getFirst();
        jdbc.update("update executions set status='RUNNING' where id=?", first.getId());
        body(request("POST", path(room), source("still full"), owner.token()), 503);
        jdbc.update("update executions set status='FAILED', failure_reason='DISPATCH_FAILED', state_revision=1 where id=?", first.getId());
        body(request("POST", path(room), source("capacity released"), owner.token()), 202);
    }
    @Test void unroutablePublicationFailsOnlyQueuedRowAndHistoryRecoversIt() throws Exception {
        var binding = new Binding(ExecutionMessaging.JOBS, Binding.DestinationType.QUEUE, ExecutionMessaging.EXCHANGE, ExecutionMessaging.JOBS, Map.of());
        admin.removeBinding(binding);
        try {
            var owner = actor(); var response = request("POST", path(room(owner)), source("pass"), owner.token());
            var result = body(response, 503);
            assertThat(result.get("failureReason").asText()).isEqualTo("DISPATCH_FAILED");
            assertThat(result.get("outcomeUnknown").asBoolean()).isFalse();
            var detail = body(request("GET", response.headers().firstValue("Location").orElseThrow(), null, owner.token()), 200);
            assertThat(detail.get("status").asText()).isEqualTo("FAILED"); assertThat(detail.get("stateRevision").asLong()).isEqualTo(1);
            assertThat(detail.get("completedAt").isNull()).isFalse();
        } finally { admin.declareBinding(binding); }
    }
    @Test void redisOutageFailsClosedBeforePersistence() throws Exception {
        var owner = actor(); var room = room(owner);
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try { body(request("POST", path(room), source("pass"), owner.token()), 503); }
        finally { REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec(); }
        assertThat(executions.count()).isZero(); verify(publisher, never()).publish(any());
    }
    @Test void brokerNegativeConfirmPersistsDefiniteDispatchFailure() throws Exception {
        var binding = new Binding(ExecutionMessaging.JOBS, Binding.DestinationType.QUEUE, ExecutionMessaging.EXCHANGE, ExecutionMessaging.JOBS, Map.of());
        admin.deleteQueue(ExecutionMessaging.JOBS);
        try {
            admin.declareQueue(new org.springframework.amqp.core.Queue(ExecutionMessaging.JOBS, true, false, false,
                    Map.of("x-max-length", 1, "x-overflow", "reject-publish")));
            admin.declareBinding(binding);
            assertThat(publisher.publish(UUID.randomUUID())).isNull();
            var owner = actor();
            var result = body(request("POST", path(room(owner)), source("pass"), owner.token()), 503);
            assertThat(result.get("failureReason").asText()).isEqualTo("DISPATCH_FAILED");
            assertThat(result.get("status").asText()).isEqualTo("FAILED");
            assertThat(executions.count()).isEqualTo(1);
        } finally {
            admin.deleteQueue(ExecutionMessaging.JOBS);
            admin.declareQueue(new org.springframework.amqp.core.Queue(ExecutionMessaging.JOBS, true));
            admin.declareBinding(binding);
        }
    }
    @Test void databaseOutageBeforeAdmissionNeverPublishes() throws Exception {
        var owner = actor(); var room = room(owner);
        PG.getDockerClient().pauseContainerCmd(PG.getContainerId()).exec();
        try { body(request("POST", path(room), source("pass"), owner.token()), 503); }
        finally { PG.getDockerClient().unpauseContainerCmd(PG.getContainerId()).exec(); }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(executions.count()).isZero());
        verify(publisher, never()).publish(any());
    }
    @Test void statusReadOutageAfterConfirmedPublicationReturnsRecoverableUnknownOutcome() throws Exception {
        var owner = actor(); var room = room(owner);
        doAnswer(call -> {
            assertThat(call.callRealMethod()).isNull();
            PG.getDockerClient().pauseContainerCmd(PG.getContainerId()).exec();
            return null;
        }).when(publisher).publish(any(UUID.class));
        String id;
        try {
            var response = body(request("POST", path(room), source("pass"), owner.token()), 503);
            id = response.get("executionId").asText();
            assertThat(response.get("outcomeUnknown").asBoolean()).isTrue();
            assertThat(response.get("status").isNull()).isTrue();
        } finally { PG.getDockerClient().unpauseContainerCmd(PG.getContainerId()).exec(); }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(body(request("GET", "/api/executions/" + id, null, owner.token()), 200).get("status").asText()).isEqualTo("QUEUED"));
        assertThat(json.readTree(rabbit.receive(ExecutionMessaging.JOBS, 2000).getBody()).get("executionId").asText()).isEqualTo(id);
        verify(publisher, times(1)).publish(any());
    }
    @Test void brokerOutageHasBoundedUnconfirmedOutcomeAndDurableFailure() throws Exception {
        var owner = actor(); var room = room(owner);
        RABBIT.getDockerClient().pauseContainerCmd(RABBIT.getContainerId()).exec();
        try {
            var response = body(request("POST", path(room), source("pass"), owner.token()), 503);
            assertThat(response.get("failureReason").asText()).isEqualTo("DISPATCH_UNCONFIRMED");
            assertThat(response.get("status").asText()).isEqualTo("FAILED");
        } finally { RABBIT.getDockerClient().unpauseContainerCmd(RABBIT.getContainerId()).exec(); }
    }
    @ParameterizedTest @ValueSource(strings = {"RUNNING", "SUCCEEDED", "FAILED", "TIMED_OUT"})
    void dispatchFailureCannotOverwriteAClaimOrTerminalResult(String status) throws Exception {
        var owner = actor(); var room = room(owner);
        doAnswer(call -> {
            jdbc.update("update executions set status=?, state_revision=7 where id=?", status, (UUID) call.getArgument(0));
            return FailureReason.DISPATCH_UNCONFIRMED;
        }).when(publisher).publish(any(UUID.class));
        var result = body(request("POST", path(room), source("pass"), owner.token()), 202);
        assertThat(result.get("status").asText()).isEqualTo(status); assertThat(result.get("stateRevision").asLong()).isEqualTo(7);
    }
    @Test void failurePersistenceOutageReturnsIdAndUnknownOutcomeWithoutRetryingSubmission() throws Exception {
        var owner = actor(); var room = room(owner);
        doAnswer(call -> {
            PG.getDockerClient().pauseContainerCmd(PG.getContainerId()).exec();
            return FailureReason.DISPATCH_FAILED;
        }).when(publisher).publish(any(UUID.class));
        try {
            var response = body(request("POST", path(room), source("pass"), owner.token()), 503);
            assertThat(response.get("outcomeUnknown").asBoolean()).isTrue();
            assertThat(response.get("status").isNull()).isTrue(); assertThat(response.get("executionId").asText()).isNotBlank();
        } finally { PG.getDockerClient().unpauseContainerCmd(PG.getContainerId()).exec(); }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(executions.count()).isEqualTo(1));
        verify(publisher, times(1)).publish(any());
    }
    @Test void interruptionAfterCommitLeavesDocumentedQueuedGapAndManualConditionalFailureIsSafe() throws Exception {
        var owner = actor(); var room = room(owner);
        doAnswer(call -> { throw new AssertionError("Injected process interruption after commit"); }).when(publisher).publish(any(UUID.class));
        assertThatThrownBy(() -> service.submit(owner.id(), room, json.readTree(source("pass")))).isInstanceOf(AssertionError.class);
        var row = executions.findAll().getFirst(); assertThat(row.getStatus()).isEqualTo(ExecutionStatus.QUEUED);
        assertThat(rabbit.receive(ExecutionMessaging.JOBS)).isNull();
        int affected = jdbc.update("update executions set status='FAILED', failure_reason='DISPATCH_FAILED', completed_at=greatest(clock_timestamp(), created_at), state_revision=state_revision+1 where id=? and status='QUEUED'", row.getId());
        assertThat(affected).isEqualTo(1);
        assertThat(jdbc.update("update executions set status='RUNNING' where id=? and status='QUEUED'", row.getId())).isZero();
    }
}
