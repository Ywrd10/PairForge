package com.pairforge.api.infrastructure;

import com.pairforge.api.auth.AuthProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class ProductionConfiguration {
    @Bean InitializingBean productionBoundary(Environment env, AuthProperties auth) {
        return () -> {
            if (!env.matchesProfiles("observability") || !"127.0.0.1".equals(env.getProperty("server.address"))
                    || !"native".equalsIgnoreCase(env.getProperty("server.forward-headers-strategy"))
                    || !"127\\.0\\.0\\.1".equals(env.getProperty("server.tomcat.remoteip.internal-proxies"))
                    || !"X-Forwarded-For".equals(env.getProperty("server.tomcat.remoteip.remote-ip-header"))
                    || !"X-Forwarded-Proto".equals(env.getProperty("server.tomcat.remoteip.protocol-header"))
                    || auth.allowedOrigins().size() != 1 || !auth.isAllowedOriginsExplicit()
                    || !auth.allowedOrigins().getFirst().startsWith("https://"))
                throw new IllegalStateException("Production requires private listeners and one HTTPS origin through the loopback proxy");
            requireTls(env);
        };
    }
    static void requireTls(Environment env) {
        if (!env.getProperty("spring.datasource.url", "").matches(
                "jdbc:postgresql://[^/?#@]+/[A-Za-z0-9_]+\\?sslmode=verify-full&sslrootcert=/[A-Za-z0-9_./-]+")
                || !env.getProperty("spring.rabbitmq.ssl.enabled", Boolean.class, false)
                || !env.getProperty("spring.rabbitmq.ssl.verify-hostname", Boolean.class, false)
                || !env.getProperty("spring.rabbitmq.ssl.validate-server-certificate", Boolean.class, false)
                || !"broker".equals(env.getProperty("spring.rabbitmq.ssl.bundle")))
            throw new IllegalStateException("Production database and broker require verified TLS");
    }
}
