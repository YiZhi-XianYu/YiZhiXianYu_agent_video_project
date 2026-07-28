package com.yizhixianyu.agentvideo.execution;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TaskRunEntityTest {

    @Test
    void followsTheFirstVerticalSliceStateMachine() {
        var task = new TaskRunEntity(
            "workflow-1", "video_probe", "video.probe", "1.0.0", null
        );

        task.markReady();
        task.markDispatching();
        task.markRunning();
        task.markSucceeded();

        assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(task.getProgress()).isEqualTo(100);
        assertThat(task.getAttempt()).isEqualTo(1);
    }

    @Test
    void keepsThePredecessorForSerialScheduling() {
        var task = new TaskRunEntity(
            "workflow-1", "video_proxy_generate", "video.proxy-generate", "1.0.0", "probe-task"
        );

        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.getDependsOnTaskRunId()).isEqualTo("probe-task");
    }

    @Test
    void canSkipADownstreamTaskAfterAnUpstreamFailure() {
        var task = new TaskRunEntity(
            "workflow-1", "asset-1", "asset-1:shot", "shot", "video.shot-detect", "1.0.0",
            "UPSTREAM_ARTIFACT", "{}"
        );

        task.markSkipped("Upstream failed");

        assertThat(task.getStatus()).isEqualTo(TaskStatus.SKIPPED);
        assertThat(task.getProgress()).isEqualTo(100);
        assertThat(task.getErrorMessage()).isEqualTo("Upstream failed");
    }

    @Test
    void retriesATransportFailureWithTheSameAttempt() {
        var task = new TaskRunEntity(
            "workflow-1", "video_probe", "video.probe", "1.0.0", null
        );
        var now = Instant.now();

        task.markReady();
        task.markDispatching();
        task.scheduleRetry("Connection refused", now, true);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.RETRY_WAIT);
        assertThat(task.getAttempt()).isEqualTo(1);
        assertThat(task.getRetryCount()).isEqualTo(1);
        assertThat(task.releaseRetry(now)).isTrue();
        task.resumeDispatching();
        assertThat(task.getAttempt()).isEqualTo(1);
    }

    @Test
    void retriesAToolFailureAsANewAttempt() {
        var task = new TaskRunEntity(
            "workflow-1", "video_probe", "video.probe", "1.0.0", null
        );
        var now = Instant.now();

        task.markReady();
        task.markDispatching();
        task.markRunning();
        task.scheduleRetry("Temporary ffmpeg failure", now, false);

        assertThat(task.releaseRetry(now)).isFalse();
        task.markDispatching();
        assertThat(task.getAttempt()).isEqualTo(2);
    }

    @Test
    void enforcesTheConfiguredRetryLimit() {
        var task = new TaskRunEntity(
            "workflow-1", "video_probe", "video.probe", "1.0.0", null
        );
        var now = Instant.now();

        task.markReady();
        task.markDispatching();
        task.scheduleRetry("first", now, true);
        task.releaseRetry(now);
        task.resumeDispatching();
        task.scheduleRetry("second", now, true);

        assertThat(task.canRetry(3)).isFalse();
        task.markFailed("third");
        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
    }

    @Test
    void keepsTheStartAndEndOfOversizedToolErrors() {
        var task = new TaskRunEntity(
            "workflow-1", "video_render", "video.render", "1.1.0", null
        );
        var message = "BEGIN-" + "x".repeat(2500) + "-INVALID-ARGUMENT-END";

        task.markReady();
        task.markDispatching();
        task.markRunning();
        task.markFailed(message);

        assertThat(task.getErrorMessage()).hasSize(ErrorMessageFormatter.MAX_LENGTH);
        assertThat(task.getErrorMessage()).startsWith("BEGIN-");
        assertThat(task.getErrorMessage()).contains("...[error truncated]...");
        assertThat(task.getErrorMessage()).endsWith("-INVALID-ARGUMENT-END");
    }

    @Test
    void allowsControlledParameterUpdateOnlyWhileTaskIsInactive() {
        var task = new TaskRunEntity(
            "workflow-1", null, "workflow:bgm", "bgm", "audio.bgm-select", "1.0.0",
            "UPSTREAM_ARTIFACT", "{}"
        );

        task.updateParametersJson("{\"recommendationBatch\":1}");

        assertThat(task.getParametersJson()).contains("recommendationBatch");
    }
}
