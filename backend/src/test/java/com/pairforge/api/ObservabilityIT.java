package com.pairforge.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@org.springframework.test.context.ActiveProfiles("observability")
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class ObservabilityIT {
    private static final String BROKER_USER = "pairforge_test";
    private static final String BROKER_PASSWORD = UUID.randomUUID().toString();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("pairforge_test")
            .withUsername("pairforge_test")
            .withPassword(UUID.randomUUID().toString());

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1.8")
            .withAdminUser(BROKER_USER)
            .withAdminPassword(BROKER_PASSWORD);

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.11-bookworm")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("pairforge.auth.key-hex", () -> UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", ""));
        registry.add("pairforge.auth.bcrypt-cost", () -> 4);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", () -> BROKER_USER);
        registry.add("spring.rabbitmq.password", () -> BROKER_PASSWORD);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @LocalServerPort int port;
    @org.springframework.boot.test.web.server.LocalManagementPort int managementPort;
    @Autowired org.springframework.core.env.Environment environment;
    @Autowired io.micrometer.core.instrument.MeterRegistry metrics;
    @Autowired com.pairforge.api.auth.TokenService tokens;
    private HttpResponse<String> get(int target, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + target + path))
                .timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void scrapesOnlyOnSeparateLoopbackListener() throws Exception {
        assertThat(managementPort).isNotEqualTo(port);
        assertThat(environment.getProperty("management.server.address")).isEqualTo("127.0.0.1");
        var scrape = get(managementPort, "/actuator/prometheus");
        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.body()).contains("jvm_memory_used_bytes", "application=\"pairforge-backend\"");
        assertThat(get(port, "/actuator/prometheus").statusCode()).isEqualTo(401);
        var publicScrape = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/prometheus"))
                .header("Authorization", "Bearer " + tokens.issue(UUID.randomUUID()).accessToken())
                .header("X-Forwarded-Port", Integer.toString(managementPort)).GET().build();
        assertThat(HTTP.send(publicScrape, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        for (String path : java.util.List.of("env", "configprops", "heapdump", "beans", "metrics", "loggers", "shutdown"))
            assertThat(get(managementPort, "/actuator/" + path).statusCode()).isEqualTo(401);
    }
    @Test void metricsRemainAvailableDuringDatabaseLossWithoutChangingLiveness() {
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> assertThat(get(managementPort, "/actuator/health/readiness").statusCode()).isEqualTo(200));
        POSTGRES.getDockerClient().pauseContainerCmd(POSTGRES.getContainerId()).exec();
        try {
            await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
                assertThat(get(managementPort, "/actuator/health/readiness").statusCode()).isEqualTo(503);
                assertThat(get(managementPort, "/actuator/health/liveness").statusCode()).isEqualTo(200);
                assertThat(get(managementPort, "/actuator/prometheus").statusCode()).isEqualTo(200);
            });
        } finally {
            POSTGRES.getDockerClient().unpauseContainerCmd(POSTGRES.getContainerId()).exec();
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(get(managementPort, "/actuator/health/readiness").statusCode()).isEqualTo(200));
        }
    }
    @Test void httpLabelsUseTemplatesAndLogsExcludeRequestSecrets(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        String sentinel = "never-log-" + UUID.randomUUID();
        for (int i=0; i<4; i++) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/rooms/" + UUID.randomUUID()))
                    .header("Authorization", "Bearer " + sentinel).GET().build();
            assertThat(HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        }
        assertThat(get(managementPort, "/actuator/prometheus").body()).contains("http_server_requests_seconds_count").doesNotContain(sentinel);
        assertThat(output.getAll()).doesNotContain(sentinel, BROKER_PASSWORD, POSTGRES.getPassword());
        assertThat(metrics.get("http.server.requests").timers()).allSatisfy(timer ->
                assertThat(timer.getId().getTag("uri")).doesNotMatch(".*[a-f0-9]{8}-[a-f0-9]{4}.*"));
    }
}
