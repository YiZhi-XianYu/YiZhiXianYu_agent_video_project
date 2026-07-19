package com.yizhixianyu.agentvideo.workflow;

import com.yizhixianyu.agentvideo.execution.ProxyQuality;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowDefinitionValidatorTest {

    private final WorkflowDefinitionValidator validator = new WorkflowDefinitionValidator();
    private final MultiAssetAnalysisTemplate template = new MultiAssetAnalysisTemplate();

    @Test
    void acceptsTheMultiAssetAnalysisTemplate() {
        assertThatCode(() -> validator.validate(template.create(ProxyQuality.FHD_1080P)))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsCycles() {
        var definition = new WorkflowDefinition(
            "INVALID_CYCLE", 1,
            List.of(
                node("a1", "video.probe", WorkflowDefinition.InputBinding.PROJECT_ASSET),
                node("b1", "video.proxy-generate", WorkflowDefinition.InputBinding.UPSTREAM_ARTIFACT)
            ),
            List.of(new WorkflowDefinition.Edge("a1", "b1"), new WorkflowDefinition.Edge("b1", "a1"))
        );

        assertThatThrownBy(() -> validator.validate(definition))
            .hasMessageContaining("root node");
    }

    @Test
    void rejectsUnknownTools() {
        var definition = new WorkflowDefinition(
            "INVALID_TOOL", 1,
            List.of(new WorkflowDefinition.Node(
                "unknown_tool", "video.unknown", "1.0.0",
                WorkflowDefinition.InputBinding.PROJECT_ASSET, Map.of()
            )),
            List.of()
        );

        assertThatThrownBy(() -> validator.validate(definition))
            .hasMessageContaining("Unknown or disabled Tool");
    }

    @Test
    void rejectsUpstreamBindingsWithoutDependencies() {
        var definition = new WorkflowDefinition(
            "INVALID_BINDING", 1,
            List.of(node("shot_detect", "video.shot-detect", WorkflowDefinition.InputBinding.UPSTREAM_ARTIFACT)),
            List.of()
        );

        assertThatThrownBy(() -> validator.validate(definition))
            .hasMessageContaining("requires an upstream Artifact");
    }

    private WorkflowDefinition.Node node(
        String nodeKey,
        String toolName,
        WorkflowDefinition.InputBinding binding
    ) {
        var parameters = "video.proxy-generate".equals(toolName) ? Map.<String, Object>of("quality", "1080P")
            : "video.shot-detect".equals(toolName) ? Map.<String, Object>of("sceneThreshold", 0.3)
            : Map.<String, Object>of();
        return new WorkflowDefinition.Node(nodeKey, toolName, "1.0.0", binding, parameters);
    }
}
