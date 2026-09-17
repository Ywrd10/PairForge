package com.pairforge.api.execution;

import com.pairforge.api.common.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataAccessException;

@Component
@ConditionalOnWebApplication
public class ExecutionAdmission {
    private static final DefaultRedisScript<Long> LIMIT = new DefaultRedisScript<>("""
            local count = tonumber(redis.call('GET', KEYS[1]) or '0')
            if count >= tonumber(ARGV[1]) then
                local ttl = redis.call('PTTL', KEYS[1])
                if ttl < 0 then redis.call('PEXPIRE', KEYS[1], ARGV[2]); ttl = tonumber(ARGV[2]) end
                return math.max(ttl, 1)
            end
            if redis.call('INCR', KEYS[1]) == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
            return 0
            """, Long.class);
    private final StringRedisTemplate redis;
    private final ExecutionProperties properties;
    public ExecutionAdmission(StringRedisTemplate redis, ExecutionProperties properties) { this.redis = redis; this.properties = properties; }
    public void check(UUID user) {
        final Long retry;
        try {
            retry = redis.execute(LIMIT, List.of("execution:submission:" + user),
                    Integer.toString(properties.userLimit()), Long.toString(properties.windowSeconds() * 1000L));
        } catch (DataAccessException error) { throw unavailable(); }
        if (retry == null) throw unavailable();
        if (retry > 0) throw new ApiException(429, "RATE_LIMITED", "Too many execution submissions", (retry + 999) / 1000);
    }
    private static ApiException unavailable() { return new ApiException(503, "DEPENDENCY_UNAVAILABLE", "Execution admission is unavailable"); }
}
