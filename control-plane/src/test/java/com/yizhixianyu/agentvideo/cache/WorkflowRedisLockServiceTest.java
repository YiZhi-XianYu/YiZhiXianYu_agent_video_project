package com.yizhixianyu.agentvideo.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowRedisLockServiceTest {
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> values;

    @Test
    void rejectsConcurrentAdvanceWhenLeaseExists() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        var service = new WorkflowRedisLockService(redis, 15);

        assertThatThrownBy(() -> service.execute("workflow-1", () -> "never"))
            .isInstanceOf(WorkflowLockBusyException.class);
    }

    @Test
    void failsOpenWhenRedisIsUnavailable() {
        when(redis.opsForValue()).thenThrow(new IllegalStateException("redis down"));
        var service = new WorkflowRedisLockService(redis, 15);

        assertThat(service.execute("workflow-1", () -> "database-lock-path")).isEqualTo("database-lock-path");
    }

    @Test
    void releasesSuccessfulLeaseWithTokenScript() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        var service = new WorkflowRedisLockService(redis, 15);

        assertThat(service.execute("workflow-1", () -> "done")).isEqualTo("done");

        verify(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class), any(java.util.List.class), anyString());
    }
}
