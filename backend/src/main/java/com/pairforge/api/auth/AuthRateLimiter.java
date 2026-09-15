package com.pairforge.api.auth;

import com.pairforge.api.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class AuthRateLimiter {
    private static final DefaultRedisScript<Long> LIMIT = new DefaultRedisScript<>("""
            local retry = 0
            for i, key in ipairs(KEYS) do
                local count = tonumber(redis.call('GET', key) or '0')
                if count >= tonumber(ARGV[2*i-1]) then
                    local ttl = redis.call('PTTL', key)
                    if ttl < 0 then
                        redis.call('PEXPIRE', key, ARGV[2*i])
                        ttl = tonumber(ARGV[2*i])
                    end
                    retry = math.max(retry, ttl, 1)
                end
            end
            if retry > 0 then return retry end
            for i, key in ipairs(KEYS) do
                local count = redis.call('INCR', key)
                if count == 1 then redis.call('PEXPIRE', key, ARGV[2*i]) end
            end
            return 0
            """, Long.class);
    private final StringRedisTemplate redis;
    private final AuthProperties properties;
    private final SecretKey key;
    public AuthRateLimiter(StringRedisTemplate redis, AuthProperties properties, SecretKey key) {
        this.redis = redis;
        this.properties = properties;
        this.key = key;
    }
    public void login(String ip, String normalizedEmail) {
        check(List.of("auth:login:ip:" + digest(ip), "auth:login:account:" + digest(normalizedEmail)),
                properties.loginIpLimit(), properties.ipWindowSeconds(),
                properties.loginAccountLimit(), properties.accountWindowSeconds());
    }
    public void register(String ip) {
        check(List.of("auth:register:ip:" + digest(ip)),
                properties.registrationIpLimit(), properties.registrationWindowSeconds());
    }
    private void check(List<String> keys, int... limitsAndWindows) {
        Object[] args = new Object[limitsAndWindows.length];
        for (int i = 0; i < args.length; i++) {
            args[i] = Long.toString(i % 2 == 0 ? limitsAndWindows[i] : limitsAndWindows[i] * 1000L);
        }
        final Long retryMs;
        try {
            retryMs = redis.execute(LIMIT, keys, args);
        } catch (DataAccessException error) {
            throw new ApiException(503, "DEPENDENCY_UNAVAILABLE", "Authentication admission is unavailable");
        }
        if (retryMs == null) throw new ApiException(503, "DEPENDENCY_UNAVAILABLE", "Authentication admission is unavailable");
        if (retryMs > 0) throw new ApiException(429, "RATE_LIMITED", "Too many authentication attempts",
                Math.max(1, (retryMs + 999) / 1000));
    }
    private String digest(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(
                    ("pairforge-auth-rate-limit:" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Authentication key initialization failed", error);
        }
    }
}
