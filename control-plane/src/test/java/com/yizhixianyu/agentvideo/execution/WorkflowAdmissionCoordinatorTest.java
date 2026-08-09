package com.yizhixianyu.agentvideo.execution;

import com.yizhixianyu.agentvideo.workflow.WorkflowDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowAdmissionCoordinatorTest {
    @Mock WorkflowConcurrencyService concurrency;
    @Mock WorkflowExecutionService workflows;

    @Test
    void releasesRealLeaseWhenWorkflowFinishesDuringCreation() {
        var workflow = new WorkflowRunEntity("project-1", null, "MULTI_ASSET_ANALYSIS", ProxyQuality.FHD_1080P);
        workflow.succeed();
        var definition = new WorkflowDefinition("test", 1, List.of(), List.of(), List.of());
        when(workflows.createMultiAssetAnalysisRun(eq("project-1"), any(), eq(ProxyQuality.FHD_1080P),
            eq("10.0 seconds"), eq(true), eq(definition))).thenReturn(workflow);

        var coordinator = new WorkflowAdmissionCoordinator(concurrency, workflows);
        coordinator.createMultiAssetAnalysisRun("project-1", List.of("asset-1"), ProxyQuality.FHD_1080P,
            "10.0 seconds", true, definition);

        verify(concurrency).acquire(eq("project-1"), eq(workflow.getId()));
        verify(concurrency).release(eq("project-1"), eq(workflow.getId()));
    }
}
