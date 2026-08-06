package com.yizhixianyu.agentvideo.execution;

public class WorkflowConcurrencyLimitException extends RuntimeException {
    public WorkflowConcurrencyLimitException(String scope, int limit) {
        super("Workflow concurrency limit reached for " + scope + " (limit=" + limit + ")");
    }
}
