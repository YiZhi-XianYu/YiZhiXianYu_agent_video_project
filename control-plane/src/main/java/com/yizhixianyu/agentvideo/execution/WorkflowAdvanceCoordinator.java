package com.yizhixianyu.agentvideo.execution;

import com.yizhixianyu.agentvideo.cache.WorkflowRedisLockService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.function.Supplier;

/** Acquires a Redis lease outside the transactional workflow service. */
@Service
public class WorkflowAdvanceCoordinator {
    private final WorkflowExecutionService workflows;
    private final WorkflowRedisLockService lock;

    public WorkflowAdvanceCoordinator(
        WorkflowExecutionService workflows,
        ObjectProvider<WorkflowRedisLockService> lockProvider
    ) {
        this.workflows = workflows;
        this.lock = lockProvider.getIfAvailable();
    }

    public void continueWorkflow(String workflowRunId) {
        execute(workflowRunId, () -> { workflows.continueWorkflow(workflowRunId); return null; });
    }

    public void recoverWorkflow(String workflowRunId) {
        execute(workflowRunId, () -> { workflows.recoverWorkflow(workflowRunId); return null; });
    }

    public String applyCustomStoryPlan(String workflowRunId, Map<String, Object> plan) {
        return execute(workflowRunId, () -> workflows.applyCustomStoryPlan(workflowRunId, plan));
    }

    public String applyCustomTimeline(String workflowRunId, Map<String, Object> timeline) {
        return execute(workflowRunId, () -> workflows.applyCustomTimeline(workflowRunId, timeline));
    }

    public String selectBgmCandidate(String workflowRunId, String artifactId) {
        return execute(workflowRunId, () -> workflows.selectBgmCandidate(workflowRunId, artifactId));
    }

    public String continueWithoutBgm(String workflowRunId) {
        return execute(workflowRunId, () -> workflows.continueWithoutBgm(workflowRunId));
    }

    public String refreshBgmCandidates(String workflowRunId) {
        return execute(workflowRunId, () -> workflows.refreshBgmCandidates(workflowRunId));
    }

    private <T> T execute(String workflowRunId, Supplier<T> action) {
        return lock == null ? action.get() : lock.execute(workflowRunId, action);
    }
}
