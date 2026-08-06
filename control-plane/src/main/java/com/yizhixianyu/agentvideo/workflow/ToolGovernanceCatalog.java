package com.yizhixianyu.agentvideo.workflow;

import java.util.Map;
import java.util.Set;

/** Local, deterministic governance catalog. It mirrors the Python Registry contract without a runtime network dependency. */
public final class ToolGovernanceCatalog {
    private ToolGovernanceCatalog() {}

    private static final Set<String> TOOLS = Set.of(
        "video.probe@1.0.0", "video.proxy-generate@1.0.0", "video.shot-detect@1.0.0",
        "vision.quality-score@1.0.0", "vision.scene-classify@1.0.0", "vision.object-detect@1.0.0",
        "vision.person-detect@1.0.0", "decision.shot-rank@1.0.0", "planning.story-template@1.0.0",
        "decision.highlight-select@1.0.0", "timeline.compose@1.0.0", "timeline.compose@1.1.0",
        "video.render@1.0.0", "video.render@1.1.0", "audio.bgm-select@1.0.0",
        "audio.speech-transcribe@1.0.0", "audio.source-transcribe@1.0.0", "subtitle.compose@1.0.0",
        "vision.vlm-analyze@1.0.0"
    );

    public static boolean contains(String name, String version) { return TOOLS.contains(name + "@" + version); }

    public static Policy policy(String name) {
        if ("video.render".equals(name)) return new Policy("REQUIRE_CONFIRMATION", "HIGH", true, 2, false, "RENDER");
        if ("audio.bgm-select".equals(name)) return new Policy("REQUIRE_CONFIRMATION", "LOW", true, 2, true, "LIGHT");
        if (Set.of("vision.scene-classify", "vision.object-detect", "vision.person-detect", "vision.vlm-analyze",
                   "audio.speech-transcribe", "audio.source-transcribe").contains(name)) {
            return new Policy("AUTO", "NONE", false, 2, true, "MODEL");
        }
        if ("vision.quality-score".equals(name)) return new Policy("AUTO", "NONE", false, 2, true, "MEDIA");
        if ("subtitle.compose".equals(name) || "timeline.compose".equals(name)) {
            return new Policy("AUTO", "LOW", false, 2, true, "LIGHT");
        }
        return new Policy("AUTO", "NONE", false, 2, true, "LIGHT");
    }

    public record Policy(String automationPolicy, String sideEffectLevel, boolean requiresUserConfirmation,
                          int maxAttempts, boolean allowFallback, String resourceGroup) {}
}
