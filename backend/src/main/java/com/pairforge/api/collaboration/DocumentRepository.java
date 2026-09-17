package com.pairforge.api.collaboration;

import com.pairforge.api.common.Language;
import com.pairforge.api.collaboration.DocumentMessages.Document;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class DocumentRepository {
    // Initialization/read/TTL refresh and content/version/language updates are indivisible.
    private static final DefaultRedisScript<List> STATE = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local created = '0'
            if redis.call('EXISTS', key) == 0 then
                if ARGV[1] == 'update' then return {'MISSING'} end
                redis.call('HSET', key, 'content', ARGV[3], 'language', ARGV[4], 'version', '0', 'generation', ARGV[5])
                created = '1'
            end
            if ARGV[1] == 'update' then
                if redis.call('HGET', key, 'generation') ~= ARGV[5] then return {'STALE'} end
                redis.call('HSET', key, 'content', ARGV[3], 'language', ARGV[4])
                redis.call('HINCRBY', key, 'version', 1)
            end
            redis.call('EXPIRE', key, ARGV[2])
            return {'OK', created, redis.call('HGET', key, 'content'), redis.call('HGET', key, 'language'),
                redis.call('HGET', key, 'version'), redis.call('HGET', key, 'generation')}
            """, List.class);
    private final StringRedisTemplate redis;
    private final CollaborationProperties properties;
    public DocumentRepository(StringRedisTemplate redis, CollaborationProperties properties) {
        this.redis = redis; this.properties = properties;
    }
    public record Result(Document document, boolean created, String outcome) {}
    public Result snapshot(UUID room, Language language) {
        return execute(room, "snapshot", DocumentMessages.template(language), language, UUID.randomUUID());
    }
    public Result update(UUID room, DocumentMessages.Update update) {
        return execute(room, "update", update.content(), update.language(), update.generationId());
    }
    private Result execute(UUID room, String operation, String content, Language language, UUID generation) {
        List<?> values = redis.execute(STATE, List.of(key(room)), operation,
                Integer.toString(properties.ttlSeconds()), content, language.name(), generation.toString());
        if (values == null || values.isEmpty()) throw new IllegalStateException("Redis returned no document result");
        String outcome = values.get(0).toString();
        if (!outcome.equals("OK")) return new Result(null, false, outcome);
        return new Result(new Document(values.get(2).toString(), Language.valueOf(values.get(3).toString()),
                Long.parseLong(values.get(4).toString()), UUID.fromString(values.get(5).toString())),
                values.get(1).equals("1"), outcome);
    }
    static String key(UUID room) { return "room:{" + room + "}:document"; }
}
