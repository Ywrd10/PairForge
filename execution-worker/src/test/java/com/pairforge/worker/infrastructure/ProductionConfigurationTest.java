package com.pairforge.worker.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class ProductionConfigurationTest {
    MockEnvironment environment() {
        var e = new MockEnvironment(); e.setActiveProfiles("prod", "sandbox", "observability");
        return e.withProperty("server.address", "127.0.0.1")
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.internal/pairforge?sslmode=verify-full&sslrootcert=/etc/pairforge/ca.crt")
                .withProperty("spring.rabbitmq.ssl.enabled", "true")
                .withProperty("spring.rabbitmq.ssl.verify-hostname", "true")
                .withProperty("spring.rabbitmq.ssl.validate-server-certificate", "true")
                .withProperty("spring.rabbitmq.ssl.bundle", "broker");
    }
    @Test void acceptsPrivateWorkerAndRejectsInheritedCredentialsAndUnsafeTls() {
        var config = new ProductionConfiguration();
        assertThatCode(() -> config.productionBoundary(environment()).afterPropertiesSet()).doesNotThrowAnyException();
        java.util.Map.of("server.address", "0.0.0.0", "JWT_KEY_HEX", "forbidden", "MIGRATION_PASSWORD", "forbidden",
                "POSTGRES_PASSWORD", "forbidden", "spring.datasource.url", "jdbc:postgresql://db.internal/pairforge",
                "spring.rabbitmq.ssl.enabled", "false", "spring.rabbitmq.ssl.verify-hostname", "false",
                "spring.rabbitmq.ssl.validate-server-certificate", "false", "spring.rabbitmq.ssl.bundle", "")
                .forEach((key, value) -> assertThatThrownBy(() -> config.productionBoundary(environment().withProperty(key, value))
                        .afterPropertiesSet()).as(key).isInstanceOf(IllegalStateException.class));
        var e = environment(); e.setActiveProfiles("prod", "observability");
        assertThatThrownBy(() -> config.productionBoundary(e).afterPropertiesSet()).isInstanceOf(IllegalStateException.class);
    }
}
