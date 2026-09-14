package com.pairforge.api.infrastructure;

import org.springframework.boot.autoconfigure.amqp.ConnectionFactoryCustomizer;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RabbitConnectionConfiguration {
    @Bean
    ConnectionFactoryCustomizer rabbitHandshakeTimeout(RabbitProperties properties) {
        // TCP connect timeout does not bound the subsequent AMQP handshake.
        return factory -> factory.setHandshakeTimeout(
                Math.toIntExact(properties.getConnectionTimeout().toMillis()));
    }
}
