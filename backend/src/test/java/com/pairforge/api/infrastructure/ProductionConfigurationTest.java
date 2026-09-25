package com.pairforge.api.infrastructure;

import com.pairforge.api.auth.AuthProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class ProductionConfigurationTest {
    MockEnvironment environment() {
        var e = new MockEnvironment(); e.setActiveProfiles("prod", "observability");
        return e.withProperty("server.address", "127.0.0.1")
                .withProperty("server.forward-headers-strategy", "native")
                .withProperty("server.tomcat.remoteip.internal-proxies", "127\\.0\\.0\\.1")
                .withProperty("server.tomcat.remoteip.remote-ip-header", "X-Forwarded-For")
                .withProperty("server.tomcat.remoteip.protocol-header", "X-Forwarded-Proto")
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.internal/pairforge?sslmode=verify-full&sslrootcert=/etc/pairforge/ca.crt")
                .withProperty("spring.rabbitmq.ssl.enabled", "true")
                .withProperty("spring.rabbitmq.ssl.verify-hostname", "true")
                .withProperty("spring.rabbitmq.ssl.validate-server-certificate", "true")
                .withProperty("spring.rabbitmq.ssl.bundle", "broker");
    }
    AuthProperties auth(String origin) {
        return new AuthProperties("unused", "issuer", "audience", 900, 12, List.of(origin), 20, 10, 5, 60, 900, 3600);
    }
    @Test void acceptsPrivateVerifiedDeployment() {
        assertThatCode(() -> new ProductionConfiguration().productionBoundary(environment(), auth("https://pairforge.example.com")).afterPropertiesSet()).doesNotThrowAnyException();
    }
    @Test void refusesUnsafeBindingsProxyTrustOriginsAndTlsDowngrades() {
        var bad = java.util.Map.of(
                "server.address", "0.0.0.0",
                "server.forward-headers-strategy", "framework",
                "server.tomcat.remoteip.internal-proxies", ".*",
                "server.tomcat.remoteip.remote-ip-header", "Forwarded",
                "spring.datasource.url", "jdbc:postgresql://db.internal/pairforge?sslmode=require",
                "spring.rabbitmq.ssl.enabled", "false",
                "spring.rabbitmq.ssl.verify-hostname", "false",
                "spring.rabbitmq.ssl.validate-server-certificate", "false",
                "spring.rabbitmq.ssl.bundle", "");
        bad.forEach((key, value) -> assertThatThrownBy(() -> new ProductionConfiguration()
                .productionBoundary(environment().withProperty(key, value), auth("https://pairforge.example.com"))
                .afterPropertiesSet()).as(key).isInstanceOf(IllegalStateException.class));
        assertThatThrownBy(() -> new ProductionConfiguration().productionBoundary(environment(), auth("http://localhost:5173"))
                .afterPropertiesSet()).isInstanceOf(IllegalStateException.class);
        var e = environment(); e.setActiveProfiles("prod");
        assertThatThrownBy(() -> new ProductionConfiguration().productionBoundary(e, auth("https://pairforge.example.com"))
                .afterPropertiesSet()).isInstanceOf(IllegalStateException.class);
    }
}
