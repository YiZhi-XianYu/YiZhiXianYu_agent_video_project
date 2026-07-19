package com.yizhixianyu.agentvideo.execution;

import org.junit.jupiter.api.Test;

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
}
