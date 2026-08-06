package com.yizhixianyu.agentvideo.execution;

import org.springframework.stereotype.Service;
import com.yizhixianyu.agentvideo.workflow.WorkflowDefinition;

import java.util.function.Supplier;

/** Coordinates Redis admission with workflow creation and terminal release. */
@Service
public class WorkflowAdmissionCoordinator {
    private final WorkflowConcurrencyService concurrency;
    private final WorkflowExecutionService workflows;

    public WorkflowAdmissionCoordinator(WorkflowConcurrencyService concurrency, WorkflowExecutionService workflows) {
        this.concurrency = concurrency;
        this.workflows = workflows;
    }

    public WorkflowRunEntity createVideoProxyRun(String projectId, String assetId, ProxyQuality quality) {
        return create(projectId, () -> workflows.createVideoProxyRun(projectId, assetId, quality));
    }

    public WorkflowRunEntity createMultiAssetAnalysisRun(String projectId, java.util.List<String> assetIds,
                                                          ProxyQuality quality, String durationPrompt, boolean autoMode,
                                                          WorkflowDefinition definition) {
        return create(projectId, () -> workflows.createMultiAssetAnalysisRun(projectId, assetIds, quality, durationPrompt, autoMode, definition));
    }

    public WorkflowRunEntity createMultiAssetAnalysisRun(String projectId, java.util.List<String> assetIds,
                                                          ProxyQuality quality, String durationPrompt, boolean autoMode,
                                                          WorkflowDefinition definition,
                                                          WorkflowExecutionService.AgentContext agentContext) {
        return create(projectId, () -> workflows.createMultiAssetAnalysisRun(projectId, assetIds, quality, durationPrompt, autoMode, definition, agentContext));
    }

    public WorkflowRunEntity createMultiAssetAnalysisRun(String projectId, java.util.List<String> assetIds,
                                                          ProxyQuality quality, String durationPrompt, boolean autoMode) {
        return create(projectId, () -> workflows.createMultiAssetAnalysisRun(projectId, assetIds, quality, durationPrompt, autoMode));
    }

    public void releaseIfTerminal(WorkflowRunEntity workflow) {
        if (workflow.getStatus() == RunStatus.SUCCEEDED || workflow.getStatus() == RunStatus.FAILED) {
            concurrency.release(workflow.getProjectId(), workflow.getId());
        }
    }

    private WorkflowRunEntity create(String projectId, Supplier<WorkflowRunEntity> action) {
        String admissionId = "pending-" + java.util.UUID.randomUUID();
        concurrency.acquire(projectId, admissionId);
        try {
            var workflow = action.get();
            // Replace the temporary lease with the real workflow id.
            concurrency.release(projectId, admissionId);
            concurrency.acquire(projectId, workflow.getId());
            return workflow;
        } catch (RuntimeException exception) {
            concurrency.release(projectId, admissionId);
            throw exception;
        }
    }
}
