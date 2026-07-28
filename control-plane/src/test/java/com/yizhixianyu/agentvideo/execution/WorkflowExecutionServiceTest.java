package com.yizhixianyu.agentvideo.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhixianyu.agentvideo.artifact.ArtifactEntity;
import com.yizhixianyu.agentvideo.artifact.ArtifactRepository;
import com.yizhixianyu.agentvideo.asset.AssetService;
import com.yizhixianyu.agentvideo.project.ProjectService;
import com.yizhixianyu.agentvideo.toolclient.ToolServiceClient;
import com.yizhixianyu.agentvideo.workflow.MultiAssetAnalysisTemplate;
import com.yizhixianyu.agentvideo.workflow.WorkflowDefinition;
import com.yizhixianyu.agentvideo.workflow.WorkflowDefinitionValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.argThat;

@ExtendWith(MockitoExtension.class)
class WorkflowExecutionServiceTest {

    @Mock private WorkflowRunRepository workflowRepository;
    @Mock private WorkflowAssetRepository workflowAssetRepository;
    @Mock private TaskRunRepository taskRepository;
    @Mock private TaskDependencyRepository dependencyRepository;
    @Mock private ToolExecutionRepository toolExecutionRepository;
    @Mock private ArtifactRepository artifactRepository;
    @Mock private ProjectService projectService;
    @Mock private AssetService assetService;
    @Mock private ToolServiceClient toolClient;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ObjectMapper objectMapper;
    private MultiAssetAnalysisTemplate template;
    private WorkflowExecutionService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        template = new MultiAssetAnalysisTemplate();
        service = new WorkflowExecutionService(
            workflowRepository, workflowAssetRepository, taskRepository, dependencyRepository,
            toolExecutionRepository, artifactRepository, projectService, assetService, toolClient,
            objectMapper, template, new WorkflowDefinitionValidator(), "http://control-plane", 3,
            0, 30_000, 10, Path.of("target/test-artifacts"), eventPublisher
        );
    }

    @Test
    void optionalEnhancementFailuresStillReleaseRender() throws Exception {
        var workflow = workflow(true);
        var timeline = succeededTask("timeline", "timeline_compose", "timeline.compose");
        var bgm = failedTask("bgm", "bgm_select", "audio.bgm-select");
        var subtitle = failedTask("subtitle", "subtitle_compose", "subtitle.compose");
        var render = pendingTask("render", "video_render", "video.render");
        var tasks = List.of(timeline, bgm, subtitle, render);
        var dependencies = List.of(
            dependency("render", "timeline", WorkflowDefinition.DependencyType.REQUIRED),
            dependency("render", "bgm", WorkflowDefinition.DependencyType.OPTIONAL),
            dependency("render", "subtitle", WorkflowDefinition.DependencyType.OPTIONAL)
        );
        stubWorkflow(workflow, tasks, dependencies);

        service.recoverWorkflow("workflow-1");

        assertThat(render.getStatus()).isEqualTo(TaskStatus.READY);
        assertThat(workflow.getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void terminalRenderGatePausesAndDoesNotPauseAgainAfterCompletion() throws Exception {
        var workflow = workflow(false);
        var render = succeededTask("render", "video_render", "video.render");
        stubWorkflow(workflow, List.of(render), List.of());

        service.recoverWorkflow("workflow-1");
        assertThat(workflow.getStatus()).isEqualTo(RunStatus.PAUSED);
        assertThat(workflow.getCurrentGateKey()).isEqualTo("gate_render_review");

        service.continueWorkflow("workflow-1");
        assertThat(workflow.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(workflow.hasCompletedGate("gate_render_review")).isTrue();
    }

    @Test
    void intermediateGatePausesAndReturnsWithoutReevaluatingPendingTaskForever() {
        var workflow = workflow(false);
        var ranking = succeededTask("ranking", "shot_ranking", "decision.shot-rank");
        var story = pendingTask("story", "story_plan", "planning.story-template");
        stubWorkflow(
            workflow,
            List.of(ranking, story),
            List.of(dependency("story", "ranking", WorkflowDefinition.DependencyType.REQUIRED))
        );

        assertTimeoutPreemptively(
            Duration.ofSeconds(1),
            () -> service.recoverWorkflow("workflow-1")
        );

        assertThat(workflow.getStatus()).isEqualTo(RunStatus.PAUSED);
        assertThat(workflow.getCurrentGateKey()).isEqualTo("gate_shot_ranking");
        assertThat(story.getStatus()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void completedUpstreamGateDoesNotHideNextPendingGate() {
        var workflow = workflow(false);
        workflow.pause("gate_shot_ranking");
        workflow.completeCurrentGate();
        workflow.resume();
        var ranking = succeededTask("ranking", "shot_ranking", "decision.shot-rank");
        var story = succeededTask("story", "story_plan", "planning.story-template");
        var highlight = pendingTask("highlight", "highlight_selection", "decision.highlight-select");
        stubWorkflow(
            workflow,
            List.of(ranking, story, highlight),
            List.of(
                dependency("highlight", "ranking", WorkflowDefinition.DependencyType.REQUIRED),
                dependency("highlight", "story", WorkflowDefinition.DependencyType.REQUIRED)
            )
        );

        service.recoverWorkflow("workflow-1");

        assertThat(workflow.getStatus()).isEqualTo(RunStatus.PAUSED);
        assertThat(workflow.getCurrentGateKey()).isEqualTo("gate_story_edit");
        assertThat(highlight.getStatus()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void renderDispatchContainsTimelineBgmAndSubtitleArtifacts() {
        var workflow = workflow(true);
        var render = pendingTask("render", "video_render", "video.render");
        render.markReady();
        var dependencies = List.of(
            dependency("render", "timeline", WorkflowDefinition.DependencyType.REQUIRED),
            dependency("render", "bgm", WorkflowDefinition.DependencyType.OPTIONAL),
            dependency("render", "subtitle", WorkflowDefinition.DependencyType.OPTIONAL)
        );
        when(workflowRepository.findLockedById("workflow-1")).thenReturn(Optional.of(workflow));
        when(taskRepository.findLockedById("render")).thenReturn(Optional.of(render));
        when(dependencyRepository.findByTaskRunId("render")).thenReturn(dependencies);
        when(artifactRepository.findByProducerTaskRunIdIn(List.of("timeline", "bgm", "subtitle"))).thenReturn(List.of(
            artifact("timeline-artifact", "timeline", "TIMELINE", "file:///runtime/timeline.json"),
            artifact("bgm-artifact", "bgm", "BGM_AUDIO", "file:///runtime/bgm.mp3"),
            artifact("subtitle-artifact", "subtitle", "SUBTITLE_SRT", "file:///runtime/subtitle.srt")
        ));

        var request = service.prepareDispatch("workflow-1", "render").request();

        assertThat(request.tool()).isEqualTo("video.render");
        assertThat(request.version()).isEqualTo("1.1.0");
        assertThat(request.inputs()).containsOnlyKeys("timeline", "bgm", "subtitle");
        assertThat(request.inputs().get("timeline").artifactId()).isEqualTo("timeline-artifact");
        assertThat(request.inputs().get("bgm").artifactId()).isEqualTo("bgm-artifact");
        assertThat(request.inputs().get("subtitle").artifactId()).isEqualTo("subtitle-artifact");
    }

    @Test
    void applyingCustomStoryPlanContinuesOriginalWorkflowAndResetsDescendants() {
        var workflow = workflow(false);
        workflow.pause("gate_story_edit");
        var story = succeededTask("story", "story_plan", "planning.story-template");
        var highlight = succeededTask("highlight", "highlight_selection", "decision.highlight-select");
        var timeline = succeededTask("timeline", "timeline_compose", "timeline.compose");
        var bgm = pendingTask("bgm", "bgm_select", "audio.bgm-select");
        var subtitle = pendingTask("subtitle", "subtitle_compose", "subtitle.compose");
        var render = pendingTask("render", "video_render", "video.render");
        var tasks = List.of(story, highlight, timeline, bgm, subtitle, render);
        var dependencies = List.of(
            dependency("highlight", "story", WorkflowDefinition.DependencyType.REQUIRED),
            dependency("timeline", "highlight", WorkflowDefinition.DependencyType.REQUIRED),
            dependency("bgm", "story", WorkflowDefinition.DependencyType.REQUIRED),
            dependency("bgm", "timeline", WorkflowDefinition.DependencyType.REQUIRED),
            dependency("subtitle", "timeline", WorkflowDefinition.DependencyType.REQUIRED),
            dependency("render", "timeline", WorkflowDefinition.DependencyType.REQUIRED),
            dependency("render", "bgm", WorkflowDefinition.DependencyType.OPTIONAL),
            dependency("render", "subtitle", WorkflowDefinition.DependencyType.OPTIONAL)
        );
        when(workflowRepository.findLockedById("workflow-1")).thenReturn(Optional.of(workflow));
        when(taskRepository.findByWorkflowRunIdOrderByCreatedAtAsc("workflow-1")).thenReturn(tasks);
        when(dependencyRepository.findByTaskRunIdIn(anyList())).thenReturn(dependencies);
        when(artifactRepository.save(any(ArtifactEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var runId = service.applyCustomStoryPlan("workflow-1", Map.of(
            "schemaVersion", "1.0",
            "template", "MANUAL_EDIT",
            "targetDurationMs", 1000,
            "maxShots", 1,
            "beats", List.of()
        ));

        assertThat(runId).isEqualTo("workflow-1");
        assertThat(workflow.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(workflow.hasCompletedGate("gate_story_edit")).isTrue();
        assertThat(highlight.getStatus()).isEqualTo(TaskStatus.READY);
        assertThat(timeline.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(bgm.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(subtitle.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(render.getStatus()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void applyingCustomTimelineContinuesOriginalWorkflowAndResetsRenderDescendants() {
        var workflow = workflow(false);
        workflow.pause("gate_story_edit");
        workflow.completeCurrentGate();
        workflow.resume();
        workflow.pause("gate_timeline_preview");
        var story = succeededTask("story", "story_plan", "planning.story-template");
        var timeline = succeededTask("timeline", "timeline_compose", "timeline.compose");
        var bgm = succeededTask("bgm", "bgm_select", "audio.bgm-select");
        var subtitle = pendingTask("subtitle", "subtitle_compose", "subtitle.compose");
        var render = pendingTask("render", "video_render", "video.render");
        var tasks = List.of(story, timeline, bgm, subtitle, render);
        var dependencies = List.of(
            dependency("bgm", "story", WorkflowDefinition.DependencyType.REQUIRED),
            dependency("bgm", "timeline", WorkflowDefinition.DependencyType.REQUIRED),
            dependency("subtitle", "timeline", WorkflowDefinition.DependencyType.REQUIRED),
            dependency("render", "timeline", WorkflowDefinition.DependencyType.REQUIRED),
            dependency("render", "bgm", WorkflowDefinition.DependencyType.OPTIONAL),
            dependency("render", "subtitle", WorkflowDefinition.DependencyType.OPTIONAL)
        );
        when(workflowRepository.findLockedById("workflow-1")).thenReturn(Optional.of(workflow));
        when(taskRepository.findByWorkflowRunIdOrderByCreatedAtAsc("workflow-1")).thenReturn(tasks);
        when(dependencyRepository.findByTaskRunIdIn(anyList())).thenReturn(dependencies);
        when(artifactRepository.save(any(ArtifactEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var runId = service.applyCustomTimeline("workflow-1", Map.of(
            "schemaVersion", "1.1",
            "durationMs", 1000,
            "tracks", List.of(Map.of(
                "type", "VIDEO",
                "clips", List.of(Map.of("clipId", "clip-1"))
            ))
        ));

        assertThat(runId).isEqualTo("workflow-1");
        assertThat(workflow.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(workflow.hasCompletedGate("gate_timeline_preview")).isTrue();
        assertThat(bgm.getStatus()).isEqualTo(TaskStatus.READY);
        assertThat(subtitle.getStatus()).isEqualTo(TaskStatus.READY);
        assertThat(render.getStatus()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void selectingBgmCreatesImmutableAudioArtifactAndContinuesOriginalWorkflow() {
        var workflow = workflow(false);
        workflow.pause("gate_bgm_review");
        var bgm = succeededTask("bgm", "bgm_select", "audio.bgm-select");
        var render = pendingTask("render", "video_render", "video.render");
        var candidate = artifact(
            "candidate-1", "bgm", "BGM_CANDIDATE", "file:///runtime/music/candidate-1.mp3"
        );
        when(workflowRepository.findLockedById("workflow-1")).thenReturn(Optional.of(workflow));
        when(taskRepository.findByWorkflowRunIdOrderByCreatedAtAsc("workflow-1"))
            .thenReturn(List.of(bgm, render));
        when(artifactRepository.findByExternalArtifactId("candidate-1")).thenReturn(Optional.of(candidate));
        when(artifactRepository.save(any(ArtifactEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(dependencyRepository.findByTaskRunIdIn(anyList())).thenReturn(List.of(
            dependency("render", "bgm", WorkflowDefinition.DependencyType.OPTIONAL)
        ));

        var runId = service.selectBgmCandidate("workflow-1", "candidate-1");

        assertThat(runId).isEqualTo("workflow-1");
        assertThat(workflow.hasCompletedGate("gate_bgm_review")).isTrue();
        assertThat(workflow.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(render.getStatus()).isEqualTo(TaskStatus.READY);
        verify(artifactRepository).save(argThat(artifact ->
            "BGM_AUDIO".equals(artifact.getType())
                && "bgm".equals(artifact.getProducerTaskRunId())
                && candidate.getStorageUri().equals(artifact.getStorageUri())
                && candidate.getContentHash().equals(artifact.getContentHash())
        ));
        verify(artifactRepository).save(argThat(artifact ->
            "BGM_SELECTION".equals(artifact.getType())
                && artifact.getMetadataJson().contains("SELECTED")
        ));
    }

    @Test
    void skippingBgmContinuesWithoutCreatingAudioArtifact() {
        var workflow = workflow(false);
        workflow.pause("gate_bgm_review");
        var bgm = succeededTask("bgm", "bgm_select", "audio.bgm-select");
        var render = pendingTask("render", "video_render", "video.render");
        when(workflowRepository.findLockedById("workflow-1")).thenReturn(Optional.of(workflow));
        when(taskRepository.findByWorkflowRunIdOrderByCreatedAtAsc("workflow-1"))
            .thenReturn(List.of(bgm, render));
        when(dependencyRepository.findByTaskRunIdIn(anyList())).thenReturn(List.of(
            dependency("render", "bgm", WorkflowDefinition.DependencyType.OPTIONAL)
        ));

        var runId = service.continueWithoutBgm("workflow-1");

        assertThat(runId).isEqualTo("workflow-1");
        assertThat(workflow.hasCompletedGate("gate_bgm_review")).isTrue();
        assertThat(render.getStatus()).isEqualTo(TaskStatus.READY);
        verify(artifactRepository).save(argThat(artifact ->
            "BGM_SELECTION".equals(artifact.getType())
                && artifact.getMetadataJson().contains("NONE")
        ));
    }

    @Test
    void renderDispatchOmitsHistoricalBgmWhenLatestSelectionIsNone() {
        var workflow = workflow(true);
        var render = pendingTask("render", "video_render", "video.render");
        render.markReady();
        var dependencies = List.of(
            dependency("render", "timeline", WorkflowDefinition.DependencyType.REQUIRED),
            dependency("render", "bgm", WorkflowDefinition.DependencyType.OPTIONAL)
        );
        when(workflowRepository.findLockedById("workflow-1")).thenReturn(Optional.of(workflow));
        when(taskRepository.findLockedById("render")).thenReturn(Optional.of(render));
        when(dependencyRepository.findByTaskRunId("render")).thenReturn(dependencies);
        when(artifactRepository.findByProducerTaskRunIdIn(List.of("timeline", "bgm"))).thenReturn(List.of(
            artifact("timeline-artifact", "timeline", "TIMELINE", "file:///runtime/timeline.json"),
            artifact("old-bgm", "bgm", "BGM_AUDIO", "file:///runtime/old-bgm.mp3"),
            artifactWithMetadata(
                "selection-none", "bgm", "BGM_SELECTION", "file:///runtime/selection.json",
                "{\"mode\":\"NONE\"}"
            )
        ));

        var request = service.prepareDispatch("workflow-1", "render").request();

        assertThat(request.inputs()).containsOnlyKeys("timeline");
    }

    @Test
    void snapshotOrdersNewestArtifactsFirst() {
        var workflow = workflow(true);
        var render = succeededTask("render", "video_render", "video.render");
        var oldVideo = artifact("old-video", "render", "RENDERED_VIDEO", "file:///runtime/old.mp4");
        var newVideo = artifact("new-video", "render", "RENDERED_VIDEO", "file:///runtime/new.mp4");
        ReflectionTestUtils.setField(oldVideo, "createdAt", Instant.parse("2026-07-28T00:00:00Z"));
        ReflectionTestUtils.setField(newVideo, "createdAt", Instant.parse("2026-07-28T00:01:00Z"));
        when(workflowRepository.findById("workflow-1")).thenReturn(Optional.of(workflow));
        when(taskRepository.findByWorkflowRunIdOrderByCreatedAtAsc("workflow-1")).thenReturn(List.of(render));
        when(dependencyRepository.findByTaskRunIdIn(anyList())).thenReturn(List.of());
        when(artifactRepository.findByProducerTaskRunId("render")).thenReturn(List.of(oldVideo, newVideo));
        when(workflowAssetRepository.findByWorkflowRunIdOrderByPositionIndexAsc("workflow-1"))
            .thenReturn(List.of());
        when(assetService.getRequired("asset-1")).thenReturn(new com.yizhixianyu.agentvideo.asset.AssetEntity(
            "project-1", "video.mp4", "file:///runtime/video.mp4", 1, "hash"
        ));

        var snapshot = service.getSnapshot("workflow-1");

        assertThat(snapshot.tasks().get(0).artifacts())
            .extracting(WorkflowExecutionService.ArtifactSnapshot::externalArtifactId)
            .containsExactly("new-video", "old-video");
    }

    private WorkflowRunEntity workflow(boolean autoMode) {
        var definition = template.create(ProxyQuality.HD_720P);
        try {
            var workflow = new WorkflowRunEntity(
                "project-1", "asset-1", "MULTI_ASSET_ANALYSIS", ProxyQuality.HD_720P,
                definition.definitionKey(), definition.definitionVersion(), objectMapper.writeValueAsString(definition)
            );
            ReflectionTestUtils.setField(workflow, "id", "workflow-1");
            workflow.setAutoMode(autoMode);
            workflow.start();
            return workflow;
        } catch (Exception exc) {
            throw new IllegalStateException(exc);
        }
    }

    private void stubWorkflow(
        WorkflowRunEntity workflow,
        List<TaskRunEntity> tasks,
        List<TaskDependencyEntity> dependencies
    ) {
        when(workflowRepository.findLockedById("workflow-1")).thenReturn(Optional.of(workflow));
        when(taskRepository.findByWorkflowRunIdOrderByCreatedAtAsc("workflow-1")).thenReturn(tasks);
        when(dependencyRepository.findByTaskRunIdIn(anyList())).thenReturn(dependencies);
    }

    private TaskRunEntity pendingTask(String id, String nodeKey, String toolName) {
        var task = new TaskRunEntity(
            "workflow-1", null, "workflow:" + nodeKey, nodeKey, toolName,
            toolName.equals("video.render") ? "1.1.0" : "1.0.0", "UPSTREAM_ARTIFACT", "{}"
        );
        ReflectionTestUtils.setField(task, "id", id);
        return task;
    }

    private TaskRunEntity succeededTask(String id, String nodeKey, String toolName) {
        var task = pendingTask(id, nodeKey, toolName);
        task.markReady();
        task.markDispatching();
        task.markRunning();
        task.markSucceeded();
        return task;
    }

    private TaskRunEntity failedTask(String id, String nodeKey, String toolName) {
        var task = pendingTask(id, nodeKey, toolName);
        task.markFailed("enhancement unavailable");
        return task;
    }

    private TaskDependencyEntity dependency(
        String taskId,
        String upstreamId,
        WorkflowDefinition.DependencyType type
    ) {
        return new TaskDependencyEntity(taskId, upstreamId, type);
    }

    private ArtifactEntity artifact(String externalId, String producerId, String type, String uri) {
        return artifactWithMetadata(externalId, producerId, type, uri, "{}");
    }

    private ArtifactEntity artifactWithMetadata(
        String externalId, String producerId, String type, String uri, String metadata
    ) {
        return new ArtifactEntity(
            externalId, "project-1", producerId, type, uri, "application/octet-stream", 1, "hash", metadata
        );
    }
}
