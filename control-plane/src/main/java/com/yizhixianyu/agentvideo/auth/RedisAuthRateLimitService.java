package com.yizhixianyu.agentvideo.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Collections;

/** Atomic fixed-window counters shared by all control-plane instances. */
@Service
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
public class RedisAuthRateLimitService {
    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>("""
        local count = redis.call('INCR', KEYS[1])
        if count == 1 then redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1])) end
        return count
        """, Long.class);

    private final StringRedisTemplate redis;

    public RedisAuthRateLimitService(StringRedisTemplate redis) { this.redis = redis; }

    public long increment(String scope, String identity, Duration window) {
        Long count = redis.execute(INCREMENT_SCRIPT, Collections.singletonList(key(scope, identity)), Long.toString(Math.max(1, window.getSeconds())));
        if (count == null) throw new IllegalStateException("Redis rate-limit increment returned no count");
        return count;
    }

    public long ttlSeconds(String scope, String identity) {
        Long ttl = redis.getExpire(key(scope, identity));
        return ttl == null ? 1 : Math.max(1, ttl);
    }

    public void clear(String scope, String identity) { redis.delete(key(scope, identity)); }

    private String key(String scope, String identity) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = java.util.HexFormat.of().formatHex(digest.digest(identity.getBytes(StandardCharsets.UTF_8)));
            return "avp:v1:rate:auth:" + scope + ":" + hash;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
