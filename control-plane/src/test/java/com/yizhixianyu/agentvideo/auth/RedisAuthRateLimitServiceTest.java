package com.yizhixianyu.agentvideo.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisAuthRateLimitServiceTest {
    @Mock StringRedisTemplate redis;

    @Test
    void incrementsUsingAtomicScriptAndHashesIdentity() {
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class), any(java.util.List.class), anyString()))
            .thenReturn(1L);
        var service = new RedisAuthRateLimitService(redis);

        assertThat(service.increment("login", "198.51.100.10:user@example.com", Duration.ofMinutes(15))).isEqualTo(1);
    }
}
