package com.pairforge.api.room;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pairforge.api.auth.TokenService;
import com.pairforge.api.user.User;
import com.pairforge.api.user.UserRepository;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class RoomIT {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withPassword(UUID.randomUUID().toString());
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.11-bookworm").withExposedPorts(6379);
    @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1.8")
            .withAdminUser("test").withAdminPassword(UUID.randomUUID().toString());
    static final String KEY = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("pairforge.auth.key-hex", () -> KEY);
        registry.add("pairforge.auth.bcrypt-cost", () -> 4);
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired RoomRepository rooms;
    @Autowired RoomMemberRepository members;
    @Autowired TokenService tokens;
    @Autowired org.springframework.security.oauth2.jwt.JwtEncoder encoder;
    @Autowired InvitationTokenService invitations;
    @Autowired JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    record Actor(UUID id, String token) {}
    Actor actor() {
        var user = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "unused-test-hash"));
        return new Actor(user.getId(), tokens.issue(user.getId()).accessToken());
    }
    HttpResponse<String> request(String method, String path, String body, String token, String... headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(12));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (headers.length > 0) builder.headers(headers);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    String createBody(String name) throws Exception {
        return json.writeValueAsString(Map.of("name", name, "language", "JAVA"));
    }
    JsonNode create(Actor owner) throws Exception {
        var response = request("POST", "/api/rooms", createBody("Room"), owner.token());
        assertThat(response.statusCode()).isEqualTo(201);
        var body = json.readTree(response.body());
        assertThat(response.headers().firstValue("Location")).contains("/api/rooms/" + body.at("/room/id").asText());
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        return body;
    }
    String path(JsonNode created) { return "/api/rooms/" + created.at("/room/id").asText(); }
    String joinBody(JsonNode created) throws Exception {
        return json.writeValueAsString(Map.of("invitationToken", created.get("invitationToken").asText()));
    }
    void error(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        var body = json.readTree(response.body());
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("requestId").asText()).isEqualTo(response.headers().firstValue("X-Request-ID").orElseThrow());
        assertThat(response.body()).doesNotContain(KEY, "password_hash", "stackTrace", "invitation_token_hash");
    }

    @Test void registeredUsersCanCreateJoinListAndReadWithThreeMembers(CapturedOutput output) throws Exception {
        var actors = new ArrayList<Actor>();
        for (int i = 0; i < 3; i++) {
            String credentials = json.writeValueAsString(Map.of("email", UUID.randomUUID() + "@example.com",
                    "password", "a sufficiently long password"));
            var registered = request("POST", "/api/auth/register", credentials, null);
            assertThat(registered.statusCode()).isEqualTo(201);
            var login = request("POST", "/api/auth/login", credentials, null);
            assertThat(login.statusCode()).isEqualTo(200);
            actors.add(new Actor(UUID.fromString(json.readTree(registered.body()).get("id").asText()),
                    json.readTree(login.body()).get("accessToken").asText()));
        }
        var created = create(actors.getFirst());
        UUID roomId = UUID.fromString(created.at("/room/id").asText());
        String invitation = created.get("invitationToken").asText();
        String hash = rooms.findById(roomId).orElseThrow().getInvitationTokenHash();
        assertThat(hash).isEqualTo(invitations.hash(invitation));
        assertThat(members.existsById(new RoomMemberId(roomId, actors.getFirst().id()))).isTrue();
        assertThat(created.at("/room/ownerId").asText()).isEqualTo(actors.getFirst().id().toString());
        for (var actor : actors) {
            var joined = request("POST", path(created) + "/join", joinBody(created), actor.token());
            assertThat(joined.statusCode()).isEqualTo(200);
            var read = request("GET", path(created), null, actor.token());
            assertThat(read.statusCode()).isEqualTo(200);
            var listed = request("GET", "/api/rooms", null, actor.token());
            assertThat(json.readTree(listed.body()).get("items").get(0).get("id").asText()).isEqualTo(roomId.toString());
            assertThat(joined.body() + read.body() + listed.body()).doesNotContain(invitation, hash, "invitationToken", "password");
        }
        assertThat(members.countByIdRoomId(roomId)).isEqualTo(3);
        assertThat(output.getAll()).doesNotContain(invitation, hash);
    }

    @Test void invitationsAndReadsConcealRoomsFromNonmembers(CapturedOutput output) throws Exception {
        var owner = actor();
        var outsider = actor();
        var created = create(owner);
        var other = create(owner);
        String missing = "/api/rooms/" + UUID.randomUUID();
        for (String target : List.of(path(created), missing)) {
            error(request("GET", target, null, outsider.token()), 404, "ROOM_NOT_FOUND");
            error(request("POST", target + "/join", joinBody(other), outsider.token()), 404, "ROOM_NOT_FOUND");
        }
        error(request("POST", path(created) + "/join", "{}", outsider.token()), 400, "INVALID_INPUT");
        error(request("POST", path(created) + "/join", joinBody(other), owner.token()), 404, "ROOM_NOT_FOUND");
        var list = request("GET", "/api/rooms", null, outsider.token());
        assertThat(json.readTree(list.body()).get("items")).isEmpty();
        assertThat(members.countByIdRoomId(UUID.fromString(created.at("/room/id").asText()))).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain(created.get("invitationToken").asText(), other.get("invitationToken").asText());
    }

    @Test void duplicateConcurrentJoinsPreserveOneMembershipAndItsTimestamp() throws Exception {
        var created = create(actor());
        var guest = actor();
        String body = joinBody(created);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Future<HttpResponse<String>>>();
            for (int i = 0; i < 8; i++) tasks.add(pool.submit(() -> request("POST", path(created) + "/join", body, guest.token())));
            for (var task : tasks) assertThat(task.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        }
        UUID roomId = UUID.fromString(created.at("/room/id").asText());
        var id = new RoomMemberId(roomId, guest.id());
        var joinedAt = members.findById(id).orElseThrow().getJoinedAt();
        assertThat(request("POST", path(created) + "/join", body, guest.token()).statusCode()).isEqualTo(200);
        assertThat(members.findById(id).orElseThrow().getJoinedAt()).isEqualTo(joinedAt);
        assertThat(members.countByIdRoomId(roomId)).isEqualTo(2);
    }

    @Test void ownerMembershipFailureRollsBackRoomCreation() throws Exception {
        var owner = actor();
        long before = rooms.count();
        jdbc.execute("ALTER TABLE room_members ADD CONSTRAINT test_membership_failure CHECK (false) NOT VALID");
        try {
            error(request("POST", "/api/rooms", createBody("Rollback"), owner.token()), 503, "DEPENDENCY_UNAVAILABLE");
            assertThat(rooms.count()).isEqualTo(before);
        } finally {
            jdbc.execute("ALTER TABLE room_members DROP CONSTRAINT test_membership_failure");
        }
    }

    @Test void listingIsBoundedDeterministicAndMembershipFiltered() throws Exception {
        var owner = actor();
        for (int i = 0; i < 3; i++) create(owner);
        create(actor());
        jdbc.update("UPDATE rooms SET created_at = '2026-01-01T00:00:00Z' WHERE owner_id = ?", owner.id());
        var expected = jdbc.queryForList("SELECT id FROM rooms WHERE owner_id = ? ORDER BY created_at DESC, id DESC", UUID.class, owner.id());
        for (int i = 0; i < 3; i++) {
            var page = json.readTree(request("GET", "/api/rooms?page=" + i + "&size=1", null, owner.token()).body());
            assertThat(page.get("items")).hasSize(1);
            assertThat(page.at("/items/0/id").asText()).isEqualTo(expected.get(i).toString());
            assertThat(page.get("hasNext").asBoolean()).isEqualTo(i < 2);
        }
        var defaults = json.readTree(request("GET", "/api/rooms", null, owner.token()).body());
        assertThat(defaults.get("size").asInt()).isEqualTo(20);
        assertThat(defaults.get("page").asInt()).isZero();
        assertThat(request("GET", "/api/rooms?size=100", null, owner.token()).statusCode()).isEqualTo(200);
        assertThat(json.readTree(request("GET", "/api/rooms?page=3&size=1", null, owner.token()).body()).get("items")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"?page=-1", "?size=0", "?size=101", "?page=no", "?page=2147483647&size=100", "/not-a-uuid", "/%20"})
    void invalidParametersReturnSafe400(String suffix) throws Exception {
        error(request("GET", "/api/rooms" + suffix, null, actor().token()), 400, "INVALID_INPUT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{", "{\"name\":\" \t\",\"language\":\"JAVA\"}",
            "{\"name\":\"Room\",\"language\":\"RUBY\"}", "{\"name\":\"Room\",\"language\":0}"})
    void invalidCreateBodiesAreRejected(String body) throws Exception {
        error(request("POST", "/api/rooms", body, actor().token()), 400, "INVALID_INPUT");
    }

    @Test void namesAndInvitationsHaveSafeBoundaries() throws Exception {
        var owner = actor();
        var python = request("POST", "/api/rooms", "{\"name\":\"Python room\",\"language\":\"PYTHON\"}", owner.token());
        assertThat(python.statusCode()).isEqualTo(201);
        assertThat(json.readTree(python.body()).at("/room/language").asText()).isEqualTo("PYTHON");
        assertThat(request("POST", "/api/rooms", createBody("x".repeat(120)), owner.token()).statusCode()).isEqualTo(201);
        for (String name : List.of("x".repeat(121), "bad\0name")) {
            error(request("POST", "/api/rooms", createBody(name), owner.token()), 400, "INVALID_INPUT");
        }
        error(request("POST", "/api/rooms", "{\"name\":\"bad\\uD800name\",\"language\":\"JAVA\"}", owner.token()),
                400, "INVALID_INPUT");
        var created = create(owner);
        for (String token : List.of("", "short", "!".repeat(43), "x".repeat(44))) {
            var response = request("POST", path(created) + "/join", json.writeValueAsString(Map.of("invitationToken", token)), owner.token());
            error(response, 400, "INVALID_INPUT");
            if (!token.isEmpty()) assertThat(response.body()).doesNotContain("\"" + token + "\"");
        }
        error(request("POST", "/api/rooms/not-a-uuid/join", joinBody(created), owner.token()), 400, "INVALID_INPUT");
        error(request("POST", "/api/rooms/%20/join", joinBody(created), owner.token()), 400, "INVALID_INPUT");
    }

    @Test void onlyBearerIdentityCanAuthorizeRoomOperations() throws Exception {
        var owner = actor();
        var created = create(owner);
        for (String token : Arrays.asList(null, "invalid", tokens.issue(UUID.randomUUID()).accessToken())) {
            error(request("POST", "/api/rooms", createBody("Room"), token), 401, "UNAUTHORIZED");
            error(request("GET", "/api/rooms", null, token), 401, "UNAUTHORIZED");
            error(request("GET", path(created), null, token), 401, "UNAUTHORIZED");
            error(request("POST", path(created) + "/join", joinBody(created), token), 401, "UNAUTHORIZED");
        }
        var forgedOwner = createBody("Room").replace("}", ",\"ownerId\":\"" + UUID.randomUUID() + "\"}");
        var response = request("POST", "/api/rooms", forgedOwner, owner.token());
        assertThat(json.readTree(response.body()).at("/room/ownerId").asText()).isEqualTo(owner.id().toString());
        error(request("POST", path(created) + "/executions", "{}", owner.token()), 400, "INVALID_INPUT");
        error(request("POST", path(created) + "/unsupported", "{}", owner.token()), 403, "FORBIDDEN");
    }

    @Test void expiredBearerTokensCannotReadOrMutateRooms() throws Exception {
        var owner = actor();
        var created = create(owner);
        var now = java.time.Instant.now();
        var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                .issuer("pairforge-api").audience(List.of("pairforge-browser")).subject(owner.id().toString())
                .issuedAt(now.minusSeconds(120)).notBefore(now.minusSeconds(120)).expiresAt(now.minusSeconds(60)).build();
        String expired = encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(
                        org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(), claims)).getTokenValue();
        error(request("GET", path(created), null, expired), 401, "UNAUTHORIZED");
        error(request("GET", "/api/rooms", null, expired), 401, "UNAUTHORIZED");
        error(request("POST", "/api/rooms", createBody("Expired"), expired), 401, "UNAUTHORIZED");
        error(request("POST", path(created) + "/join", joinBody(created), expired), 401, "UNAUTHORIZED");
    }

    @Test void joinCannotAssignMembershipToAClientSuppliedUser() throws Exception {
        var created = create(actor());
        var guest = actor();
        var other = actor();
        String body = json.writeValueAsString(Map.of("invitationToken", created.get("invitationToken").asText(),
                "userId", other.id().toString()));
        assertThat(request("POST", path(created) + "/join", body, guest.token()).statusCode()).isEqualTo(200);
        UUID roomId = UUID.fromString(created.at("/room/id").asText());
        assertThat(members.existsById(new RoomMemberId(roomId, guest.id()))).isTrue();
        assertThat(members.existsById(new RoomMemberId(roomId, other.id()))).isFalse();
        error(request("GET", path(created), null, other.token()), 404, "ROOM_NOT_FOUND");
    }

    @Test void boundedJsonAndCorsApplyToCreateAndJoin() throws Exception {
        var owner = actor();
        var created = create(owner);
        for (String target : List.of("/api/rooms", path(created) + "/join")) {
            var oversized = request("POST", target, " ".repeat(5000), owner.token(), "Origin", "http://127.0.0.1:5173");
            error(oversized, 413, "REQUEST_TOO_LARGE");
            assertThat(oversized.headers().firstValue("Access-Control-Allow-Origin")).contains("http://127.0.0.1:5173");
            var chunked = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + target)).timeout(Duration.ofSeconds(12))
                    .header("Authorization", "Bearer " + owner.token()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(new byte[5000]))).build();
            error(http.send(chunked, HttpResponse.BodyHandlers.ofString()), 413, "REQUEST_TOO_LARGE");
            for (String type : List.of("text/plain", "application/x-www-form-urlencoded", "multipart/form-data")) {
                var form = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + target)).timeout(Duration.ofSeconds(12))
                        .header("Authorization", "Bearer " + owner.token()).header("Content-Type", type)
                        .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
                error(http.send(form, HttpResponse.BodyHandlers.ofString()), 415, "UNSUPPORTED_MEDIA_TYPE");
            }
            error(request("POST", target, "{}", owner.token(), "Origin", "https://untrusted.example"), 403, "FORBIDDEN");
            var preflight = request("OPTIONS", target, null, null, "Origin", "http://127.0.0.1:5173",
                    "Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "authorization,content-type");
            assertThat(preflight.statusCode()).isEqualTo(200);
        }
    }

    @Test void postgresOutageFailsSafelyAndRecovers() throws Exception {
        var owner = actor();
        var guest = actor();
        var created = create(owner);
        long before = rooms.count();
        PG.getDockerClient().pauseContainerCmd(PG.getContainerId()).exec();
        try {
            error(request("POST", "/api/rooms", createBody("Outage"), owner.token()), 503, "DEPENDENCY_UNAVAILABLE");
            error(request("GET", "/api/rooms", null, owner.token()), 503, "DEPENDENCY_UNAVAILABLE");
            error(request("GET", path(created), null, owner.token()), 503, "DEPENDENCY_UNAVAILABLE");
            error(request("POST", path(created) + "/join", joinBody(created), guest.token()), 503, "DEPENDENCY_UNAVAILABLE");
        } finally {
            PG.getDockerClient().unpauseContainerCmd(PG.getContainerId()).exec();
        }
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(request("GET", path(created), null, owner.token()).statusCode()).isEqualTo(200));
        assertThat(rooms.count()).isEqualTo(before);
        assertThat(request("POST", path(created) + "/join", joinBody(created), guest.token()).statusCode()).isEqualTo(200);
    }

    @Test void roomOperationsDoNotDependOnRedisOrRabbitMq() throws Exception {
        var owner = actor();
        var guest = actor();
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        RABBIT.getDockerClient().pauseContainerCmd(RABBIT.getContainerId()).exec();
        try {
            var created = create(owner);
            assertThat(request("POST", path(created) + "/join", joinBody(created), guest.token()).statusCode()).isEqualTo(200);
            assertThat(request("GET", path(created), null, guest.token()).statusCode()).isEqualTo(200);
            assertThat(request("GET", "/api/rooms", null, guest.token()).statusCode()).isEqualTo(200);
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
            RABBIT.getDockerClient().unpauseContainerCmd(RABBIT.getContainerId()).exec();
        }
    }
}
