package com.yizhixianyu.agentvideo.cache;

/** Another request is currently advancing the same workflow. */
public class WorkflowLockBusyException extends RuntimeException {
    public WorkflowLockBusyException(String workflowRunId) {
        super("Workflow is already being advanced: " + workflowRunId);
    }
}
