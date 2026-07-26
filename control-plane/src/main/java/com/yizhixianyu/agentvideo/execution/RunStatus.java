package com.yizhixianyu.agentvideo.execution;

/** Workflow运行状态。新增PAUSED支持人在回路暂停审核。 */
public enum RunStatus {
    CREATED,
    RUNNING,
    PAUSED,
    SUCCEEDED,
    FAILED
}

