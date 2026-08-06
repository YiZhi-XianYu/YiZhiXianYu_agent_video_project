package com.yizhixianyu.agentvideo.cache;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

/** Redis is intentionally best-effort: callers must retain a database/memory fallback. */
@Service
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
public class RedisDraftService {
    private static final DefaultRedisScript<Long> SAVE_SCRIPT = new DefaultRedisScript<>("""
        local current = redis.call('HGET', KEYS[1], 'revision')
        local expected = ARGV[1]
        if expected ~= '' and current ~= false and tonumber(current) ~= tonumber(expected) then
          return -1
        end
        if expected ~= '' and current == false and tonumber(expected) ~= 0 then
          return -1
        end
        local next = (current == false) and 1 or (tonumber(current) + 1)
        redis.call('HSET', KEYS[1], 'revision', tostring(next), 'payload', ARGV[2])
        redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
        return next
        """, Long.class);
    private final StringRedisTemplate redis;
    public RedisDraftService(StringRedisTemplate redis) { this.redis = redis; }
    public StoredDraft save(String key, String json, Duration ttl, Long expectedRevision) {
        long ttlSeconds = Math.max(1, ttl.getSeconds());
        String expected = expectedRevision == null ? "" : Long.toString(Math.max(0, expectedRevision));
        Long revision = redis.execute(SAVE_SCRIPT, Collections.singletonList(key), expected, json, Long.toString(ttlSeconds));
        if (revision == null) throw new IllegalStateException("Redis CAS returned no revision");
        if (revision < 0) throw new DraftConflictException("Draft revision is stale; reload before saving");
        return StoredDraft.expiringIn(revision, json, ttlSeconds);
    }
    public StoredDraft save(String key, String json, Duration ttl) {
        return save(key, json, ttl, null);
    }
    public StoredDraft get(String key) {
        Map<Object, Object> fields = redis.opsForHash().entries(key);
        if (fields == null || fields.isEmpty()) return null;
        Object revision = fields.get("revision"); Object payload = fields.get("payload");
        if (revision == null || payload == null) return null;
        Long ttl = redis.getExpire(key);
        if (ttl == null || ttl < 0) return null;
        return StoredDraft.expiringIn(Long.parseLong(String.valueOf(revision)), String.valueOf(payload), ttl);
    }
    public void delete(String key) { redis.delete(key); }
    /** Deletes matching keys without issuing the blocking KEYS command. */
    public void deleteByPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) return;
        List<String> keys = redis.execute(connection -> {
            List<String> found = new ArrayList<>();
            try (var cursor = connection.scan(ScanOptions.scanOptions().match(prefix + "*").count(100).build())) {
                cursor.forEachRemaining(key -> found.add(new String(key, java.nio.charset.StandardCharsets.UTF_8)));
            } catch (java.io.IOException ex) {
                throw new IllegalStateException("Redis scan failed", ex);
            }
            return found;
        });
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }
}
