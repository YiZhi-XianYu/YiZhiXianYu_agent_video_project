package com.yizhixianyu.agentvideo.cache;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.Set;

/** Redis is intentionally best-effort: callers must retain a database/memory fallback. */
@Service
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
public class RedisDraftService {
    private final StringRedisTemplate redis;
    public RedisDraftService(StringRedisTemplate redis) { this.redis = redis; }
    public void save(String key, String json, Duration ttl) { redis.opsForValue().set(key, json, ttl); }
    public String get(String key) { return redis.opsForValue().get(key); }
    public void delete(String key) { redis.delete(key); }
    public void deleteByPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) return;
        Set<String> keys = redis.keys(prefix + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }
}
