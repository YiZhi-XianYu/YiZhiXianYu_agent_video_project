package com.yizhixianyu.agentvideo.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhixianyu.agentvideo.auth.AuthService;
import com.yizhixianyu.agentvideo.cache.RedisDraftService;
import com.yizhixianyu.agentvideo.cache.DraftConflictException;
import com.yizhixianyu.agentvideo.cache.StoredDraft;
import com.yizhixianyu.agentvideo.execution.WorkflowRunRepository;
import com.yizhixianyu.agentvideo.project.ProjectService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
    private final Duration dagDraftTtl;
    private final Duration gateDraftTtl;
    private final Map<String, StoredDraft> memory = new ConcurrentHashMap<>();

    @Autowired
    public DraftController(AuthService authService, ProjectService projectService,
                           WorkflowRunRepository workflowRepository, ObjectMapper objectMapper,
                           ObjectProvider<RedisDraftService> redisProvider,
                           @Value("${app.redis.dag-draft-ttl-seconds:86400}") long dagDraftTtlSeconds,
                           @Value("${app.redis.gate-draft-ttl-seconds:14400}") long gateDraftTtlSeconds) {
        this.authService = authService; this.projectService = projectService;
        this.workflowRepository = workflowRepository; this.objectMapper = objectMapper; this.redis = redisProvider.getIfAvailable();
        this.dagDraftTtl = Duration.ofSeconds(Math.max(1, dagDraftTtlSeconds));
        this.gateDraftTtl = Duration.ofSeconds(Math.max(1, gateDraftTtlSeconds));
    }

    @PutMapping("/projects/{projectId}/dag-drafts/{draftId}")
    public DraftResponse saveDag(@PathVariable String projectId, @PathVariable String draftId,
                                 @RequestBody JsonNode body, HttpServletRequest request) {
        var user = authService.requireUser(request);
        projectService.getRequiredForUser(projectId, user.id());
        return save(key("dag", user.id(), projectId, draftId), body, dagDraftTtl);
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
        return save(key("gate", user.id(), workflowRunId, gateKey), body, gateDraftTtl);
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
        try {
            var json = objectMapper.writeValueAsString(body);
            Long expected = body.has("draftRevision") && body.path("draftRevision").canConvertToLong()
                ? body.path("draftRevision").asLong() : 0L;
            StoredDraft saved;
            if (redis != null) {
                try {
                    saved = redis.save(key, json, ttl, expected);
                } catch (DraftConflictException conflict) {
                    throw conflict;
                } catch (RuntimeException unavailable) {
                    saved = saveMemory(key, json, ttl, expected);
                }
            } else {
                saved = saveMemory(key, json, ttl, expected);
            }
            memory.put(key, saved);
            return new DraftResponse(true, key, saved.revision(), saved.ttlSeconds());
        } catch (DraftConflictException conflict) {
            throw conflict;
        } catch (Exception ex) { throw new IllegalArgumentException("Invalid draft JSON", ex); }
    }
    private ResponseEntity<JsonNode> read(String key) {
        StoredDraft stored = null;
        if (redis != null) {
            try {
                stored = redis.get(key);
                if (stored == null) memory.remove(key);
            } catch (RuntimeException unavailable) {
                stored = memory.get(key);
            }
        } else {
            stored = memory.get(key);
        }
        if (stored != null && stored.expired()) {
            memory.remove(key, stored);
            stored = null;
        }
        if (stored == null) return ResponseEntity.notFound().build();
        try {
            var node = objectMapper.readTree(stored.json());
            if (node.isObject()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("draftRevision", stored.revision());
                if (stored.ttlSeconds() >= 0) ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("draftTtlSeconds", stored.ttlSeconds());
            }
            return ResponseEntity.ok(node);
        } catch (Exception ex) { return ResponseEntity.notFound().build(); }
    }
    private void remove(String key) { memory.remove(key); if (redis != null) { try { redis.delete(key); } catch (RuntimeException ignored) { } } }
    private StoredDraft saveMemory(String key, String json, Duration ttl, Long expected) {
        var current = memory.get(key);
        if (current != null && current.expired()) {
            memory.remove(key, current);
            current = null;
        }
        if (expected != null && (current == null ? expected != 0 : current.revision() != expected)) {
            throw new DraftConflictException("Draft revision is stale; reload before saving");
        }
        long revision = current == null ? 1 : current.revision() + 1;
        return StoredDraft.expiringIn(revision, json, Math.max(1, ttl.getSeconds()));
    }
    private String key(String type, String owner, String scope, String id) { return "avp:v1:" + type + ":draft:" + owner + ":" + scope + ":" + id; }
    public record DraftResponse(boolean saved, String key, long revision, long ttlSeconds) {}
}
