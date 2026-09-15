package com.pairforge.api.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pairforge.api.common.ApiException;
import com.pairforge.api.user.UserRepository;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthIT {
    static final String KEY = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    static final String PASSWORD = "a sufficiently long password";
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withPassword(UUID.randomUUID().toString());
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.11-bookworm").withExposedPorts(6379);
    @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1.8")
            .withAdminUser("test").withAdminPassword(UUID.randomUUID().toString());
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
        registry.add("pairforge.auth.registration-ip-limit", () -> 20);
        registry.add("pairforge.auth.login-ip-limit", () -> 10);
        registry.add("pairforge.auth.login-account-limit", () -> 3);
        registry.add("pairforge.auth.ip-window-seconds", () -> 60);
        registry.add("pairforge.auth.account-window-seconds", () -> 60);
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired StringRedisTemplate redis;
    @Autowired AuthRateLimiter limiter;
    @Autowired TokenService tokens;
    @Autowired org.springframework.security.oauth2.jwt.JwtEncoder encoder;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @BeforeEach void clearCounters() {
        var keys = redis.keys("auth:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys); // isolated test Redis only
    }
    String email() { return UUID.randomUUID() + "@example.com"; }
    String body(String email, String password) throws Exception {
        return json.writeValueAsString(Map.of("email", email, "password", password));
    }
    HttpResponse<String> request(String method, String path, String body, String... headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(12));
        if (headers.length > 0) builder.headers(headers);
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> register(String email) throws Exception {
        return request("POST", "/api/auth/register", body(email, PASSWORD));
    }
    String login(String email) throws Exception {
        var response = request("POST", "/api/auth/login", body(email, PASSWORD));
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).get("accessToken").asText();
    }
    JsonNode error(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        JsonNode error = json.readTree(response.body());
        assertThat(error.get("code").asText()).isEqualTo(code);
        assertThat(error.get("requestId").asText()).isEqualTo(response.headers().firstValue("X-Request-ID").orElseThrow());
        assertThat(response.body()).doesNotContain(PASSWORD, KEY, "password_hash", "stackTrace");
        return error;
    }

    @Test void registrationLoginAndProtectedIdentityUseOnlyHashedPasswords() throws Exception {
        String email = email();
        var registered = register(" " + email.toUpperCase(Locale.ROOT) + " ");
        assertThat(registered.statusCode()).isEqualTo(201);
        assertThat(json.readTree(registered.body()).get("email").asText()).isEqualTo(email);
        var user = users.findByEmail(email).orElseThrow();
        assertThat(user.getPasswordHash()).startsWith("$2a$04$").isNotEqualTo(PASSWORD);
        assertThat(passwords.matches(PASSWORD, user.getPasswordHash())).isTrue();
        String token = login(email);
        var me = request("GET", "/api/auth/me", null, "Authorization", "Bearer " + token);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(json.readTree(me.body()).get("id").asText()).isEqualTo(user.getId().toString());
        assertThat(me.body()).doesNotContain("password", user.getPasswordHash());
        assertThat(me.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(me.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        var loginBody = json.readTree(request("POST", "/api/auth/login", body(email, PASSWORD)).body());
        assertThat(loginBody.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(loginBody.get("expiresIn").asLong()).isEqualTo(900);
    }

    @Test void duplicateAndConcurrentRegistrationCreateOneUser() throws Exception {
        String email = email();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> register(email));
            var second = pool.submit(() -> register(email.toUpperCase(Locale.ROOT)));
            var responses = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 409);
            error(responses.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow(), 409, "EMAIL_ALREADY_REGISTERED");
        }
        assertThat(users.findAll().stream().filter(u -> u.getEmail().equals(email)).count()).isEqualTo(1);
    }

    @Test void unknownUserAndIncorrectPasswordHaveTheSamePublicError() throws Exception {
        String email = email();
        register(email);
        var wrong = error(request("POST", "/api/auth/login", body(email, "an incorrect long password")), 401, "UNAUTHORIZED");
        var unknown = error(request("POST", "/api/auth/login", body(email(), PASSWORD)), 401, "UNAUTHORIZED");
        assertThat(wrong.get("message")).isEqualTo(unknown.get("message"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{", "{\"email\":\"bad\",\"password\":\"short\"}",
            "{\"email\":\"test@example.com\",\"password\":null}"})
    void invalidJsonAndFieldsAreSafe(String body) throws Exception {
        error(request("POST", "/api/auth/register", body), 400, "INVALID_INPUT");
    }

    @Test void passwordByteLimitIsEnforcedWithoutTruncation() throws Exception {
        error(request("POST", "/api/auth/register", body(email(), "é".repeat(37))), 400, "INVALID_INPUT");
        String email = email();
        assertThat(request("POST", "/api/auth/register", body(email, "é".repeat(36))).statusCode()).isEqualTo(201);
        assertThat(request("POST", "/api/auth/login", body(email, "é".repeat(36))).statusCode()).isEqualTo(200);
    }

    @Test void oversizedKnownLengthAndChunkedBodiesAreRejected() throws Exception {
        String oversized = body(email(), "x".repeat(5000));
        var response = request("POST", "/api/auth/register", oversized, "Origin", "http://127.0.0.1:5173");
        error(response, 413, "REQUEST_TOO_LARGE");
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("http://127.0.0.1:5173");
        var chunked = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .header("Origin", "http://127.0.0.1:5173")
                .timeout(Duration.ofSeconds(12))
                .POST(HttpRequest.BodyPublishers.ofInputStream(() ->
                        new java.io.ByteArrayInputStream(oversized.getBytes(java.nio.charset.StandardCharsets.UTF_8)))).build();
        var chunkedResponse = http.send(chunked, HttpResponse.BodyHandlers.ofString());
        error(chunkedResponse, 413, "REQUEST_TOO_LARGE");
        assertThat(chunkedResponse.headers().firstValue("Access-Control-Allow-Origin")).contains("http://127.0.0.1:5173");
        error(request("POST", "/api/auth/register", oversized, "Origin", "https://untrusted.example"), 403, "FORBIDDEN");
    }

    @Test void missingMalformedAndNonexistentUserTokensAreRejected() throws Exception {
        error(request("GET", "/api/auth/me", null), 401, "UNAUTHORIZED");
        error(request("GET", "/api/auth/me", null, "Authorization", "Bearer invalid"), 401, "UNAUTHORIZED");
        String token = tokens.issue(UUID.randomUUID()).accessToken();
        error(request("GET", "/api/auth/me", null, "Authorization", "Bearer " + token), 401, "UNAUTHORIZED");
        error(request("GET", "/api/auth/me?access_token=" + token, null), 401, "UNAUTHORIZED");
    }

    @Test void unmatchedRoutesAndActuatorRemainDeniedWithAValidToken() throws Exception {
        String email = email();
        register(email);
        String token = login(email);
        for (String path : List.of("/api/rooms", "/actuator/env", "/actuator/heapdump")) {
            error(request("GET", path, null, "Authorization", "Bearer " + token), 403, "FORBIDDEN");
        }
        assertThat(request("GET", "/actuator/health", null).statusCode()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "issuer", "audience", "tampered"})
    void installedBearerFilterRejectsInvalidTokensForAnExistingUser(String scenario) throws Exception {
        String email = email();
        assertThat(register(email).statusCode()).isEqualTo(201);
        var now = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                .issuer(scenario.equals("issuer") ? "wrong-issuer" : "pairforge-api")
                .audience(List.of(scenario.equals("audience") ? "wrong-audience" : "pairforge-browser"))
                .subject(users.findByEmail(email).orElseThrow().getId().toString())
                .issuedAt(now.minusSeconds(60)).notBefore(now.minusSeconds(60))
                .expiresAt(scenario.equals("expired") ? now.minusSeconds(1) : now.plusSeconds(60)).build();
        String token = encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(
                        org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(), claims)).getTokenValue();
        if (scenario.equals("tampered")) {
            int signature = token.lastIndexOf('.') + 1;
            token = token.substring(0, signature) + (token.charAt(signature) == 'A' ? 'B' : 'A') + token.substring(signature + 1);
        }
        error(request("GET", "/api/auth/me", null, "Authorization", "Bearer " + token), 401, "UNAUTHORIZED");
    }

    @Test void corsAllowsOnlyConfiguredOriginsAndNoCredentials() throws Exception {
        var allowed = request("OPTIONS", "/api/auth/login", null, "Origin", "http://127.0.0.1:5173",
                "Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "content-type");
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).contains("http://127.0.0.1:5173");
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
        error(request("OPTIONS", "/api/auth/login", null, "Origin", "https://untrusted.example",
                "Access-Control-Request-Method", "POST"), 403, "FORBIDDEN");
        error(request("POST", "/api/auth/register", body(email(), PASSWORD), "Origin", "https://untrusted.example"),
                403, "FORBIDDEN");
    }

    @Test void basicAuthAndCookiesDoNotBypassBearerSecurityOrCreateSessions() throws Exception {
        error(request("GET", "/api/auth/me", null, "Authorization", "Basic dXNlcjpwYXNz", "Cookie", "JSESSIONID=madeup"),
                401, "UNAUTHORIZED");
        var denied = request("POST", "/api/rooms", "{}");
        error(denied, 401, "UNAUTHORIZED");
        assertThat(denied.headers().allValues("Set-Cookie")).isEmpty();
        String email = email();
        var registered = register(email);
        assertThat(registered.statusCode()).isEqualTo(201);
        assertThat(registered.headers().allValues("Set-Cookie")).isEmpty();
        var authenticatedDenied = request("POST", "/api/rooms", "{}", "Authorization", "Bearer " + login(email));
        error(authenticatedDenied, 403, "FORBIDDEN");
        assertThat(authenticatedDenied.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test void credentialEndpointsRejectBrowserFormContentTypes() throws Exception {
        for (String path : List.of("/api/auth/register", "/api/auth/login")) {
            for (String contentType : List.of("text/plain", "application/x-www-form-urlencoded", "multipart/form-data")) {
                var form = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(12)).header("Content-Type", contentType)
                        .POST(HttpRequest.BodyPublishers.ofString(body(email(), PASSWORD))).build();
                error(http.send(form, HttpResponse.BodyHandlers.ofString()), 415, "UNSUPPORTED_MEDIA_TYPE");
            }
        }
    }

    @Test void accountThrottleIsAtomicAndReportsRetryAfter() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            var attempts = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 8; i++) attempts.add(pool.submit(() -> {
                try { limiter.login("test-ip", "same-account"); return 200; }
                catch (ApiException error) { return error.status(); }
            }));
            var results = new ArrayList<Integer>();
            for (var attempt : attempts) results.add(attempt.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(code -> code == 200).count()).isEqualTo(3);
            assertThat(results.stream().filter(code -> code == 429).count()).isEqualTo(5);
        }
        var response = request("POST", "/api/auth/login", body("throttle@example.com", PASSWORD));
        for (int i = 1; i < 4; i++) response = request("POST", "/api/auth/login", body(" THROTTLE@EXAMPLE.COM ", PASSWORD));
        error(response, 429, "RATE_LIMITED");
        assertThat(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow())).isPositive();
        assertThat(redis.keys("auth:*")).allMatch(key -> !key.contains("throttle@example.com"));
    }

    @Test void ipLimitCannotBeBypassedWithForwardedHeaders() throws Exception {
        for (int i = 0; i < 10; i++) {
            error(request("POST", "/api/auth/login", body(email(), PASSWORD), "X-Forwarded-For", "192.0.2." + i),
                    401, "UNAUTHORIZED");
        }
        error(request("POST", "/api/auth/login", body(email(), PASSWORD), "X-Forwarded-For", "192.0.2.99"),
                429, "RATE_LIMITED");
    }

    @Test void registrationIsLimitedBeforePasswordWork() {
        for (int i = 0; i < 20; i++) limiter.register("registration-ip");
        assertThatThrownBy(() -> limiter.register("registration-ip")).isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).status()).isEqualTo(429));
    }

    @Test void countersHaveExpiryAndAdmissionResumesAfterExpiry() {
        limiter.login("expiring-ip", "expiring-account");
        var keys = redis.keys("auth:*");
        assertThat(keys).hasSize(2);
        for (String key : keys) {
            assertThat(redis.getExpire(key)).isBetween(1L, 60L);
            redis.expire(key, Duration.ofMillis(50));
        }
        await().atMost(Duration.ofSeconds(3)).until(() -> redis.keys("auth:*").isEmpty());
        assertThatCode(() -> limiter.login("expiring-ip", "expiring-account")).doesNotThrowAnyException();
    }

    @Test void deniedAttemptsDoNotIncrementCountersOrExtendTheirWindow() {
        for (int i = 0; i < 3; i++) limiter.login("fixed-ip", "fixed-account");
        var keys = redis.keys("auth:*");
        assertThat(keys).hasSize(2);
        for (String key : keys) redis.expire(key, Duration.ofSeconds(10));
        assertThatThrownBy(() -> limiter.login("fixed-ip", "fixed-account")).isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).status()).isEqualTo(429));
        for (String key : keys) {
            assertThat(redis.opsForValue().get(key)).isEqualTo("3");
            assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 10_000L);
        }
    }

    @Test void redisOutageFailsClosedAndRecoversWithoutCreatingAnAccount() throws Exception {
        String email = email();
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            error(register(email), 503, "DEPENDENCY_UNAVAILABLE");
            error(request("POST", "/api/auth/login", body(email, PASSWORD)), 503, "DEPENDENCY_UNAVAILABLE");
            assertThat(users.findByEmail(email)).isEmpty();
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(register(email).statusCode()).isEqualTo(201));
    }

    @Test void postgresOutageProduces503AndRecovers() throws Exception {
        String email = email();
        register(email);
        String token = login(email);
        PG.getDockerClient().pauseContainerCmd(PG.getContainerId()).exec();
        try {
            error(request("POST", "/api/auth/login", body(email, PASSWORD)), 503, "DEPENDENCY_UNAVAILABLE");
            error(request("GET", "/api/auth/me", null, "Authorization", "Bearer " + token), 503, "DEPENDENCY_UNAVAILABLE");
            error(register(email()), 503, "DEPENDENCY_UNAVAILABLE");
        } finally {
            PG.getDockerClient().unpauseContainerCmd(PG.getContainerId()).exec();
        }
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(request("GET", "/api/auth/me", null, "Authorization", "Bearer " + token).statusCode()).isEqualTo(200));
    }
}
