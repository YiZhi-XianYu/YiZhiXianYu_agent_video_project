from app.registry.registry import registry


EXPECTED_WORKFLOW_TOOLS = {
    "video.probe": "1.0.0",
    "video.proxy-generate": "1.0.0",
    "video.shot-detect": "1.0.0",
    "vision.quality-score": "1.0.0",
    "vision.vlm-analyze": "1.0.0",
    "audio.source-transcribe": "1.0.0",
    "decision.shot-rank": "1.0.0",
    "planning.story-template": "1.0.0",
    "decision.highlight-select": "1.0.0",
    "timeline.compose": "1.1.0",
    "audio.bgm-select": "1.0.0",
    "subtitle.compose": "1.0.0",
    "video.render": "1.1.0",
}


def test_main_workflow_tools_are_registered_with_java_versions() -> None:
    manifests = {item["name"]: item for item in registry.manifests()}

    assert {
        name: manifests[name]["version"] for name in EXPECTED_WORKFLOW_TOOLS
    } == EXPECTED_WORKFLOW_TOOLS


def test_cross_service_artifact_contracts_match_workflow_edges() -> None:
    manifests = {item["name"]: item for item in registry.manifests()}

    assert set(manifests["decision.highlight-select"]["inputTypes"]) == {
        "STORY_PLAN", "SHOT_RANKING",
    }
    assert set(manifests["audio.bgm-select"]["inputTypes"]) == {
        "STORY_PLAN", "TIMELINE",
    }
    assert set(manifests["subtitle.compose"]["inputTypes"]) == {
        "TIMELINE", "SOURCE_TRANSCRIPT",
    }
    assert set(manifests["video.render"]["inputTypes"]) == {
        "TIMELINE", "BGM_AUDIO", "SUBTITLE_SRT",
    }


def test_memory_heavy_workflow_tools_declare_resource_groups() -> None:
    manifests = {item["name"]: item for item in registry.manifests()}

    assert manifests["vision.vlm-analyze"]["resourceGroup"] == "MODEL"
    assert manifests["audio.source-transcribe"]["resourceGroup"] == "MODEL"
    assert manifests["audio.speech-transcribe"]["resourceGroup"] == "MODEL"
    assert manifests["video.render"]["resourceGroup"] == "RENDER"
