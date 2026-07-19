import json

from app.core.config import settings
from app.core.models import ArtifactInput, ToolExecutionRequest
from app.tools.shot_decisions import HighlightSelectionTool, ShotRankingTool, TimelineComposeTool
from app.tools.story_plan import LlmStoryProposalValidator, StoryPlanTool, StoryProposalValidator
from app.tools.timeline_validator import TimelineValidator


def write_input(tmp_path, name: str, payload: dict) -> ArtifactInput:
    path = tmp_path / name
    path.write_text(json.dumps(payload), encoding="utf-8")
    return ArtifactInput(artifactId=name, uri=path.resolve().as_uri(), fileName=name)


def request(tool: str, inputs: dict[str, ArtifactInput], parameters: dict | None = None) -> ToolExecutionRequest:
    return ToolExecutionRequest(
        tool=tool,
        version="1.0.0",
        idempotencyKey=f"{tool}:test",
        inputs=inputs,
        parameters=parameters or {},
    )


def test_ranking_highlights_and_timeline_are_deterministic(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(settings, "artifact_root", tmp_path / "artifacts")
    quality_a = write_input(tmp_path, "quality-a", {"shots": [quality_shot("s1", "a1", 0, 7000, 0.92, "aaaaaaaaaaaaaaaa"), quality_shot("s3", "a1", 10000, 17000, 0.82, "cccccccccccccccc"), quality_shot("s5", "a1", 20000, 27000, 0.72, "f0f0f0f0f0f0f0f0"), quality_shot("s7", "a1", 30000, 37000, 0.66, "ff00ff00ff00ff00")]})
    quality_b = write_input(tmp_path, "quality-b", {"shots": [quality_shot("s2", "a2", 0, 7000, 0.88, "5555555555555555"), quality_shot("s4", "a2", 10000, 17000, 0.78, "3333333333333333"), quality_shot("s6", "a2", 20000, 27000, 0.68, "0f0f0f0f0f0f0f0f"), quality_shot("s8", "a2", 30000, 37000, 0.64, "00ff00ff00ff00ff")]})

    ranking_output = ShotRankingTool().execute(request("decision.shot-rank", {"quality": quality_a, "quality1": quality_b}))[0]
    ranking_payload = json.loads((tmp_path / "artifacts" / ranking_output.artifact_id / "shot-ranking.json").read_text(encoding="utf-8"))
    assert ranking_payload["eligibleShotCount"] == 8
    assert ranking_payload["shots"][0]["shotId"] == "s1"
    assert {shot["sourceAssetId"] for shot in ranking_payload["shots"][:2]} == {"a1", "a2"}

    ranking_input = ArtifactInput(artifactId=ranking_output.artifact_id, uri=ranking_output.uri, fileName="SHOT_RANKING")
    story_output = StoryPlanTool().execute(request("planning.story-template", {"ranking": ranking_input}, {"targetDurationMs": 30000, "maxShots": 10}))[0]
    story_payload = json.loads((tmp_path / "artifacts" / story_output.artifact_id / "story-plan.json").read_text(encoding="utf-8"))
    assert [beat["role"] for beat in story_payload["beats"]] == ["HOOK", "INTRO", "JOURNEY", "CLIMAX", "ENDING"]
    assert sum(beat["actualDurationMs"] for beat in story_payload["beats"]) == 30000
    selected_assets = [shot["sourceAssetId"] for beat in story_payload["beats"] for shot in beat["shots"]]
    assert abs(selected_assets.count("a1") - selected_assets.count("a2")) <= 1
    assert all(
        "STORY_ASSET_DIVERSITY" in shot["selectionReasons"]
        for beat in story_payload["beats"]
        for shot in beat["shots"]
    )

    story_input = ArtifactInput(artifactId=story_output.artifact_id, uri=story_output.uri, fileName="STORY_PLAN")
    highlight_output = HighlightSelectionTool().execute(request("decision.highlight-select", {"story": story_input}))[0]
    highlight_payload = json.loads((tmp_path / "artifacts" / highlight_output.artifact_id / "highlight-set.json").read_text(encoding="utf-8"))
    assert highlight_payload["selectedDurationMs"] == 30000

    highlight_input = ArtifactInput(artifactId=highlight_output.artifact_id, uri=highlight_output.uri, fileName="HIGHLIGHT_SET")
    timeline_output = TimelineComposeTool().execute(request("timeline.compose", {"highlights": highlight_input}, {"width": 1280, "height": 720, "fps": 30}))[0]
    timeline_payload = json.loads((tmp_path / "artifacts" / timeline_output.artifact_id / "timeline.json").read_text(encoding="utf-8"))
    assert timeline_payload["durationMs"] == 30000
    assert timeline_payload["validation"] == {"valid": True, "errors": []}
    assert timeline_payload["tracks"][0]["clips"][0]["storyRole"] == "HOOK"


def test_ranking_rejects_low_quality_and_penalizes_near_duplicates(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(settings, "artifact_root", tmp_path / "artifacts")
    quality = write_input(tmp_path, "quality", {"shots": [
        quality_shot("best", "a1", 0, 4000, 0.9, "aaaaaaaaaaaaaaaa"),
        quality_shot("duplicate", "a2", 0, 4000, 0.89, "aaaaaaaaaaaaaaaa"),
        quality_shot("diverse", "a2", 10000, 14000, 0.84, "5555555555555555"),
        quality_shot("bad", "a1", 20000, 20700, 0.2, "ffffffffffffffff"),
    ]})

    output = ShotRankingTool().execute(request("decision.shot-rank", {"quality": quality}))[0]
    payload = json.loads((tmp_path / "artifacts" / output.artifact_id / "shot-ranking.json").read_text(encoding="utf-8"))
    by_id = {shot["shotId"]: shot for shot in payload["shots"]}

    assert by_id["duplicate"]["nearDuplicatePenalty"] > 0
    assert by_id["diverse"]["rank"] < by_id["duplicate"]["rank"]
    assert by_id["bad"]["eligible"] is False
    assert "BELOW_QUALITY_THRESHOLD" in by_id["bad"]["rejectionReasons"]


def test_story_proposal_validator_rejects_unknown_and_duplicate_shots() -> None:
    proposal = {
        "template": "TRAVEL_JOURNEY_V1",
        "targetDurationMs": 5000,
        "beats": [
            {"role": role, "targetDurationMs": 1000, "actualDurationMs": 1000, "shots": [{
                "shotId": "unknown" if role == "HOOK" else "s1",
                "storyRole": role,
                "startMs": 0,
                "endMs": 2000,
                "sourceInMs": 0,
                "sourceOutMs": 1000,
                "selectedDurationMs": 1000,
            }]}
            for role in ["HOOK", "INTRO", "JOURNEY", "CLIMAX", "ENDING"]
        ],
    }

    errors = StoryProposalValidator.validate(proposal, {"s1", "s2"}, 5000, 10)

    assert any("not an allowed Ranking candidate" in error for error in errors)
    assert any("duplicated" in error for error in errors)


def test_llm_story_proposal_validator_enforces_candidate_ids_and_budget() -> None:
    proposal = {
        "schemaVersion": "1.0",
        "template": "TRAVEL_JOURNEY_V1",
        "targetDurationMs": 30000,
        "confidence": 0.8,
        "assumptions": [],
        "beats": [
            {"role": "HOOK", "targetDurationMs": 3500, "shotIds": ["s1"], "reasonCodes": ["STRONG_OPENING"]},
            {"role": "INTRO", "targetDurationMs": 4500, "shotIds": ["s2"], "reasonCodes": ["ESTABLISHING_CONTEXT"]},
            {"role": "JOURNEY", "targetDurationMs": 9000, "shotIds": ["s3", "s4"], "reasonCodes": ["JOURNEY_CONTINUITY"]},
            {"role": "CLIMAX", "targetDurationMs": 9000, "shotIds": ["s4", "unknown"], "reasonCodes": ["CLIMAX_CANDIDATE"]},
            {"role": "ENDING", "targetDurationMs": 3000, "shotIds": ["s5"], "reasonCodes": ["CALM_ENDING"]},
        ],
    }

    errors = LlmStoryProposalValidator.validate(proposal, {"s1", "s2", "s3", "s4", "s5"}, 30000, 10)

    assert "beats[3] references an unknown shotId: unknown" in errors
    assert "shotId is duplicated across beats: s4" in errors
    assert "beat durations do not exactly fill targetDurationMs" in errors


def test_timeline_validator_rejects_gaps_and_source_overflow() -> None:
    timeline = {
        "canvas": {"width": 1280, "height": 720, "fps": 30},
        "durationMs": 2000,
        "tracks": [{"type": "VIDEO", "clips": [{
            "clipId": "clip-1",
            "shotId": "shot-1",
            "assetId": "asset-1",
            "sourceProxyArtifactId": "proxy-1",
            "sourceInMs": 0,
            "sourceOutMs": 2500,
            "sourceShotStartMs": 0,
            "sourceShotEndMs": 2000,
            "timelineInMs": 500,
            "timelineOutMs": 3000,
            "playbackRate": 1.0,
            "transitionIn": {"type": "CUT", "durationMs": 0},
            "selectionRank": 1,
            "storyRole": "HOOK",
            "selectionReasons": [],
        }]}],
    }

    errors = TimelineValidator.validate(timeline)

    assert "clips[0] source range exceeds its Shot" in errors
    assert "clips[0] does not start at the previous Clip boundary" in errors
    assert "Timeline duration does not match the VIDEO track" in errors


def quality_shot(shot_id: str, asset_id: str, start_ms: int, end_ms: int, quality: float, fingerprint: str) -> dict:
    return {
        "shotId": shot_id,
        "sourceAssetId": asset_id,
        "sourceProxyArtifactId": f"proxy-{asset_id}",
        "keyframeArtifactId": f"keyframe-{shot_id}",
        "index": start_ms // 1000,
        "startMs": start_ms,
        "endMs": end_ms,
        "durationMs": end_ms - start_ms,
        "boundaryConfidence": 0.8,
        "clarity": quality,
        "exposure": quality,
        "stability": quality,
        "composition": quality,
        "motionLevel": 0.08,
        "motionInterest": 1.0,
        "qualityScore": quality,
        "visualFingerprint": fingerprint,
        "reasonCodes": ["HIGH_VISUAL_QUALITY"],
    }
