package com.yizhixianyu.agentvideo.execution;

public enum TaskStatus {
    PENDING,
    READY,
    DISPATCHING,
    RUNNING,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED,
    SKIPPED
}
