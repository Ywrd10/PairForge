package com.pairforge.api.infrastructure;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.assertj.core.api.Assertions.*;
class ObservabilityConfigurationTest {
    @Test void rejectsPublicOrSharedManagementListener() throws Exception {
        var config = new ObservabilityConfiguration();
        var env = new MockEnvironment().withProperty("management.server.port", "8082")
                .withProperty("server.port", "8080").withProperty("management.server.address", "0.0.0.0");
        assertThatThrownBy(() -> config.privateManagementListener(env).afterPropertiesSet()).isInstanceOf(IllegalStateException.class);
        env.setProperty("management.server.address", "127.0.0.1");
        config.privateManagementListener(env).afterPropertiesSet();
        env.setProperty("management.server.port", "8080");
        assertThatThrownBy(() -> config.privateManagementListener(env).afterPropertiesSet()).isInstanceOf(IllegalStateException.class);
    }
    @Test void capsHttpRouteCardinality() {
        var registry = new SimpleMeterRegistry();
        try {
            registry.config().meterFilter(new ObservabilityConfiguration().boundedHttpRoutes());
            for (int i=0; i<150; i++) registry.timer("http.server.requests", "uri", "/fixture/" + i);
            assertThat(registry.getMeters()).hasSize(100);
        } finally { registry.close(); }
    }
}
