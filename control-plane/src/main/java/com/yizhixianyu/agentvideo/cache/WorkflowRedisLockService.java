package com.yizhixianyu.agentvideo.cache;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.function.Supplier;

/** Best-effort Redis lease used to reduce concurrent workflow advancement. */
@Service
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
public class WorkflowRedisLockService {
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) == ARGV[1] then
          return redis.call('DEL', KEYS[1])
        end
        return 0
        """, Long.class);
    private final StringRedisTemplate redis;
    private final Duration ttl;

    public WorkflowRedisLockService(
        StringRedisTemplate redis,
        @org.springframework.beans.factory.annotation.Value("${app.redis.workflow-lock-ttl-seconds:15}") long ttlSeconds
    ) {
        this.redis = redis;
        this.ttl = Duration.ofSeconds(Math.max(2, ttlSeconds));
    }

    public <T> T execute(String workflowRunId, Supplier<T> action) {
        String key = "avp:v1:workflow:lock:" + workflowRunId;
        String token = UUID.randomUUID().toString();
        boolean acquired;
        try {
            acquired = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, ttl));
        } catch (RuntimeException unavailable) {
            // Redis is an optimization; DB row locks remain the correctness boundary.
            return action.get();
        }
        if (!acquired) throw new WorkflowLockBusyException(workflowRunId);
        try {
            return action.get();
        } finally {
            try { redis.execute(RELEASE_SCRIPT, Collections.singletonList(key), token); }
            catch (RuntimeException ignored) { }
        }
    }

    public String key(String workflowRunId) {
        return "avp:v1:workflow:lock:" + workflowRunId;
    }
}
