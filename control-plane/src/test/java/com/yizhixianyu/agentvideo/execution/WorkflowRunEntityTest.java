package com.yizhixianyu.agentvideo.execution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowRunEntityTest {

    @Test
    void remembersCompletedGatesAcrossResume() {
        var workflow = new WorkflowRunEntity("project-1", "asset-1", "TEST", ProxyQuality.HD_720P);
        workflow.start();
        workflow.pause("gate_render_review");

        workflow.completeCurrentGate();
        workflow.resume();

        assertThat(workflow.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(workflow.getCurrentGateKey()).isNull();
        assertThat(workflow.hasCompletedGate("gate_render_review")).isTrue();
        assertThat(workflow.getCompletedGatesJson()).isEqualTo("[\"gate_render_review\"]");
    }

    @Test
    void canCancelNonTerminalWorkflow() {
        var workflow = new WorkflowRunEntity("project-1", "asset-1", "TEST", ProxyQuality.HD_720P);
        workflow.start();
        workflow.cancel();
        assertThat(workflow.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(workflow.getErrorMessage()).contains("cancelled");
    }

    @Test
    void cannotCancelTerminalWorkflow() {
        var workflow = new WorkflowRunEntity("project-1", "asset-1", "TEST", ProxyQuality.HD_720P);
        workflow.start();
        workflow.succeed();
        org.assertj.core.api.Assertions.assertThatThrownBy(workflow::cancel)
            .isInstanceOf(IllegalStateException.class);
    }
}
