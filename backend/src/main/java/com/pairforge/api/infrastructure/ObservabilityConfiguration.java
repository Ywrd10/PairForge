package com.pairforge.api.infrastructure;

import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class ObservabilityConfiguration {
    @Bean MeterFilter boundedHttpRoutes() {
        return MeterFilter.maximumAllowableTags("http.server.requests", "uri", 100, MeterFilter.deny());
    }
    @Bean @Profile("observability") InitializingBean privateManagementListener(Environment environment) {
        return () -> {
            int management = environment.getProperty("management.server.port", Integer.class, -1);
            int application = environment.getProperty("server.port", Integer.class, 8080);
            if (!"127.0.0.1".equals(environment.getProperty("management.server.address"))
                    || management < 0 || management > 65535 || (management != 0 && management == application))
                throw new IllegalStateException("Observability requires a separate loopback management listener");
        };
    }
}
