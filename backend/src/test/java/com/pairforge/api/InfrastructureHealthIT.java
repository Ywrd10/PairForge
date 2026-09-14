package com.pairforge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InfrastructureHealthIT {
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

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    DataSource dataSource;

    @Test
    void reportsHealthyWithoutExposingConnectionDetails() throws Exception {
        assertProbe("/actuator/health", 200, "UP");
        assertProbe("/actuator/health/readiness", 200, "UP");
        assertProbe("/actuator/health/liveness", 200, "UP");
        JsonNode body = mapper.readTree(get("/actuator/health").body());
        assertThat(body.has("components")).isFalse();
        assertThat(body.has("details")).isFalse();
    }

    @Test
    void createsNoProductSchema() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery(
                     "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"env", "configprops", "heapdump", "beans", "metrics", "loggers", "shutdown"})
    void doesNotExposeOtherActuatorEndpoints(String endpoint) throws Exception {
        assertThat(get("/actuator/" + endpoint).statusCode()).isEqualTo(404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgres", "rabbit", "redis"})
    void readinessTracksDependencyOutageAndRecoveryWhileLivenessStaysUp(String dependency) {
        GenericContainer<?> container = switch (dependency) {
            case "postgres" -> POSTGRES;
            case "rabbit" -> RABBIT;
            case "redis" -> REDIS;
            default -> throw new IllegalArgumentException(dependency);
        };
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() ->
                assertProbe("/actuator/health/readiness", 200, "UP"));
        // Freeze the dependency without changing its randomly assigned host port.
        // The Compose smoke check separately covers full stop/start recovery.
        container.getDockerClient().pauseContainerCmd(container.getContainerId()).exec();
        try {
            await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
                assertProbe("/actuator/health/readiness", 503, "DOWN");
                assertProbe("/actuator/health/liveness", 200, "UP");
            });
        } finally {
            container.getDockerClient().unpauseContainerCmd(container.getContainerId()).exec();
            await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() ->
                    assertProbe("/actuator/health/readiness", 200, "UP"));
        }
    }

    private void assertProbe(String path, int expectedCode, String expectedStatus) throws Exception {
        var response = get(path);
        assertThat(response.statusCode()).as(path).isEqualTo(expectedCode);
        assertThat(mapper.readTree(response.body()).path("status").asText()).isEqualTo(expectedStatus);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(8)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
