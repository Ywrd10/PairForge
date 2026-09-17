package com.pairforge.api.collaboration;

import com.pairforge.api.auth.AuthProperties;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.cfg.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.*;
import org.springframework.messaging.simp.config.*;
import org.springframework.messaging.converter.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Configuration
// Override Boot's channel bindings (order 0) and prepend our strict JSON converter.
@org.springframework.core.annotation.Order(1)
@EnableWebSocketMessageBroker
@EnableScheduling
@EnableConfigurationProperties(CollaborationProperties.class)
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class CollaborationConfiguration implements WebSocketMessageBrokerConfigurer {
    private final CollaborationSecurity security;
    private final CollaborationSessions sessions;
    private final CollaborationProperties properties;
    private final AuthProperties auth;
    public CollaborationConfiguration(CollaborationSecurity security, CollaborationSessions sessions,
            CollaborationProperties properties, AuthProperties auth) {
        this.security = security; this.sessions = sessions; this.properties = properties; this.auth = auth;
    }
    @Bean public ThreadPoolTaskScheduler collaborationScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2); scheduler.setThreadNamePrefix("collaboration-"); scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
    @Bean public ThreadPoolTaskExecutor collaborationInboundExecutor() { return fifoExecutor("collaboration-in-"); }
    @Bean public ThreadPoolTaskExecutor collaborationOutboundExecutor() { return fifoExecutor("collaboration-out-"); }
    private static ThreadPoolTaskExecutor fifoExecutor(String prefix) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); executor.setMaxPoolSize(1); executor.setQueueCapacity(64);
        executor.setThreadNamePrefix(prefix);
        return executor;
    }
    @Override public void registerStompEndpoints(StompEndpointRegistry registry) {
        // A single bounded FIFO executor preserves order without an unbounded
        // OrderedMessageChannelDecorator queue per connection.
        registry.setErrorHandler(new StompSubProtocolErrorHandler() {
            @Override public org.springframework.messaging.Message<byte[]> handleClientMessageProcessingError(
                    org.springframework.messaging.Message<byte[]> message, Throwable error) {
                org.slf4j.LoggerFactory.getLogger(CollaborationConfiguration.class)
                        .warn("Collaboration protocol rejected type={}", error.getClass().getSimpleName());
                var headers = StompHeaderAccessor.create(StompCommand.ERROR);
                headers.setMessage("Collaboration request rejected");
                return org.springframework.messaging.support.MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
            }
        });
        registry.addEndpoint("/ws").setAllowedOrigins(auth.allowedOrigins().toArray(String[]::new))
                .addInterceptors(new HandshakeInterceptor() {
                    @Override public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                            WebSocketHandler handler, Map<String, Object> attributes) {
                        boolean allowed = request.getURI().getRawQuery() == null
                                && auth.allowedOrigins().contains(request.getHeaders().getOrigin());
                        if (!allowed) response.setStatusCode(HttpStatus.FORBIDDEN);
                        return allowed;
                    }
                    @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                            WebSocketHandler handler, Exception error) {}
                });
    }
    @Override public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.enableSimpleBroker("/topic", "/queue").setHeartbeatValue(new long[]{10000, 10000})
                .setTaskScheduler(collaborationScheduler());
    }
    @Override public boolean configureMessageConverters(List<MessageConverter> converters) {
        // Only STOMP JSON uses this mapper; REST serialization remains unchanged.
        var mapper = JsonMapper.builder().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT, DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
        mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        var converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(mapper); converters.add(0, converter);
        return false;
    }
    @Override public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(security);
        registration.executor(collaborationInboundExecutor());
    }
    @Override public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(new org.springframework.messaging.support.ExecutorChannelInterceptor() {
            @Override public org.springframework.messaging.Message<?> beforeHandle(org.springframework.messaging.Message<?> message,
                    org.springframework.messaging.MessageChannel channel, org.springframework.messaging.MessageHandler handler) {
                // A queued event can outlive the JWT after preSend has admitted it.
                return preSend(message, channel);
            }
            @Override public org.springframework.messaging.Message<?> preSend(org.springframework.messaging.Message<?> message,
                    org.springframework.messaging.MessageChannel channel) {
                var headers = org.springframework.messaging.simp.SimpMessageHeaderAccessor.wrap(message);
                if (headers.getMessageType() == org.springframework.messaging.simp.SimpMessageType.MESSAGE) {
                    try { if (sessions.require(headers.getSessionId()).user == null) return null; }
                    catch (IllegalArgumentException denied) { return null; }
                }
                return message;
            }
        });
        registration.executor(collaborationOutboundExecutor());
    }
    @Override public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
        registry.setMessageSizeLimit(properties.messageBytes()).setSendBufferSizeLimit(properties.sendBufferBytes())
                .setSendTimeLimit(properties.sendTimeoutMs()).setTimeToFirstMessage(properties.connectTimeoutMs());
        registry.addDecoratorFactory(handler -> new WebSocketHandlerDecorator(handler) {
            @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                if (!sessions.open(session)) { session.close(CloseStatus.POLICY_VIOLATION); return; }
                try {
                    super.afterConnectionEstablished(session);
                    session.setTextMessageSizeLimit(properties.messageBytes());
                    session.setBinaryMessageSizeLimit(properties.messageBytes());
                    if (session instanceof org.springframework.web.socket.adapter.standard.StandardWebSocketSession standard) {
                        standard.getNativeSession().setMaxIdleTimeout(30000);
                        standard.getNativeSession().getAsyncRemote().setSendTimeout(properties.sendTimeoutMs());
                        // Tomcat's BasicRemote send is blocking; bound it as well as Spring's send buffer.
                        standard.getNativeSession().getUserProperties().put("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT",
                                (long) properties.sendTimeoutMs());
                    }
                }
                catch (Exception failure) { sessions.close(session.getId()); throw failure; }
            }
            @Override public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
                try { sessions.charge(session.getId(), true); }
                catch (IllegalArgumentException failure) { sessions.close(session.getId()); return; }
                super.handleMessage(session, message);
            }
            @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                sessions.remove(session.getId()); super.afterConnectionClosed(session, status);
            }
        });
    }
}
