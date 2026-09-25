package com.pairforge.api;

import com.pairforge.api.auth.TokenService;
import com.pairforge.api.common.Language;
import com.pairforge.api.execution.ExecutionRepository;
import com.pairforge.api.room.*;
import com.pairforge.api.user.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.testcontainers.containers.*;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.images.builder.Transferable;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "pairforge.execution-access.restricted=true", "server.forward-headers-strategy=native",
        "server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1",
        "server.tomcat.remoteip.remote-ip-header=X-Forwarded-For", "server.tomcat.remoteip.protocol-header=X-Forwarded-Proto"})
@Import(DeploymentIT.CaptureConfiguration.class)
class DeploymentIT {
    @org.junit.jupiter.api.io.TempDir static Path operationDirectory;
    static final User APPROVED = new User("approved@example.test", "unused");
    static final User OTHER = new User("other@example.test", "unused");
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17.11-bookworm");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.11-bookworm").withExposedPorts(6379);
    @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1.8")
            .withAdminUser("test").withAdminPassword(UUID.randomUUID().toString());
    static GenericContainer<?> proxy;
    static GenericContainer<?> origin;
    static final Network NETWORK = Network.newNetwork();
    static final String ORIGIN_TOKEN = UUID.randomUUID().toString().replace("-", "");
    static final String CADDY_IMAGE = "caddy@sha256:6aeddd44c3078b0f9a35206472a11420648a79c184603ef95957d0a20044cb2b";
    static HttpClient https;
    static final AtomicReference<String> REMOTE = new AtomicReference<>();
    static final AtomicReference<Boolean> SECURE = new AtomicReference<>();
    @DynamicPropertySource static void configure(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl); r.add("spring.datasource.username", PG::getUsername); r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost); r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("spring.rabbitmq.host", RABBIT::getHost); r.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        r.add("spring.rabbitmq.username", RABBIT::getAdminUsername); r.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        r.add("pairforge.auth.key-hex", () -> UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));
        r.add("pairforge.auth.allowed-origins", () -> "https://localhost");
        r.add("pairforge.execution-access.users", () -> APPROVED.getId().toString());
        r.add("pairforge.execution-access.enabled-file", () -> operationDirectory.resolve("enabled").toString());
    }
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired RoomService rooms;
    @Autowired TokenService tokens;
    @Autowired ExecutionRepository executions;
    @TestConfiguration(proxyBeanMethods = false) static class CaptureConfiguration {
        @Bean FilterRegistrationBean<jakarta.servlet.Filter> capture() {
            var registration = new FilterRegistrationBean<jakarta.servlet.Filter>((request, response, chain) -> {
                if (((jakarta.servlet.http.HttpServletRequest) request).getRequestURI().equals("/api/auth/me")) {
                    REMOTE.set(request.getRemoteAddr()); SECURE.set(request.isSecure());
                }
                chain.doFilter(request, response);
            });
            registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }
    }
    @BeforeEach void setup() throws Exception {
        Files.writeString(operationDirectory.resolve("enabled"), "ready");
        if (!users.existsById(APPROVED.getId())) { users.saveAndFlush(APPROVED); users.saveAndFlush(OTHER); }
        if (proxy != null) return;
        org.testcontainers.Testcontainers.exposeHostPorts(port);
        String config = Files.readString(Path.of("../infra/deploy/Caddyfile"));
        origin = new GenericContainer<>(CADDY_IMAGE).withNetwork(NETWORK).withNetworkAliases("origin")
                .withEnv("API_UPSTREAM", "host.testcontainers.internal:" + port).withEnv("FRONTEND_ROOT", "/srv")
                .withEnv("ORIGIN_BIND", "0.0.0.0").withEnv("ORIGIN_TOKEN", ORIGIN_TOKEN)
                .withCopyToContainer(Transferable.of(config), "/etc/caddy/Caddyfile")
                .withCopyToContainer(Transferable.of("PairForge deployment fixture"), "/srv/index.html")
                .withExposedPorts(8084).waitingFor(Wait.forLogMessage(".*server running.*", 1));
        origin.start();
        // Local edge fixture tests the transport contract, not the real AWS service.
        String edge = "https://localhost {\n tls internal\n reverse_proxy origin:8084 {\n"
                + " header_up X-PairForge-Origin " + ORIGIN_TOKEN + "\n"
                + " header_up CloudFront-Viewer-Address 198.51.100.25:12345\n }\n}";
        proxy = new GenericContainer<>(CADDY_IMAGE).withNetwork(NETWORK)
                .withCopyToContainer(Transferable.of(edge), "/etc/caddy/Caddyfile")
                .withExposedPorts(443).waitingFor(Wait.forLogMessage(".*server running.*", 1));
        proxy.start();
        byte[] ca = proxy.copyFileFromContainer("/data/caddy/pki/authorities/local/root.crt", InputStream::readAllBytes);
        var trust = KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null);
        trust.setCertificateEntry("fixture", CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(ca)));
        var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); managers.init(trust);
        var ssl = SSLContext.getInstance("TLS"); ssl.init(null, managers.getTrustManagers(), null);
        https = HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(5)).build();
    }
    @AfterAll static void cleanup() {
        if (proxy != null) proxy.close();
        if (origin != null) origin.close();
        NETWORK.close();
    }
    URI uri(String path) { return URI.create("https://localhost:" + proxy.getMappedPort(443) + path); }
    HttpResponse<String> get(String path) throws Exception {
        return https.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void tlsRoutesStaticAndRestButNeverManagementAndRejectsUntrustedCertificates() throws Exception {
        assertThat(get("/rooms/example").body()).isEqualTo("PairForge deployment fixture");
        assertThat(get("/api/auth/me").statusCode()).isEqualTo(401);
        assertThat(get("/actuator/prometheus").statusCode()).isEqualTo(404);
        assertThat(get("/.env").statusCode()).isEqualTo(404);
        assertThatThrownBy(() -> HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("/")).build(), HttpResponse.BodyHandlers.discarding()))
                .isInstanceOf(SSLHandshakeException.class);
    }
    @Test void proxyOverwritesSpoofedHeadersAndTomcatTrustsOnlyTheLocalProxy() throws Exception {
        https.send(HttpRequest.newBuilder(uri("/api/auth/me")).header("X-Forwarded-For", "203.0.113.19")
                .header("CloudFront-Viewer-Address", "203.0.113.22:12345")
                .header("Forwarded", "for=203.0.113.20;proto=http").header("X-Forwarded-Proto", "http").build(), HttpResponse.BodyHandlers.discarding());
        assertThat(REMOTE.get()).isEqualTo("198.51.100.25");
        assertThat(SECURE.get()).isTrue();
        try (var socket = new Socket()) {
            socket.bind(new InetSocketAddress("127.0.0.2", 0)); socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET /api/auth/me HTTP/1.1\r\nHost: localhost\r\nX-Forwarded-For: 203.0.113.21\r\nX-Forwarded-Proto: https\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getInputStream().readAllBytes();
        }
        assertThat(REMOTE.get()).isEqualTo("127.0.0.2"); assertThat(SECURE.get()).isFalse();
    }
    @Test void originRejectsDirectRequestsAndMissingViewerIdentity() throws Exception {
        var client = HttpClient.newHttpClient();
        var target = URI.create("http://localhost:" + origin.getMappedPort(8084) + "/api/auth/me");
        assertThat(client.send(HttpRequest.newBuilder(target).build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
        assertThat(client.send(HttpRequest.newBuilder(target).header("X-PairForge-Origin", "wrong")
                .header("CloudFront-Viewer-Address", "198.51.100.25:12345").build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
        assertThat(client.send(HttpRequest.newBuilder(target).header("X-PairForge-Origin", ORIGIN_TOKEN)
                .build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(400);
        assertThat(client.send(HttpRequest.newBuilder(target).header("X-PairForge-Origin", ORIGIN_TOKEN)
                .header("CloudFront-Viewer-Address", "malformed").build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(400);
        assertThat(client.send(HttpRequest.newBuilder(target).header("X-PairForge-Origin", ORIGIN_TOKEN)
                .header("CloudFront-Viewer-Address", "[2001:db8::25]:12345").build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
        assertThat(REMOTE.get()).isEqualTo("2001:db8::25");
    }
    HttpResponse<String> submit(User user, UUID room) throws Exception {
        return https.send(HttpRequest.newBuilder(uri("/api/rooms/" + room + "/executions"))
                .header("Authorization", "Bearer " + tokens.issue(user.getId()).accessToken()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"language\":\"PYTHON\",\"source\":\"print(1)\"}")).build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void roomInvitationDoesNotApproveExecutionAndApprovalDoesNotGrantMembership() throws Exception {
        var room = rooms.create(APPROVED.getId(), new RoomDtos.CreateRequest("Deployment", Language.JAVA));
        rooms.join(OTHER.getId(), room.room().id(), new RoomDtos.JoinRequest(room.invitationToken()));
        long before = executions.count();
        var denied = submit(OTHER, room.room().id());
        assertThat(denied.statusCode()).isEqualTo(403); assertThat(denied.body()).contains("EXECUTION_RESTRICTED");
        assertThat(executions.count()).isEqualTo(before);
        Files.delete(operationDirectory.resolve("enabled"));
        var closed = submit(APPROVED, room.room().id());
        assertThat(closed.statusCode()).isEqualTo(503);
        assertThat(closed.body()).contains("EXECUTION_UNAVAILABLE");
        assertThat(executions.count()).isEqualTo(before);
        Files.writeString(operationDirectory.resolve("enabled"), "ready");
        assertThat(submit(APPROVED, room.room().id()).statusCode()).isEqualTo(202);
        var unrelated = rooms.create(OTHER.getId(), new RoomDtos.CreateRequest("Other", Language.JAVA));
        assertThat(submit(APPROVED, unrelated.room().id()).statusCode()).isEqualTo(404);
    }
    @Test void authenticatedWebSocketTraversesTlsProxy() throws Exception {
        var frames = new LinkedBlockingQueue<String>();
        var socket = https.newWebSocketBuilder().header("Origin", "https://localhost")
                .buildAsync(URI.create(uri("/ws").toString().replace("https:", "wss:")), new WebSocket.Listener() {
                    final StringBuilder text = new StringBuilder();
                    @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        text.append(data); if (last) { frames.add(text.toString()); text.setLength(0); }
                        ws.request(1); return null;
                    }
                }).get(10, TimeUnit.SECONDS);
        try {
            socket.sendText("CONNECT\naccept-version:1.2\nhost:localhost\nAuthorization:Bearer " + tokens.issue(APPROVED.getId()).accessToken() + "\n\n\0", true).join();
            assertThat(frames.poll(10, TimeUnit.SECONDS)).startsWith("CONNECTED");
        } finally { socket.abort(); }
    }
}
