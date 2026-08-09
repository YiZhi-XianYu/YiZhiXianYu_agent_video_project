package com.yizhixianyu.agentvideo.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.yizhixianyu.agentvideo.execution.TaskRunEntity;
import com.yizhixianyu.agentvideo.execution.TaskRunRepository;
import com.yizhixianyu.agentvideo.execution.WorkflowRunRepository;
import com.yizhixianyu.agentvideo.trace.AgentTraceEventEntity;
import com.yizhixianyu.agentvideo.trace.AgentTraceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds a concise user explanation while retaining raw trace payloads for audit. */
@Service
public class ChuxueExplanationService {
    private final AgentTraceService traces;
    private final WorkflowRunRepository workflows;
    private final TaskRunRepository tasks;
    private final ObjectMapper mapper;

    public ChuxueExplanationService(AgentTraceService traces, WorkflowRunRepository workflows,
                                    TaskRunRepository tasks, ObjectMapper mapper) {
        this.traces = traces; this.workflows = workflows; this.tasks = tasks; this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public Explanation bySession(String sessionId) { return build(traces.bySession(sessionId)); }
    @Transactional(readOnly = true)
    public Explanation byWorkflow(String workflowRunId) { return build(traces.byWorkflow(workflowRunId)); }
    @Transactional(readOnly = true)
    public Explanation byTask(String taskRunId) { return build(traces.byTask(taskRunId)); }

    private Explanation build(List<AgentTraceEventEntity> events) {
        var user = new ArrayList<String>();
        var decisions = new ArrayList<Decision>();
        var audit = new ArrayList<Audit>();
        for (var event : events) {
            var payload = parse(event.getPayloadJson());
            var summary = summarize(event, payload);
            if (summary != null) user.add(summary);
            decisions.add(new Decision(event.getEventType(), event.getStatus(), summary));
            audit.add(new Audit(event.getEventType(), event.getTraceId(), event.getAgentName(), event.getToolName(),
                event.getOccurredAt(), payload));
        }
        return new Explanation(user, decisions, audit);
    }

    private String summarize(AgentTraceEventEntity event, Map<String, Object> p) {
        return switch (event.getEventType()) {
            case "CHUXUE_PLAN_PROPOSED" -> "已根据你的需求生成受控 Workflow 方案";
            case "CHUXUE_PLAN_CONFIRMED" -> "你已确认方案，系统开始执行 Workflow";
            case "GATE_DECISION" -> "你在 " + String.valueOf(p.getOrDefault("gateKey", "当前 Gate")) + " 选择了 " + event.getStatus();
            case "TASK_SUCCEEDED" -> taskSuccess(event.getToolName(), p);
            case "TASK_FALLBACK_RETRY" -> "Task 失败后将使用 fallback 或重试：" + String.valueOf(p.getOrDefault("fallbackReason", "未知原因"));
            case "TASK_FAILED" -> "Task 执行失败：" + String.valueOf(p.getOrDefault("reason", "未知错误"));
            case "WORKFLOW_CANCELLED" -> "你取消了当前 Workflow";
            case "WORKFLOW_RETRY_REQUESTED" -> "你请求重试当前 Workflow";
            default -> null;
        };
    }

    private String taskSuccess(String tool, Map<String, Object> p) {
        if ("decision.shot-rank".equals(tool)) return "系统已完成镜头筛选和排序";
        if ("planning.story-template".equals(tool)) return "已经生成初版故事计划";
        if ("video.render".equals(tool)) return "最终视频已由渲染 Worker 生成";
        if ("audio.source-transcribe".equals(tool)) return "已生成可供字幕使用的转写 Artifact";
        return "Task 已完成：" + String.valueOf(tool);
    }

    private Map<String, Object> parse(String json) {
        try { return mapper.readValue(json == null ? "{}" : json, new TypeReference<>() {}); }
        catch (Exception ignored) { return Map.of("rawPayload", json); }
    }

    public record Explanation(List<String> userSummary, List<Decision> decisions, List<Audit> audit) {}
    public record Decision(String eventType, String status, String userMessage) {}
    public record Audit(String eventType, String traceId, String agentName, String toolName,
                        java.time.Instant occurredAt, Map<String, Object> payload) {}
}
