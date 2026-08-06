package com.yizhixianyu.agentvideo.execution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowConcurrencyLimitExceptionTest {
    @Test
    void exposesUsefulLimitMessage() {
        var exception = new WorkflowConcurrencyLimitException("project", 2);
        assertThat(exception.getMessage()).contains("project", "2");
    }
}
