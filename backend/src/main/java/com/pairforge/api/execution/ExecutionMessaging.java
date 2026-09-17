package com.pairforge.api.execution;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
@EnableConfigurationProperties(ExecutionProperties.class)
public class ExecutionMessaging {
    public static final String EXCHANGE = "pairforge.execution";
    public static final String JOBS = "execution.jobs";
    @Bean Declarables executionTopology() {
        var exchange = new DirectExchange(EXCHANGE, true, false);
        var queue = new Queue(JOBS, true, false, false);
        return new Declarables(exchange, queue, BindingBuilder.bind(queue).to(exchange).with(JOBS));
    }
    // Lazy connection listener redeclares the durable topology on connection recovery.
    // Boot's general dynamic declaration is disabled; only the API owns this topology.
    @Bean RabbitAdmin executionRabbitAdmin(ConnectionFactory factory) { return new RabbitAdmin(factory); }
}
