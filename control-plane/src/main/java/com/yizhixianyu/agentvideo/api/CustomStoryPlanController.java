package com.yizhixianyu.agentvideo.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhixianyu.agentvideo.artifact.ArtifactRepository;
import com.yizhixianyu.agentvideo.auth.AuthService;
import com.yizhixianyu.agentvideo.execution.TaskRunRepository;
import com.yizhixianyu.agentvideo.execution.WorkflowExecutionService;
import com.yizhixianyu.agentvideo.execution.WorkflowRunRepository;
import com.yizhixianyu.agentvideo.plan.CustomStoryPlanEntity;
import com.yizhixianyu.agentvideo.plan.CustomStoryPlanRepository;
import com.yizhixianyu.agentvideo.plan.StoryPlanPayloadValidator;
import com.yizhixianyu.agentvideo.project.ProjectService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class CustomStoryPlanController {

    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_SUPERSEDED = "SUPERSEDED";
    private static final String STATUS_APPLIED = "APPLIED";

    private final CustomStoryPlanRepository repository;
    private final WorkflowExecutionService workflowService;
    private final WorkflowRunRepository workflowRunRepository;
    private final TaskRunRepository taskRunRepository;
    private final ArtifactRepository artifactRepository;
    private final ObjectMapper objectMapper;
    private final ProjectService projectService;
    private final AuthService authService;

    public CustomStoryPlanController(
        CustomStoryPlanRepository repository,
        WorkflowExecutionService workflowService,
        WorkflowRunRepository workflowRunRepository,
        TaskRunRepository taskRunRepository,
        ArtifactRepository artifactRepository,
        ObjectMapper objectMapper,
        ProjectService projectService,
        AuthService authService
    ) {
        this.repository = repository;
        this.workflowService = workflowService;
        this.workflowRunRepository = workflowRunRepository;
        this.taskRunRepository = taskRunRepository;
        this.artifactRepository = artifactRepository;
        this.objectMapper = objectMapper;
        this.projectService = projectService;
        this.authService = authService;
    }

    @GetMapping("/api/v1/workflow-runs/{workflowRunId}/custom-story-plan")
    public CustomStoryPlanView getPlan(@PathVariable String workflowRunId, HttpServletRequest request) {
        requireWorkflow(workflowRunId, request);
        var draft = repository.findBySourceWorkflowRunIdAndStatus(workflowRunId, STATUS_DRAFT);
        if (draft.isPresent()) {
            var entity = draft.get();
            return new CustomStoryPlanView(entity.getId(), workflowRunId,
                parsePlanJson(entity.getPlanJson()), true, entity.getStatus(),
                entity.getVersionName(), entity.getCreatedAt().toString());
        }
        var workflow = workflowRunRepository.findById(workflowRunId)
            .orElseThrow(() -> new IllegalArgumentException("Workflow run not found: " + workflowRunId));
        var tasks = taskRunRepository.findByWorkflowRunIdOrderByCreatedAtAsc(workflowRunId);
        for (var task : tasks) {
            var artifacts = artifactRepository.findByProducerTaskRunId(task.getId());
            for (var artifact : artifacts) {
                if ("STORY_PLAN".equals(artifact.getType()) && artifact.getMetadataJson() != null) {
                    return new CustomStoryPlanView(null, workflowRunId,
                        parseMetadata(artifact.getMetadataJson()), false, "ORIGINAL", null, null);
                }
            }
        }
        throw new IllegalArgumentException("No STORY_PLAN artifact found for workflow run: " + workflowRunId);
    }

    @PutMapping("/api/v1/workflow-runs/{workflowRunId}/custom-story-plan")
    @ResponseStatus(HttpStatus.CREATED)
    public CustomStoryPlanView savePlan(
        @PathVariable String workflowRunId,
        @Valid @RequestBody SaveCustomStoryPlanRequest request,
        HttpServletRequest servletRequest
    ) {
        requireWorkflow(workflowRunId, servletRequest);
        validatePlan(request.plan());
        var workflow = workflowRunRepository.findById(workflowRunId)
            .orElseThrow(() -> new IllegalArgumentException("Workflow run not found: " + workflowRunId));
        var existing = repository.findBySourceWorkflowRunIdAndStatus(workflowRunId, STATUS_DRAFT);
        existing.ifPresent(e -> {
            e.setStatus(STATUS_SUPERSEDED);
            repository.save(e);
        });
        var entity = repository.save(new CustomStoryPlanEntity(
            workflow.getProjectId(), workflowRunId, toJson(request.plan()), STATUS_DRAFT, request.versionName()
        ));
        return new CustomStoryPlanView(entity.getId(), workflowRunId, request.plan(), true,
            entity.getStatus(), entity.getVersionName(), entity.getCreatedAt().toString());
    }

    @PostMapping("/api/v1/workflow-runs/{workflowRunId}/custom-story-plan/apply")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApplyResponse applyPlan(@PathVariable String workflowRunId, HttpServletRequest request) {
        requireWorkflow(workflowRunId, request);
        var draft = repository.findBySourceWorkflowRunIdAndStatus(workflowRunId, STATUS_DRAFT)
            .orElseThrow(() -> new IllegalArgumentException("No DRAFT custom story plan found. Save a plan first."));
        var plan = parsePlanJson(draft.getPlanJson());
        validatePlan(plan);
        var continuedRunId = workflowService.applyCustomStoryPlan(workflowRunId, plan);
        draft.setStatus(STATUS_APPLIED);
        repository.save(draft);
        return new ApplyResponse(continuedRunId, "/api/v1/workflow-runs/" + continuedRunId);
    }

    @PostMapping("/api/v1/workflow-runs/{workflowRunId}/custom-timeline-render")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApplyResponse renderCustomTimeline(
        @PathVariable String workflowRunId,
        @RequestBody Map<String, Object> request,
        HttpServletRequest servletRequest
    ) {
        requireWorkflow(workflowRunId, servletRequest);
        var timeline = request == null ? null : request.get("timeline");
        if (!(timeline instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("timeline must be an object");
        }
        @SuppressWarnings("unchecked")
        var timelineMap = (Map<String, Object>) timeline;
        var continuedRunId = workflowService.applyCustomTimeline(workflowRunId, timelineMap);
        return new ApplyResponse(continuedRunId, "/api/v1/workflow-runs/" + continuedRunId);
    }

    @GetMapping("/api/v1/workflow-runs/{workflowRunId}/custom-story-plan/version-list")
    public List<VersionSummary> listVersions(@PathVariable String workflowRunId, HttpServletRequest request) {
        requireWorkflow(workflowRunId, request);
        var all = repository.findBySourceWorkflowRunIdOrderByCreatedAtDesc(workflowRunId);
        return all.stream().map(entity -> {
            var plan = parsePlanJson(entity.getPlanJson());
            @SuppressWarnings("unchecked")
            var beats = (List<Map<String, Object>>) plan.getOrDefault("beats", List.of());
            int shotCount = 0;
            long totalDurationMs = 0;
            for (var beat : beats) {
                @SuppressWarnings("unchecked")
                var shots = (List<Map<String, Object>>) beat.getOrDefault("shots", List.of());
                shotCount += shots.size();
                for (var shot : shots) {
                    totalDurationMs += toLong(shot.get("selectedDurationMs"),
                        toLong(shot.get("sourceOutMs"), 0) - toLong(shot.get("sourceInMs"), 0));
                }
            }
            return new VersionSummary(entity.getId(), entity.getVersionName(),
                entity.getStatus(), entity.getCreatedAt().toString(),
                beats.size(), shotCount, totalDurationMs);
        }).toList();
    }

    @GetMapping("/api/v1/workflow-runs/{workflowRunId}/custom-story-plan/versions/{planId}")
    public CustomStoryPlanView getVersion(
        @PathVariable String workflowRunId, @PathVariable String planId, HttpServletRequest request
    ) {
        requireWorkflow(workflowRunId, request);
        var entity = repository.findByIdAndSourceWorkflowRunId(planId, workflowRunId)
            .orElseThrow(() -> new IllegalArgumentException("Version not found: " + planId));
        var plan = parsePlanJson(entity.getPlanJson());
        return new CustomStoryPlanView(entity.getId(), workflowRunId, plan, true,
            entity.getStatus(), entity.getVersionName(), entity.getCreatedAt().toString());
    }

    @PostMapping("/api/v1/workflow-runs/{workflowRunId}/custom-story-plan/restore/{planId}")
    @ResponseStatus(HttpStatus.CREATED)
    public CustomStoryPlanView restoreVersion(
        @PathVariable String workflowRunId,
        @PathVariable String planId,
        @RequestBody(required = false) RestoreVersionRequest request,
        HttpServletRequest servletRequest
    ) {
        requireWorkflow(workflowRunId, servletRequest);
        var source = repository.findByIdAndSourceWorkflowRunId(planId, workflowRunId)
            .orElseThrow(() -> new IllegalArgumentException("Version not found: " + planId));
        var workflow = workflowRunRepository.findById(workflowRunId)
            .orElseThrow(() -> new IllegalArgumentException("Workflow run not found: " + workflowRunId));
        var existing = repository.findBySourceWorkflowRunIdAndStatus(workflowRunId, STATUS_DRAFT);
        existing.ifPresent(e -> {
            e.setStatus(STATUS_SUPERSEDED);
            repository.save(e);
        });
        var versionName = (request != null && request.versionName() != null)
            ? request.versionName()
            : (source.getVersionName() != null ? source.getVersionName() + " (restored)" : "Restored from " + planId);
        var plan = parsePlanJson(source.getPlanJson());
        validatePlan(plan);
        var entity = repository.save(new CustomStoryPlanEntity(
            workflow.getProjectId(), workflowRunId, source.getPlanJson(), STATUS_DRAFT, versionName
        ));
        return new CustomStoryPlanView(entity.getId(), workflowRunId, plan, true,
            entity.getStatus(), entity.getVersionName(), entity.getCreatedAt().toString());
    }

    @DeleteMapping("/api/v1/workflow-runs/{workflowRunId}/custom-story-plan/versions/{planId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteVersion(
        @PathVariable String workflowRunId, @PathVariable String planId, HttpServletRequest request
    ) {
        requireWorkflow(workflowRunId, request);
        var entity = repository.findByIdAndSourceWorkflowRunId(planId, workflowRunId)
            .orElseThrow(() -> new IllegalArgumentException("Version not found: " + planId));
        if (STATUS_DRAFT.equals(entity.getStatus())) {
            throw new IllegalArgumentException("Cannot delete the active DRAFT version. Save a new version first.");
        }
        repository.delete(entity);
    }

    private void requireWorkflow(String workflowRunId, HttpServletRequest request) {
        var workflow = workflowRunRepository.findById(workflowRunId)
            .orElseThrow(() -> new IllegalArgumentException("Workflow run not found: " + workflowRunId));
        projectService.getRequiredForUser(workflow.getProjectId(), authService.requireUser(request).id());
    }

    private void validatePlan(Map<String, Object> plan) {
        StoryPlanPayloadValidator.validate(plan);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize JSON", e);
        }
    }

    private Map<String, Object> parsePlanJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse plan JSON", e);
        }
    }

    private Map<String, Object> parseMetadata(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private long toLong(Object value, long fallback) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException e) { return fallback; }
        }
        return fallback;
    }

    public record CustomStoryPlanView(
        String id, String sourceWorkflowRunId, Map<String, Object> plan,
        boolean custom, String status, String versionName, String createdAt
    ) {}

    public record SaveCustomStoryPlanRequest(@NotNull Map<String, Object> plan, String versionName) {}

    public record ApplyResponse(String workflowRunId, String statusUrl) {}

    public record VersionSummary(
        String id, String versionName, String status, String createdAt,
        int beatCount, int shotCount, long totalDurationMs
    ) {}

    public record RestoreVersionRequest(String versionName) {}
}
