package com.yizhixianyu.agentvideo.api;

import com.yizhixianyu.agentvideo.agent.AgentSessionEntity;
import com.yizhixianyu.agentvideo.agent.AgentSessionService;
import com.yizhixianyu.agentvideo.agent.AgentSessionTurnEntity;
import com.yizhixianyu.agentvideo.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/v1/agent-sessions")
public class AgentSessionController {
    private final AgentSessionService service;
    private final AuthService auth;
    public AgentSessionController(AgentSessionService service, AuthService auth) { this.service = service; this.auth = auth; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionView create(@Valid @RequestBody CreateRequest request, HttpServletRequest servletRequest) {
        var user = auth.requireUser(servletRequest);
        return SessionView.from(service.create(user.id(), request.projectId(), request.goal(), request.targetDurationMs()));
    }

    @GetMapping
    public List<SessionView> list(@RequestParam String projectId, HttpServletRequest request) {
        var user = auth.requireUser(request);
        return service.list(user.id(), projectId).stream().map(SessionView::from).toList();
    }

    @GetMapping("/{sessionId}")
    public SessionView get(@PathVariable String sessionId, HttpServletRequest request) {
        return SessionView.from(service.requireOwned(auth.requireUser(request).id(), sessionId));
    }

    @GetMapping("/{sessionId}/turns")
    public List<TurnView> turns(@PathVariable String sessionId, HttpServletRequest request) {
        return service.turns(auth.requireUser(request).id(), sessionId).stream().map(TurnView::from).toList();
    }

    @PostMapping("/{sessionId}/turns")
    @ResponseStatus(HttpStatus.CREATED)
    public TurnView addTurn(@PathVariable String sessionId, @Valid @RequestBody TurnRequest turn, HttpServletRequest request) {
        return TurnView.from(service.addTurn(auth.requireUser(request).id(), sessionId, turn.role(), turn.content()));
    }

    public record CreateRequest(@NotBlank String projectId, @NotBlank String goal, Integer targetDurationMs) {}
    public record TurnRequest(@NotBlank String role, @NotBlank String content) {}
    public record SessionView(String id, String userId, String projectId, String goal, Integer targetDurationMs,
                              String currentWorkflowRunId, String currentTurnId, String currentPlanId,
                              Integer dagVersion, String currentGateKey, String status, Instant updatedAt) {
        static SessionView from(AgentSessionEntity e) { return new SessionView(e.getId(), e.getUserId(), e.getProjectId(), e.getNaturalLanguageGoal(), e.getTargetDurationMs(), e.getCurrentWorkflowRunId(), e.getCurrentTurnId(), e.getCurrentPlanId(), e.getDagVersion(), e.getCurrentGateKey(), e.getStatus(), e.getUpdatedAt()); }
    }
    public record TurnView(String id, String sessionId, int sequenceNumber, String role, String content, String planId, String workflowRunId, Instant createdAt) {
        static TurnView from(AgentSessionTurnEntity e) { return new TurnView(e.getId(), e.getSessionId(), e.getSequenceNumber(), e.getRole(), e.getContent(), e.getPlanId(), e.getWorkflowRunId(), e.getCreatedAt()); }
    }
}
