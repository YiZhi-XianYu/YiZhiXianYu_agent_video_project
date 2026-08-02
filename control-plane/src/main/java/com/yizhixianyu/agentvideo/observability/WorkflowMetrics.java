package com.yizhixianyu.agentvideo.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Low-cardinality workflow/task metrics exposed through Actuator Prometheus. */
@Component
public class WorkflowMetrics {
    private final MeterRegistry registry;
    private final Counter workflowsStarted;
    private final Counter workflowsCompleted;
    private final Counter workflowsFailed;
    private final Counter tasksDispatched;
    private final ConcurrentMap<String, Counter> taskResults = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> taskTimers = new ConcurrentHashMap<>();

    public WorkflowMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.workflowsStarted = Counter.builder("agentvideo.workflow.started")
            .description("Workflow runs started").register(registry);
        this.workflowsCompleted = Counter.builder("agentvideo.workflow.completed")
            .description("Workflow runs completed successfully").register(registry);
        this.workflowsFailed = Counter.builder("agentvideo.workflow.failed")
            .description("Workflow runs failed").register(registry);
        this.tasksDispatched = Counter.builder("agentvideo.task.dispatched")
            .description("Tasks dispatched to tool service").register(registry);
    }

    public void workflowStarted() { workflowsStarted.increment(); }
    public void workflowCompleted(boolean success) {
        (success ? workflowsCompleted : workflowsFailed).increment();
    }
    public void taskDispatched(String toolName) {
        tasksDispatched.increment();
        timer(toolName); // ensure a stable timer exists for dashboards
    }
    public Timer.Sample taskStarted() { return Timer.start(registry); }
    public void taskFinished(String toolName, String status, Timer.Sample sample) {
        taskResults.computeIfAbsent(status, value -> Counter.builder("agentvideo.task.result")
            .description("Task results by terminal status")
            .tag("status", value)
            .register(registry)).increment();
        if (sample != null) sample.stop(timer(toolName));
    }
    private Timer timer(String toolName) {
        String safeTool = toolName == null || toolName.isBlank() ? "unknown" : toolName;
        return taskTimers.computeIfAbsent(safeTool, value -> Timer.builder("agentvideo.task.duration")
            .description("Task execution duration")
            .tag("tool", value)
            .publishPercentileHistogram()
            .register(registry));
    }
}
