package com.yizhixianyu.agentvideo.execution;

import com.yizhixianyu.agentvideo.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Lob;

import java.time.Instant;

@Entity
@Table(name = "workflow_runs")
public class WorkflowRunEntity extends BaseEntity {

    @Column(nullable = false, length = 40)
    private String projectId;

    @Column(length = 40)
    private String assetId;

    @Column(nullable = false, length = 100)
    private String workflowType;

    @Column(length = 20)
    private String proxyQuality;

    @Column(length = 100)
    private String definitionKey;

    private Integer definitionVersion;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String definitionJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RunStatus status;

    @Column(nullable = false)
    private int progress;

    @Column(name = "auto_mode", nullable = false)
    private boolean autoMode = false;

    @Column(name = "current_gate_key", length = 100)
    private String currentGateKey;

    @Lob
    @Column(name = "gates_json", columnDefinition = "LONGTEXT")
    private String gatesJson;

    @Lob
    @Column(name = "completed_gates_json", columnDefinition = "LONGTEXT")
    private String completedGatesJson = "[]";

    private Instant startedAt;

    private Instant completedAt;

    @Column(length = 2000)
    private String errorMessage;

    @Column(name = "agent_session_id", length = 40)
    private String agentSessionId;
    @Column(name = "agent_turn_id", length = 80)
    private String agentTurnId;
    @Column(name = "agent_plan_id", length = 80)
    private String agentPlanId;
    @Column(name = "agent_trace_id", length = 80)
    private String agentTraceId;

    protected WorkflowRunEntity() {
    }

    public WorkflowRunEntity(String projectId, String assetId, String workflowType, ProxyQuality proxyQuality) {
        this.projectId = projectId;
        this.assetId = assetId;
        this.workflowType = workflowType;
        this.proxyQuality = proxyQuality.value();
        this.status = RunStatus.CREATED;
        this.progress = 0;
    }

    public WorkflowRunEntity(
        String projectId,
        String legacyAssetId,
        String workflowType,
        ProxyQuality proxyQuality,
        String definitionKey,
        int definitionVersion,
        String definitionJson
    ) {
        this.projectId = projectId;
        // Keep the first Asset in the legacy column for databases created before multi-asset support.
        this.assetId = legacyAssetId;
        this.workflowType = workflowType;
        this.proxyQuality = proxyQuality.value();
        this.definitionKey = definitionKey;
        this.definitionVersion = definitionVersion;
        this.definitionJson = definitionJson;
        this.status = RunStatus.CREATED;
        this.progress = 0;
    }

    public void start() {
        status = RunStatus.RUNNING;
        if (progress == 0) {
            progress = 5;
        }
        if (startedAt == null) {
            startedAt = Instant.now();
        }
    }

    public void updateProgress(int progress) {
        if (status == RunStatus.RUNNING) {
            this.progress = Math.max(this.progress, Math.min(progress, 99));
        }
    }


    /** 在Gate处暂停Workflow，等待用户审核 */
    public void pause(String gateKey) {
        if (status == RunStatus.RUNNING) {
            status = RunStatus.PAUSED;
            currentGateKey = gateKey;
        }
    }

    /** 从暂停状态恢复，继续执行下游Task */
    public void resume() {
        if (status == RunStatus.PAUSED) {
            status = RunStatus.RUNNING;
            currentGateKey = null;
        }
    }

    public void completeCurrentGate() {
        if (currentGateKey == null) {
            return;
        }
        var completed = completedGateKeys();
        completed.add(currentGateKey);
        completedGatesJson = "[\"" + String.join("\",\"", completed) + "\"]";
    }

    public boolean hasCompletedGate(String gateKey) {
        return completedGateKeys().contains(gateKey);
    }

    private java.util.LinkedHashSet<String> completedGateKeys() {
        var result = new java.util.LinkedHashSet<String>();
        if (completedGatesJson == null || completedGatesJson.isBlank()) {
            return result;
        }
        var body = completedGatesJson.strip();
        if (body.length() < 2) {
            return result;
        }
        for (var item : body.substring(1, body.length() - 1).split(",")) {
            var value = item.strip().replace("\"", "");
            if (!value.isBlank()) {
                result.add(value);
            }
        }
        return result;
    }

    public void succeed() {
        status = RunStatus.SUCCEEDED;
        progress = 100;
        completedAt = Instant.now();
        errorMessage = null;
    }

    public void fail(String message) {
        status = RunStatus.FAILED;
        progress = 100;
        completedAt = Instant.now();
        errorMessage = ErrorMessageFormatter.fit(message);
    }

    public String getProjectId() {
        return projectId;
    }

    public String getAssetId() {
        return assetId;
    }

    public String getWorkflowType() {
        return workflowType;
    }

    public ProxyQuality getProxyQuality() {
        return proxyQuality == null ? ProxyQuality.FHD_1080P : ProxyQuality.fromValue(proxyQuality);
    }

    public String getDefinitionKey() { return definitionKey; }
    public Integer getDefinitionVersion() { return definitionVersion; }
    public String getDefinitionJson() { return definitionJson; }

    public RunStatus getStatus() {
        return status;
    }

    public boolean isAutoMode() { return autoMode; }
    public void setAutoMode(boolean autoMode) { this.autoMode = autoMode; }
    public String getCurrentGateKey() { return currentGateKey; }
    public String getGatesJson() { return gatesJson; }
    public void setGatesJson(String gatesJson) { this.gatesJson = gatesJson; }
    public String getCompletedGatesJson() { return completedGatesJson; }

    public int getProgress() {
        return progress;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void cancel() {
        if (status == RunStatus.SUCCEEDED || status == RunStatus.FAILED) {
            throw new IllegalStateException("Cannot cancel terminal Workflow");
        }
        status = RunStatus.FAILED;
        progress = Math.min(progress, 99);
        completedAt = Instant.now();
        errorMessage = "Workflow cancelled by user";
    }

    public void attachAgentContext(String sessionId, String turnId, String planId, String traceId) {
        this.agentSessionId = sessionId;
        this.agentTurnId = turnId;
        this.agentPlanId = planId;
        this.agentTraceId = traceId;
    }
    public String getAgentSessionId() { return agentSessionId; }
    public String getAgentTurnId() { return agentTurnId; }
    public String getAgentPlanId() { return agentPlanId; }
    public String getAgentTraceId() { return agentTraceId; }
}
