package com.yizhixianyu.agentvideo.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPlanSnapshotEntityTest {
    @Test
    void snapshotStartsProposedAndCanBeConfirmedOnce() {
        var snapshot = new AgentPlanSnapshotEntity(
            "session-1", "turn-1", "project-1", "trace-1", "HD_720P", false,
            "制作旅行短片", 30000, "[]", "{}"
        );

        assertThat(snapshot.getStatus()).isEqualTo("PROPOSED");
        snapshot.confirm();
        assertThat(snapshot.getStatus()).isEqualTo("CONFIRMED");
    }
}
