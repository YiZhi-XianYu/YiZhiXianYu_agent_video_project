package com.yizhixianyu.agentvideo.agent;

/** Raised when a chat session already owns a non-terminal Workflow. */
public class ActiveSessionWorkflowException extends RuntimeException {
    private final String workflowRunId;
    private final String workflowStatus;

    public ActiveSessionWorkflowException(String workflowRunId, String workflowStatus) {
        super("当前会话已有 Workflow 正在执行（" + workflowRunId + "，状态 " + workflowStatus
            + "）。请等待它完成或失败后再开启新的 Workflow。");
        this.workflowRunId = workflowRunId;
        this.workflowStatus = workflowStatus;
    }

    public String getWorkflowRunId() { return workflowRunId; }
    public String getWorkflowStatus() { return workflowStatus; }
}
