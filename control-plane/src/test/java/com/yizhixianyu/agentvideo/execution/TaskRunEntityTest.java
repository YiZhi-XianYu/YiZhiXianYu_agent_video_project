package com.yizhixianyu.agentvideo.execution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskRunEntityTest {

    @Test
    void followsTheFirstVerticalSliceStateMachine() {
        var task = new TaskRunEntity("workflow-1");

        task.markReady();
        task.markDispatching();
        task.markRunning();
        task.markSucceeded();

        assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(task.getProgress()).isEqualTo(100);
        assertThat(task.getAttempt()).isEqualTo(1);
    }
}

