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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

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
        return new ArtifactEntity(
            externalId, "project-1", producerId, type, uri, "application/octet-stream", 1, "hash", "{}"
        );
    }
}
