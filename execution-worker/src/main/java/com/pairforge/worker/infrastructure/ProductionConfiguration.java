package com.pairforge.worker.infrastructure;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class ProductionConfiguration {
    @Bean InitializingBean productionBoundary(Environment env) {
        return () -> {
            if (!env.matchesProfiles("observability") || !env.matchesProfiles("sandbox")
                    || !"127.0.0.1".equals(env.getProperty("server.address"))
                    || !env.getProperty("JWT_KEY_HEX", "").isBlank()
                    || !env.getProperty("MIGRATION_PASSWORD", "").isBlank()
                    || !env.getProperty("POSTGRES_PASSWORD", "").isBlank())
                throw new IllegalStateException("Production worker requires private management, sandbox opt-in and isolated credentials");
            if (!env.getProperty("spring.datasource.url", "").matches(
                    "jdbc:postgresql://[^/?#@]+/[A-Za-z0-9_]+\\?sslmode=verify-full&sslrootcert=/[A-Za-z0-9_./-]+")
                    || !env.getProperty("spring.rabbitmq.ssl.enabled", Boolean.class, false)
                    || !env.getProperty("spring.rabbitmq.ssl.verify-hostname", Boolean.class, false)
                    || !env.getProperty("spring.rabbitmq.ssl.validate-server-certificate", Boolean.class, false)
                    || !"broker".equals(env.getProperty("spring.rabbitmq.ssl.bundle")))
                throw new IllegalStateException("Production database and broker require verified TLS");
        };
    }
}
