package com.pairforge.api.collaboration;

import com.fasterxml.jackson.databind.*;
import com.pairforge.api.auth.TokenService;
import com.pairforge.api.common.Language;
import com.pairforge.api.room.*;
import com.pairforge.api.user.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.test.context.*;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.junit.jupiter.Container;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class CollaborationIT {
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
        r.add("pairforge.auth.key-hex", () -> KEY); r.add("pairforge.collaboration.ttl-seconds", () -> 3);
        r.add("pairforge.collaboration.connect-timeout-ms", () -> 1000);
        r.add("pairforge.collaboration.max-connections-per-user", () -> 3);
    }
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired RoomService rooms;
    @Autowired RoomMemberRepository members;
    @Autowired TokenService tokens;
    @Autowired JwtEncoder encoder;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redis;
    @Autowired DocumentRepository documents;
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired CollaborationSessions sessions;
    final List<Peer> peers = new ArrayList<>();
    record Actor(UUID id, String token) {}
    Actor actor() {
        var user = users.saveAndFlush(new User(UUID.randomUUID() + "@example.test", "unused-test-hash"));
        return new Actor(user.getId(), tokens.issue(user.getId()).accessToken());
    }
    UUID room(Actor owner) { return rooms.create(owner.id(), new RoomDtos.CreateRequest("Collaboration", Language.JAVA)).room().id(); }
    Peer open(String origin, String query) {
        var peer = new Peer();
        var builder = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5));
        if (origin != null) builder.header("Origin", origin);
        peer.socket = builder.buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws" + query), peer).join();
        peers.add(peer); return peer;
    }
    Peer connect(String token) {
        var peer = open("http://127.0.0.1:5173", "");
        peer.send("CONNECT", "accept-version:1.2\nheart-beat:0,0\nAuthorization:Bearer " + token, "");
        assertThat(peer.frame()).startsWith("CONNECTED"); return peer;
    }
    JsonNode join(Peer peer, UUID room) throws Exception {
        peer.send("SUBSCRIBE", "id:private\ndestination:/user/queue/collaboration", "");
        peer.send("SUBSCRIBE", "id:room\ndestination:/topic/rooms/" + room + "/document", "");
        peer.send("SEND", "destination:/app/rooms/" + room + "/snapshot\ncontent-type:application/json", "{}");
        return event(peer, "SNAPSHOT").get("document");
    }
    void update(Peer peer, UUID room, JsonNode document, long sequence, String content) throws Exception {
        sendUpdate(peer, room, Map.of("generationId", document.get("generationId").asText(), "sequence", sequence,
                "clientUpdateId", UUID.randomUUID(), "language", "PYTHON", "content", content));
    }
    void sendUpdate(Peer peer, UUID room, Object value) throws Exception {
        peer.send("SEND", "destination:/app/rooms/" + room + "/update\ncontent-type:application/json", json.writeValueAsString(value));
    }
    JsonNode event(Peer peer, String type) throws Exception {
        for (int i = 0; i < 20; i++) {
            var frame = peer.frame();
            if (!frame.startsWith("MESSAGE")) throw new AssertionError("Expected document message");
            var value = json.readTree(frame.substring(frame.indexOf("\n\n") + 2));
            if (value.get("type").asText().equals(type)) return value;
        }
        throw new AssertionError("Missing " + type);
    }
    @AfterEach void cleanup() { peers.forEach(peer -> peer.socket.abort()); }
    static class Peer implements WebSocket.Listener {
        WebSocket socket;
        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final StringBuilder buffer = new StringBuilder();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
            buffer.append(text);
            int end;
            while ((end = buffer.indexOf("\0")) >= 0) {
                frames.add(buffer.substring(0, end).stripLeading()); buffer.delete(0, end + 1);
            }
            socket.request(1); return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket socket, int code, String reason) { closed.complete(code); return null; }
        @Override public void onError(WebSocket socket, Throwable error) { closed.complete(-1); }
        void send(String command, String headers, String body) { socket.sendText(command + "\n" + headers + "\n\n" + body + '\0', true).join(); }
        String frame() {
            try { var value = frames.poll(8, TimeUnit.SECONDS); assertThat(value).as("STOMP frame received").isNotNull(); return value; }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        }
        void awaitClosed() throws Exception { assertThat(closed.get(8, TimeUnit.SECONDS)).isNotNull(); }
    }
    @Test void threeMembersShareCommittedContentLanguageAndIncreasingVersions() throws Exception {
        var owner = actor(); var other = actor(); var third = actor(); var room = room(owner);
        members.saveAndFlush(new RoomMember(room, other.id())); members.saveAndFlush(new RoomMember(room, third.id()));
        var a = connect(owner.token()); var b = connect(other.token()); var c = connect(third.token());
        var initial = join(a, room); assertThat(join(b, room)).isEqualTo(initial); assertThat(join(c, room)).isEqualTo(initial);
        update(a, room, initial, 1, "print('shared')");
        var accepted = event(b, "UPDATED").get("document");
        assertThat(accepted.get("content").asText()).isEqualTo("print('shared')");
        assertThat(accepted.get("language").asText()).isEqualTo("PYTHON"); assertThat(accepted.get("version").asLong()).isEqualTo(1);
        assertThat(event(c, "UPDATED").get("document")).isEqualTo(accepted);
        assertThat(documents.snapshot(room, Language.JAVA).document().content()).isEqualTo("print('shared')");
    }
    @Test void reconnectRestoresLatestCommittedDocumentAndRestartsConnectionSequence() throws Exception {
        var owner = actor(); var other = actor(); var room = room(owner);
        members.saveAndFlush(new RoomMember(room, other.id()));
        var old = connect(owner.token()); var initial = join(old, room);
        update(old, room, initial, 9, "before disconnect"); event(old, "UPDATED"); old.socket.abort();
        var writer = connect(other.token()); var current = join(writer, room);
        update(writer, room, current, 1, "latest server content");
        var latest = event(writer, "UPDATED").get("document");
        var recovered = connect(owner.token());
        assertThat(join(recovered, room)).isEqualTo(latest);
        assertThat(latest.get("generationId")).isEqualTo(initial.get("generationId"));
        assertThat(latest.get("language").asText()).isEqualTo("PYTHON");
        update(recovered, room, latest, 1, "deliberate new edit");
        assertThat(event(recovered, "UPDATED").get("document").get("version").asLong()).isEqualTo(3);
    }
    @Test void reconnectAfterExpirySeesGenerationInitializedByAnotherMemberAndRejectsOldDraft() throws Exception {
        var owner = actor(); var room = room(owner); var old = connect(owner.token()); var initial = join(old, room);
        old.socket.abort();
        redis.expire(DocumentRepository.key(room), Duration.ofMillis(100));
        await().atMost(Duration.ofSeconds(2)).until(() -> Boolean.FALSE.equals(redis.hasKey(DocumentRepository.key(room))));
        var initializer = connect(owner.token()); var fresh = join(initializer, room);
        var recovered = connect(owner.token()); var restored = join(recovered, room);
        assertThat(restored).isEqualTo(fresh);
        assertThat(restored.get("generationId")).isNotEqualTo(initial.get("generationId"));
        update(recovered, room, initial, 1, "stale offline draft");
        var reset = event(recovered, "DOCUMENT_RESET").get("document");
        assertThat(reset).isEqualTo(fresh);
        assertThat(documents.snapshot(room, Language.JAVA).document().version()).isZero();
    }
    @Test void simultaneousRecoveryOfMissingDocumentCreatesExactlyOneGeneration() throws Exception {
        var owner = actor(); var room = room(owner);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<Future<DocumentRepository.Result>>();
            for (int i = 0; i < 12; i++) futures.add(pool.submit(() -> documents.snapshot(room, Language.JAVA)));
            var generations = new HashSet<UUID>();
            for (var future : futures) {
                var document = future.get(5, TimeUnit.SECONDS).document();
                generations.add(document.generationId());
                assertThat(document.version()).isZero(); assertThat(document.content()).contains("public class Main");
            }
            assertThat(generations).hasSize(1);
        }
    }
    @Test void failedReconnectSnapshotCanBeRetriedAfterRedisRecovers() throws Exception {
        var owner = actor(); var room = room(owner); var old = connect(owner.token()); var initial = join(old, room);
        update(old, room, initial, 1, "retained during outage"); var latest = event(old, "UPDATED").get("document");
        old.socket.abort();
        // Keep this fixture alive across the command timeout; expiry is tested separately.
        redis.expire(DocumentRepository.key(room), Duration.ofSeconds(30));
        var failed = connect(owner.token());
        failed.send("SUBSCRIBE", "id:private\ndestination:/user/queue/collaboration", "");
        failed.send("SUBSCRIBE", "id:room\ndestination:/topic/rooms/" + room + "/document", "");
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            failed.send("SEND", "destination:/app/rooms/" + room + "/snapshot\ncontent-type:application/json", "{}");
            assertThat(event(failed, "ERROR").get("code").asText()).isEqualTo("COLLABORATION_UNAVAILABLE");
        } finally { REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec(); }
        failed.socket.abort();
        assertThat(join(connect(owner.token()), room)).isEqualTo(latest);
    }
    @Test void reconnectRechecksMembershipBeforeRestoringDocument() throws Exception {
        var owner = actor(); var room = room(owner); var old = connect(owner.token()); join(old, room); old.socket.abort();
        members.deleteById(new RoomMemberId(room, owner.id())); members.flush();
        var recovered = connect(owner.token());
        recovered.send("SUBSCRIBE", "id:room\ndestination:/topic/rooms/" + room + "/document", "");
        recovered.awaitClosed();
        assertThat(documents.snapshot(room, Language.JAVA).document().version()).isZero();
    }
    @Test void runtimeChannelsActuallyUseSeparateBoundedFifoExecutors() {
        var inbound = context.getBean("clientInboundChannel", org.springframework.messaging.support.ExecutorSubscribableChannel.class).getExecutor();
        var outbound = context.getBean("clientOutboundChannel", org.springframework.messaging.support.ExecutorSubscribableChannel.class).getExecutor();
        assertThat(inbound).isNotSameAs(outbound);
        for (var executor : List.of(inbound, outbound)) {
            assertThat(executor).isInstanceOf(org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class);
            var pool = (org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor) executor;
            assertThat(pool.getCorePoolSize()).isEqualTo(1); assertThat(pool.getMaxPoolSize()).isEqualTo(1);
            assertThat(pool.getQueueCapacity()).isEqualTo(64);
        }
    }
    @Test void queuedOutboundEventsRecheckExpiryBeforeSocketDelivery() throws Exception {
        var owner = actor(); var peer = connect(owner.token());
        var channel = context.getBean("clientOutboundChannel", org.springframework.messaging.support.ExecutorSubscribableChannel.class);
        var gate = channel.getInterceptors().stream()
                .filter(org.springframework.messaging.support.ExecutorChannelInterceptor.class::isInstance)
                .map(org.springframework.messaging.support.ExecutorChannelInterceptor.class::cast).findFirst().orElseThrow();
        // Locate this one connected user through the production session registry.
        var registry = context.getBean(org.springframework.messaging.simp.user.SimpUserRegistry.class);
        await().atMost(Duration.ofSeconds(2)).until(() -> registry.getUser(owner.id().toString()) != null);
        String id = registry.getUser(owner.id().toString()).getSessions().iterator().next().getId();
        var headers = org.springframework.messaging.simp.SimpMessageHeaderAccessor.create(org.springframework.messaging.simp.SimpMessageType.MESSAGE);
        headers.setSessionId(id);
        var queued = org.springframework.messaging.support.MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        assertThat(gate.preSend(queued, channel)).isSameAs(queued);
        sessions.require(id).expires = Instant.now().minusSeconds(1);
        assertThat(gate.beforeHandle(queued, channel, message -> {})).isNull();
        peer.awaitClosed();
    }
    @Test void originsQueriesMissingCredentialsAndBadTokensAreDenied() throws Exception {
        for (String origin : new String[]{null, "https://evil.example"}) assertThatThrownBy(() -> open(origin, "")).isInstanceOf(CompletionException.class);
        assertThatThrownBy(() -> open("http://127.0.0.1:5173", "?token=forbidden")).isInstanceOf(CompletionException.class);
        var peer = open("http://127.0.0.1:5173", ""); peer.send("CONNECT", "accept-version:1.2", ""); peer.awaitClosed();
        var invalid = open("http://127.0.0.1:5173", ""); invalid.send("CONNECT", "Authorization:Bearer invalid", ""); invalid.awaitClosed();
    }
    @Test void unauthenticatedConnectionsCannotBeKeptAliveWithHeartbeats() throws Exception {
        var peer = open("http://127.0.0.1:5173", ""); peer.socket.sendText("\n", true).join(); peer.awaitClosed();
    }
    @Test void membershipAndDestinationsAreCheckedForSubscriptionsAndSends() throws Exception {
        var owner = actor(); var room = room(owner); var stranger = actor();
        var denied = connect(stranger.token()); denied.send("SUBSCRIBE", "id:x\ndestination:/topic/rooms/" + room + "/document", ""); denied.awaitClosed();
        var sendDenied = connect(stranger.token()); sendDenied.send("SEND", "destination:/app/rooms/" + room + "/snapshot", "{}"); sendDenied.awaitClosed();
        for (String destination : new String[]{"/topic/rooms/" + room + "/document", "/queue/collaboration", "/app/unknown"}) {
            var peer = connect(owner.token()); peer.send("SEND", "destination:" + destination, "{}"); peer.awaitClosed();
        }
    }
    @Test void duplicateReorderedAndInvalidUpdatesDoNotMutateTheDocument() throws Exception {
        var owner = actor(); var room = room(owner); var peer = connect(owner.token()); var initial = join(peer, room);
        update(peer, room, initial, 2, "accepted"); event(peer, "UPDATED");
        update(peer, room, initial, 2, "duplicate"); assertThat(event(peer, "REJECTED").get("code").asText()).isEqualTo("DUPLICATE_UPDATE");
        update(peer, room, initial, 1, "reordered"); event(peer, "REJECTED");
        update(peer, room, initial, 3, "😀".repeat(16385)); event(peer, "ERROR");
        var state = documents.snapshot(room, Language.JAVA).document();
        assertThat(state.content()).isEqualTo("accepted"); assertThat(state.version()).isEqualTo(1);
    }
    @Test void redisLossResetsGenerationAndRejectsOldEdits() throws Exception {
        var owner = actor(); var room = room(owner); var peer = connect(owner.token()); var initial = join(peer, room);
        redis.delete(DocumentRepository.key(room));
        update(peer, room, initial, 1, "must not be restored");
        var reset = event(peer, "DOCUMENT_RESET").get("document");
        assertThat(reset.get("generationId").asText()).isNotEqualTo(initial.get("generationId").asText());
        assertThat(reset.get("version").asLong()).isZero(); assertThat(reset.get("content").asText()).contains("public class Main");
    }
    @Test void ttlRefreshAndExpiryUseRealRedis() {
        var owner = actor(); var room = room(owner); var first = documents.snapshot(room, Language.JAVA).document();
        redis.expire(DocumentRepository.key(room), Duration.ofMillis(500));
        documents.snapshot(room, Language.JAVA);
        assertThat(redis.getExpire(DocumentRepository.key(room), TimeUnit.MILLISECONDS)).isGreaterThan(2000);
        redis.expire(DocumentRepository.key(room), Duration.ofMillis(100));
        await().atMost(Duration.ofSeconds(2)).until(() -> Boolean.FALSE.equals(redis.hasKey(DocumentRepository.key(room))));
        assertThat(documents.snapshot(room, Language.JAVA).document().generationId()).isNotEqualTo(first.generationId());
    }
    @Test void atomicConcurrentUpdatesKeepContentLanguageAndVersionTogether() throws Exception {
        var owner = actor(); var room = room(owner); var initial = documents.snapshot(room, Language.JAVA).document();
        try (var pool = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<Future<DocumentRepository.Result>>();
            for (int i = 0; i < 20; i++) {
                int number = i;
                futures.add(pool.submit(() -> documents.update(room, new DocumentMessages.Update(initial.generationId(), number + 1,
                        UUID.randomUUID(), "content-" + number, number % 2 == 0 ? Language.JAVA : Language.PYTHON))));
            }
            var versions = new HashSet<Long>();
            for (var future : futures) { var doc = future.get(5, TimeUnit.SECONDS).document(); versions.add(doc.version());
                int number = Integer.parseInt(doc.content().substring(8)); assertThat(doc.language()).isEqualTo(number % 2 == 0 ? Language.JAVA : Language.PYTHON); }
            assertThat(versions).hasSize(20).contains(1L, 20L);
        }
    }
    @Test void snapshotsRaceWithEditsWithoutRegressingAcceptedState() throws Exception {
        var owner = actor(); var room = room(owner); var writer = connect(owner.token()); var initial = join(writer, room);
        var reader = connect(owner.token()); join(reader, room);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var write = pool.submit(() -> { update(writer, room, initial, 1, "racing edit"); return null; });
            var snapshot = pool.submit(() -> { reader.send("SEND", "destination:/app/rooms/" + room + "/snapshot\ncontent-type:application/json", "{}"); return null; });
            write.get(5, TimeUnit.SECONDS); snapshot.get(5, TimeUnit.SECONDS);
        }
        long version = -1;
        var types = new HashSet<String>();
        for (int i = 0; i < 2; i++) {
            var frame = reader.frame(); var value = json.readTree(frame.substring(frame.indexOf("\n\n") + 2));
            var document = value.get("document"); var current = document.get("version").asLong();
            assertThat(current).isGreaterThanOrEqualTo(version); version = current;
            if (current == 1) assertThat(document.get("content").asText()).isEqualTo("racing edit");
            types.add(value.get("type").asText());
        }
        assertThat(types).containsExactlyInAnyOrder("UPDATED", "SNAPSHOT"); assertThat(version).isEqualTo(1);
    }
    @Test void acceptedUpdatesRefreshTtlButAuthenticatedHeartbeatsDoNot() throws Exception {
        var owner = actor(); var room = room(owner); var peer = connect(owner.token()); var initial = join(peer, room);
        redis.expire(DocumentRepository.key(room), Duration.ofMillis(1000));
        update(peer, room, initial, 1, "accepted"); event(peer, "UPDATED");
        assertThat(redis.getExpire(DocumentRepository.key(room), TimeUnit.MILLISECONDS)).isGreaterThan(2000);
        redis.expire(DocumentRepository.key(room), Duration.ofMillis(200));
        peer.socket.sendText("\n", true).join();
        await().atMost(Duration.ofSeconds(2)).until(() -> Boolean.FALSE.equals(redis.hasKey(DocumentRepository.key(room))));
        assertThat(peer.closed).isNotCompleted();
    }
    @Test void establishedSessionExpiresWithoutAnotherClientMessage() throws Exception {
        var user = actor(); var now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("pairforge-api").audience(List.of("pairforge-browser")).subject(user.id().toString())
                .issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(2)).build();
        var token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        connect(token).awaitClosed();
    }
    @Test void excessConnectionsAndMessageFloodsAreClosed() throws Exception {
        var owner = actor(); connect(owner.token()); connect(owner.token()); connect(owner.token());
        var excess = open("http://127.0.0.1:5173", ""); excess.send("CONNECT", "Authorization:Bearer " + owner.token(), ""); excess.awaitClosed();
        var other = connect(actor().token());
        // Several STOMP frames in one WebSocket frame must not bypass message limits.
        other.socket.sendText("\n".repeat(1), true).join();
        String frames = "UNSUBSCRIBE\nid:unused\n\n\0".repeat(20);
        other.socket.sendText(frames, true).join(); other.awaitClosed();
    }
    @Test void redisOutageReturnsErrorWithoutAcknowledgingTheWrite() throws Exception {
        var owner = actor(); var room = room(owner); var peer = connect(owner.token()); var initial = join(peer, room);
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try { update(peer, room, initial, 1, "uncertain"); assertThat(event(peer, "ERROR").get("code").asText()).isEqualTo("COLLABORATION_UNAVAILABLE"); }
        finally { REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec(); }
        // A timed-out command can have an uncertain outcome; it is never retried automatically.
    }
    @Test void malformedAndOversizedFramesCloseWithoutLeakingPayloads(CapturedOutput output) throws Exception {
        String marker = "PRIVATE_FRAME_" + UUID.randomUUID();
        var malformed = open("http://127.0.0.1:5173", "");
        malformed.socket.sendText(marker + "\n\n\0", true).join(); malformed.awaitClosed();
        var large = connect(actor().token());
        large.socket.sendText("SEND\ndestination:/invalid\ncontent-length:500000\n\n" + "x".repeat(500000) + '\0', true).join();
        large.awaitClosed();
        assertThat(output.getAll()).doesNotContain(marker, KEY);
    }
    @Test void validUtf8BoundaryFitsTransportAndForgedIdentityDoesNotChangeMembership() throws Exception {
        var owner = actor(); var room = room(owner); var peer = connect(owner.token()); var initial = join(peer, room);
        String content = "😀".repeat(16384);
        sendUpdate(peer, room, Map.of("generationId", initial.get("generationId").asText(), "sequence", 1,
                "clientUpdateId", UUID.randomUUID(), "content", content, "language", "JAVA", "userId", UUID.randomUUID()));
        assertThat(event(peer, "UPDATED").get("document").get("content").asText()).isEqualTo(content);
        assertThat(members.countByIdRoomId(room)).isEqualTo(1);
    }
    @Test void membershipRemovalDeniesLaterSendsAndDoesNotWrite() throws Exception {
        var owner = actor(); var room = room(owner); var peer = connect(owner.token()); var initial = join(peer, room);
        members.deleteById(new RoomMemberId(room, owner.id())); members.flush();
        update(peer, room, initial, 1, "denied"); peer.awaitClosed();
        assertThat(documents.snapshot(room, Language.JAVA).document().version()).isZero();
    }
    static java.util.stream.Stream<Arguments> invalidJsonTypes() {
        return java.util.stream.Stream.of(Arguments.of("language", 0), Arguments.of("language", "0"),
                Arguments.of("content", 123), Arguments.of("sequence", 1.5), Arguments.of("sequence", "1"));
    }
    @ParameterizedTest @MethodSource("invalidJsonTypes")
    void wrongJsonTypesCannotBeCoercedIntoAcceptedUpdates(String field, Object value) throws Exception {
        var owner = actor(); var room = room(owner); var peer = connect(owner.token()); var initial = join(peer, room);
        var body = json.createObjectNode().put("generationId", initial.get("generationId").asText())
                .put("sequence", 1).put("clientUpdateId", UUID.randomUUID().toString())
                .put("content", "must not be accepted").put("language", "JAVA");
        body.set(field, json.valueToTree(value)); sendUpdate(peer, room, body);
        var frame = peer.frame(); var response = json.readTree(frame.substring(frame.indexOf("\n\n") + 2));
        assertThat(response.get("type").asText()).isEqualTo("ERROR");
        assertThat(response.get("code").asText()).isEqualTo("INVALID_UPDATE");
        assertThat(documents.snapshot(room, Language.JAVA).document().version()).isZero();
    }
    @Test void databaseLossFailsClosedOnNewSubscriptions() throws Exception {
        var owner = actor(); var room = room(owner); var peer = connect(owner.token());
        PG.getDockerClient().pauseContainerCmd(PG.getContainerId()).exec();
        try {
            peer.send("SUBSCRIBE", "id:room\ndestination:/topic/rooms/" + room + "/document", ""); peer.awaitClosed();
        } finally { PG.getDockerClient().unpauseContainerCmd(PG.getContainerId()).exec(); }
    }
}
