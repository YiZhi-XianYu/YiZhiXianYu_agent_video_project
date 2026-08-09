package com.yizhixianyu.agentvideo.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhixianyu.agentvideo.execution.TaskRunRepository;
import com.yizhixianyu.agentvideo.execution.WorkflowRunRepository;
import com.yizhixianyu.agentvideo.trace.AgentTraceEventEntity;
import com.yizhixianyu.agentvideo.trace.AgentTraceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChuxueExplanationServiceTest {
    @Mock AgentTraceService traces;
    @Mock WorkflowRunRepository workflows;
    @Mock TaskRunRepository tasks;

    @Test
    void producesSummaryAndRetainsAuditFields() {
        var event = new AgentTraceEventEntity("TASK_SUCCEEDED", "trace-1", "session-1", "turn-1", "plan-1",
            "workflow-1", "task-1", null, "execution-1", "chuxue", "video.render", "SUCCEEDED", "{\"worker\":\"render-1\"}");
        when(traces.byWorkflow("workflow-1")).thenReturn(List.of(event));
        var explanation = new ChuxueExplanationService(traces, workflows, tasks, new ObjectMapper()).byWorkflow("workflow-1");
        assertThat(explanation.userSummary()).anyMatch(item -> item.contains("Worker"));
        assertThat(explanation.audit()).hasSize(1);
        assertThat(explanation.audit().get(0).traceId()).isEqualTo("trace-1");
        assertThat(explanation.audit().get(0).payload()).containsEntry("worker", "render-1");
    }
}
