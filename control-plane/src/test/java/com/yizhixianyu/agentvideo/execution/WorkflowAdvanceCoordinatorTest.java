package com.yizhixianyu.agentvideo.execution;

import com.yizhixianyu.agentvideo.cache.WorkflowRedisLockService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowAdvanceCoordinatorTest {
    @Mock WorkflowExecutionService workflows;
    @Mock WorkflowRedisLockService lock;
    @Mock ObjectProvider<WorkflowRedisLockService> lockProvider;

    @Test
    void holdsRedisLeaseAroundTransactionalServiceCall() {
        when(lockProvider.getIfAvailable()).thenReturn(lock);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get())
            .when(lock).execute(eq("workflow-1"), any());
        var coordinator = new WorkflowAdvanceCoordinator(workflows, lockProvider);

        coordinator.continueWorkflow("workflow-1");

        verify(lock).execute(eq("workflow-1"), any());
        verify(workflows).continueWorkflow("workflow-1");
    }
}
