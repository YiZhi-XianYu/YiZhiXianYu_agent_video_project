package com.yizhixianyu.agentvideo.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhixianyu.agentvideo.auth.AuthService;
import com.yizhixianyu.agentvideo.cache.RedisDraftService;
import com.yizhixianyu.agentvideo.execution.WorkflowRunRepository;
import com.yizhixianyu.agentvideo.project.ProjectService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Best-effort draft storage. Redis can be enabled without making it the source of truth. */
@RestController
@RequestMapping("/api/v1")
public class DraftController {
    private final AuthService authService;
    private final ProjectService projectService;
    private final WorkflowRunRepository workflowRepository;
    private final ObjectMapper objectMapper;
    private final RedisDraftService redis;
    private final Map<String, String> memory = new ConcurrentHashMap<>();

    @Autowired
    public DraftController(AuthService authService, ProjectService projectService,
                           WorkflowRunRepository workflowRepository, ObjectMapper objectMapper,
                           ObjectProvider<RedisDraftService> redisProvider) {
        this.authService = authService; this.projectService = projectService;
        this.workflowRepository = workflowRepository; this.objectMapper = objectMapper; this.redis = redisProvider.getIfAvailable();
    }

    @PutMapping("/projects/{projectId}/dag-drafts/{draftId}")
    public DraftResponse saveDag(@PathVariable String projectId, @PathVariable String draftId,
                                 @RequestBody JsonNode body, HttpServletRequest request) {
        var user = authService.requireUser(request);
        projectService.getRequiredForUser(projectId, user.id());
        return save(key("dag", user.id(), projectId, draftId), body, Duration.ofHours(24));
    }

    @GetMapping("/projects/{projectId}/dag-drafts/{draftId}")
    public ResponseEntity<JsonNode> getDag(@PathVariable String projectId, @PathVariable String draftId,
                                           HttpServletRequest request) {
        var user = authService.requireUser(request); projectService.getRequiredForUser(projectId, user.id());
        return read(key("dag", user.id(), projectId, draftId));
    }

    @DeleteMapping("/projects/{projectId}/dag-drafts/{draftId}")
    public void deleteDag(@PathVariable String projectId, @PathVariable String draftId, HttpServletRequest request) {
        var user = authService.requireUser(request); projectService.getRequiredForUser(projectId, user.id());
        remove(key("dag", user.id(), projectId, draftId));
    }

    @PutMapping("/workflow-runs/{workflowRunId}/gate-drafts/{gateKey}")
    public DraftResponse saveGate(@PathVariable String workflowRunId, @PathVariable String gateKey,
                                  @RequestBody JsonNode body, HttpServletRequest request) {
        var user = authService.requireUser(request);
        var workflow = workflowRepository.findById(workflowRunId).orElseThrow(() -> new IllegalArgumentException("Workflow run not found"));
        projectService.getRequiredForUser(workflow.getProjectId(), user.id());
        return save(key("gate", user.id(), workflowRunId, gateKey), body, Duration.ofHours(4));
    }

    @GetMapping("/workflow-runs/{workflowRunId}/gate-drafts/{gateKey}")
    public ResponseEntity<JsonNode> getGate(@PathVariable String workflowRunId, @PathVariable String gateKey, HttpServletRequest request) {
        var user = authService.requireUser(request);
        var workflow = workflowRepository.findById(workflowRunId).orElseThrow(() -> new IllegalArgumentException("Workflow run not found"));
        projectService.getRequiredForUser(workflow.getProjectId(), user.id());
        return read(key("gate", user.id(), workflowRunId, gateKey));
    }

    @DeleteMapping("/workflow-runs/{workflowRunId}/gate-drafts/{gateKey}")
    public void deleteGate(@PathVariable String workflowRunId, @PathVariable String gateKey, HttpServletRequest request) {
        var user = authService.requireUser(request);
        var workflow = workflowRepository.findById(workflowRunId).orElseThrow(() -> new IllegalArgumentException("Workflow run not found"));
        projectService.getRequiredForUser(workflow.getProjectId(), user.id());
        remove(key("gate", user.id(), workflowRunId, gateKey));
    }

    private DraftResponse save(String key, JsonNode body, Duration ttl) {
        try { var json = objectMapper.writeValueAsString(body); memory.put(key, json); if (redis != null) { try { redis.save(key, json, ttl); } catch (RuntimeException ignored) { } } return new DraftResponse(true, key); }
        catch (Exception ex) { throw new IllegalArgumentException("Invalid draft JSON", ex); }
    }
    private ResponseEntity<JsonNode> read(String key) {
        String json = null; if (redis != null) { try { json = redis.get(key); } catch (RuntimeException ignored) { } } if (json == null) json = memory.get(key);
        if (json == null) return ResponseEntity.notFound().build();
        try { return ResponseEntity.ok(objectMapper.readTree(json)); } catch (Exception ex) { return ResponseEntity.notFound().build(); }
    }
    private void remove(String key) { memory.remove(key); if (redis != null) { try { redis.delete(key); } catch (RuntimeException ignored) { } } }
    private String key(String type, String owner, String scope, String id) { return "avp:v1:" + type + ":draft:" + owner + ":" + scope + ":" + id; }
    public record DraftResponse(boolean saved, String key) {}
}
