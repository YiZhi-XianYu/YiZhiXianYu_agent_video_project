package com.yizhixianyu.agentvideo.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentSessionEntityTest {

    @Test
    void mapsWorkflowRuntimeToUserReadableSessionStates() {
        var session = new AgentSessionEntity("user-1", "project-1", "制作旅行短片", 30000);
        session.attachWorkflow("workflow-1", "turn-1", "plan-1", 1);

        session.syncRuntime("RUNNING", null);
        assertThat(session.getStatus()).isEqualTo("EXECUTING");
        assertThat(session.getCurrentGateKey()).isNull();

        session.syncRuntime("PAUSED", "gate_story_edit");
        assertThat(session.getStatus()).isEqualTo("WAITING_GATE");
        assertThat(session.getCurrentGateKey()).isEqualTo("gate_story_edit");

        session.syncRuntime("SUCCEEDED", null);
        assertThat(session.getStatus()).isEqualTo("COMPLETED");

        session.syncRuntime("FAILED", null);
        assertThat(session.getStatus()).isEqualTo("FAILED");
    }
}
