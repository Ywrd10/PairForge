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
    public static final String EVENTS = "execution.events";
    @Bean Declarables executionTopology() {
        var exchange = new DirectExchange(EXCHANGE, true, false);
        var queue = new Queue(JOBS, true, false, false);
        var events = new Queue(EVENTS, true, false, false);
        return new Declarables(exchange, queue, BindingBuilder.bind(queue).to(exchange).with(JOBS),
                events, BindingBuilder.bind(events).to(exchange).with(EVENTS));
    }
    // Lazy connection listener redeclares the durable topology on connection recovery.
    // Boot's general dynamic declaration is disabled; only the API owns this topology.
    @Bean RabbitAdmin executionRabbitAdmin(ConnectionFactory factory) { return new RabbitAdmin(factory); }
    @Bean org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer executionEventListener(
            ConnectionFactory factory, ExecutionEventConsumer listener, RabbitAdmin executionRabbitAdmin) {
        var container = new org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer(factory);
        container.setQueueNames(EVENTS);
        container.setAmqpAdmin(executionRabbitAdmin);
        container.setMissingQueuesFatal(false);
        container.setConcurrentConsumers(1); container.setMaxConcurrentConsumers(1); container.setPrefetchCount(1);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setDefaultRequeueRejected(false);
        container.setMessageListener(listener);
        return container;
    }
    @Bean org.springframework.boot.actuate.health.HealthIndicator executionEvents(
            org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer executionEventListener) {
        return () -> executionEventListener.isRunning() && executionEventListener.getActiveConsumerCount() > 0
                ? org.springframework.boot.actuate.health.Health.up().build() : org.springframework.boot.actuate.health.Health.down().build();
    }
}
