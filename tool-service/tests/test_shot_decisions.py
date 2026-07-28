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


def test_highlight_refinements_cannot_chain_into_duplicate_story_shots() -> None:
    roles = ["HOOK", "INTRO", "JOURNEY", "CLIMAX", "ENDING"]
    story_shots = []
    for index, role in enumerate(roles, start=1):
        shot = {
            **ranked_shot(f"s{index}", 0, 1000, index),
            "sourceAssetId": f"a{index}",
            "storyRole": role,
            "sourceInMs": 0,
            "sourceOutMs": 1000,
            "selectedDurationMs": 1000,
            "selectionReasons": [f"STORY_ROLE_{role}"],
        }
        story_shots.append(shot)

    story = {
        "targetDurationMs": 5000,
        "beats": [
            {"role": role, "shots": [story_shots[index]]}
            for index, role in enumerate(roles)
        ],
    }
    changes = [
        {"beatIndex": 1, "oldShotId": "s2", "newShotId": "s6", "reason": "valid alternative"},
        {"beatIndex": 3, "oldShotId": "s4", "newShotId": "s1", "reason": "collision"},
        {"beatIndex": 4, "oldShotId": "s5", "newShotId": "s4", "reason": "chain"},
    ]
    alternative = {
        **ranked_shot("s6", 0, 1000, 6),
        "sourceAssetId": "a6",
    }

    selected, applied, rejected = HighlightSelectionTool()._compile_shots(
        story,
        changes,
        {"shots": [*story_shots, alternative]},
    )

    selected_ids = [shot["shotId"] for shot in selected]
    assert selected_ids == ["s1", "s6", "s3", "s4", "s5"]
    assert len(selected_ids) == len(set(selected_ids))
    assert [(item["oldShotId"], item["newShotId"]) for item in applied] == [("s2", "s6")]
    assert {
        (item["oldShotId"], item["newShotId"], item["rejectionReason"])
        for item in rejected
    } == {
        ("s4", "s1", "ALREADY_SELECTED_IN_STORY_PLAN"),
        ("s5", "s4", "ALREADY_SELECTED_IN_STORY_PLAN"),
    }


def test_story_plan_reduces_duration_for_short_unique_footage(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(settings, "artifact_root", tmp_path / "artifacts")
    ranking = {
        "shots": [
            ranked_shot("s1", 0, 4000, 1),
            ranked_shot("s2", 4000, 4933, 2),
            ranked_shot("s3", 4933, 5866, 3),
            ranked_shot("s4", 5866, 6833, 4),
            ranked_shot("s5", 6833, 12236, 5),
        ]
    }
    ranking_input = write_input(tmp_path, "short-ranking.json", ranking)

    output = StoryPlanTool().execute(
        request(
            "planning.story-template",
            {"ranking": ranking_input},
            {"targetDurationMs": 30000, "maxShots": 12},
        )
    )[0]
    payload = json.loads(
        (tmp_path / "artifacts" / output.artifact_id / "story-plan.json").read_text(encoding="utf-8")
    )

    actual_duration = sum(beat["actualDurationMs"] for beat in payload["beats"])
    assert payload["targetDurationMs"] == actual_duration
    assert 5000 <= actual_duration <= 12236
    assert all(beat["targetDurationMs"] == beat["actualDurationMs"] for beat in payload["beats"])
    assert len({shot["shotId"] for beat in payload["beats"] for shot in beat["shots"]}) == 5
    assert payload["validation"] == {"valid": True, "errors": []}
    assert any("five-beat allocation constraints" in item for item in payload["assumptions"])


def test_story_plan_rebalances_beats_for_ten_second_short_footage(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(settings, "artifact_root", tmp_path / "artifacts")
    ranking_input = write_input(tmp_path, "ten-second-ranking.json", {
        "shots": [
            ranked_shot("s1", 0, 4000, 1),
            ranked_shot("s2", 4000, 4933, 2),
            ranked_shot("s3", 4933, 5866, 3),
            ranked_shot("s4", 5866, 6833, 4),
            ranked_shot("s5", 6833, 12236, 5),
        ]
    })

    output = StoryPlanTool().execute(
        request(
            "planning.story-template",
            {"ranking": ranking_input},
            {"targetDurationMs": 10000, "maxShots": 18},
        )
    )[0]
    payload = json.loads(
        (tmp_path / "artifacts" / output.artifact_id / "story-plan.json").read_text(encoding="utf-8")
    )

    assert payload["targetDurationMs"] == 10000
    assert sum(beat["actualDurationMs"] for beat in payload["beats"]) == 10000
    assert all(beat["targetDurationMs"] == beat["actualDurationMs"] for beat in payload["beats"])
    assert payload["validation"] == {"valid": True, "errors": []}


def test_story_plan_allows_empty_beats_when_fewer_than_five_shots_exist(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(settings, "artifact_root", tmp_path / "artifacts")
    for candidate_count in range(1, 5):
        ranking_input = write_input(tmp_path, f"{candidate_count}-shot-ranking.json", {
            "shots": [
                ranked_shot(f"s{candidate_count}-{index}", index * 6000, (index + 1) * 6000, index + 1)
                for index in range(candidate_count)
            ]
        })

        output = StoryPlanTool().execute(
            request(
                "planning.story-template",
                {"ranking": ranking_input},
                {"targetDurationMs": 5000, "maxShots": candidate_count},
            )
        )[0]
        payload = json.loads(
            (tmp_path / "artifacts" / output.artifact_id / "story-plan.json").read_text(encoding="utf-8")
        )

        assert len(payload["beats"]) == 5
        assert sum(bool(beat["shots"]) for beat in payload["beats"]) == candidate_count
        assert all(
            beat["targetDurationMs"] == 0 and beat["actualDurationMs"] == 0
            for beat in payload["beats"]
            if not beat["shots"]
        )
        selected_ids = [shot["shotId"] for beat in payload["beats"] for shot in beat["shots"]]
        assert len(selected_ids) == len(set(selected_ids)) == candidate_count
        assert sum(beat["actualDurationMs"] for beat in payload["beats"]) == payload["targetDurationMs"]
        assert payload["validation"] == {"valid": True, "errors": []}


def test_story_plan_rejects_rankings_without_a_usable_shot(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(settings, "artifact_root", tmp_path / "artifacts")
    ranking_input = write_input(tmp_path, "unusable-ranking.json", {
        "shots": [ranked_shot("too-short", 0, 599, 1)]
    })

    try:
        StoryPlanTool().execute(
            request(
                "planning.story-template",
                {"ranking": ranking_input},
                {"targetDurationMs": 5000, "maxShots": 12},
            )
        )
    except ValueError as exc:
        assert str(exc) == "Story Plan requires at least one unique Shot of 600 ms or longer"
    else:
        raise AssertionError("Expected an explicit unusable-shot error")


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


def test_llm_story_proposal_validator_enforces_candidate_ids_and_no_duplicates() -> None:
    proposal = {
        "schemaVersion": "1.1",
        "template": "TRAVEL_JOURNEY_V1",
        "targetDurationMs": 30000,
        "confidence": 0.8,
        "assumptions": [],
        "beats": [
            {"role": "HOOK", "shotIds": ["s1"], "reasonCodes": ["STRONG_OPENING"]},
            {"role": "INTRO", "shotIds": ["s2"], "reasonCodes": ["ESTABLISHING_CONTEXT"]},
            {"role": "JOURNEY", "shotIds": ["s3", "s4"], "reasonCodes": ["JOURNEY_CONTINUITY"]},
            {"role": "CLIMAX", "shotIds": ["s4", "unknown"], "reasonCodes": ["CLIMAX_CANDIDATE"]},
            {"role": "ENDING", "shotIds": ["s5"], "reasonCodes": ["CALM_ENDING"]},
        ],
    }

    errors = LlmStoryProposalValidator.validate(proposal, {"s1", "s2", "s3", "s4", "s5"}, 30000, 10)

    assert "beats[3] references an unknown shotId: unknown" in errors
    assert "shotId is duplicated across beats: s4" in errors
    # Duration sum is NOT checked here — _compile_llm_proposal handles it deterministically


def test_llm_story_proposal_validator_accepts_v1_0_legacy_format() -> None:
    """Backward-compatibility: v1.0 proposals with beat-level targetDurationMs are still accepted."""
    proposal = {
        "schemaVersion": "1.0",
        "template": "TRAVEL_JOURNEY_V1",
        "targetDurationMs": 30000,
        "confidence": 0.8,
        "assumptions": [],
        "beats": [
            {"role": "HOOK", "targetDurationMs": 3500, "shotIds": ["s1"], "reasonCodes": ["STRONG_OPENING"]},
            {"role": "INTRO", "targetDurationMs": 4500, "shotIds": ["s2"], "reasonCodes": ["ESTABLISHING_CONTEXT"]},
            {"role": "JOURNEY", "targetDurationMs": 9000, "shotIds": ["s3"], "reasonCodes": ["JOURNEY_CONTINUITY"]},
            {"role": "CLIMAX", "targetDurationMs": 9000, "shotIds": ["s4"], "reasonCodes": ["CLIMAX_CANDIDATE"]},
            {"role": "ENDING", "targetDurationMs": 4000, "shotIds": ["s5"], "reasonCodes": ["CALM_ENDING"]},
        ],
    }

    errors = LlmStoryProposalValidator.validate(proposal, {"s1", "s2", "s3", "s4", "s5"}, 30000, 10)
    # Should pass — v1.0 is still accepted, and duration arithmetic is not validated here
    assert len(errors) == 0


def test_llm_story_proposal_validator_rejects_hallucinated_shot_count() -> None:
    """Early-reject proposals with wildly excessive shot counts."""
    proposal = {
        "schemaVersion": "1.1",
        "template": "TRAVEL_JOURNEY_V1",
        "targetDurationMs": 30000,
        "confidence": 0.8,
        "assumptions": [],
        "beats": [
            {"role": "HOOK", "shotIds": ["s1"] * 10, "reasonCodes": ["STRONG_OPENING"] * 10},
            {"role": "INTRO", "shotIds": ["s2"] * 10, "reasonCodes": ["ESTABLISHING_CONTEXT"] * 10},
            {"role": "JOURNEY", "shotIds": ["s3"] * 10, "reasonCodes": ["JOURNEY_CONTINUITY"] * 10},
            {"role": "CLIMAX", "shotIds": ["s4"] * 10, "reasonCodes": ["CLIMAX_CANDIDATE"] * 10},
            {"role": "ENDING", "shotIds": ["s5"] * 10, "reasonCodes": ["CALM_ENDING"] * 10},
        ],
    }

    errors = LlmStoryProposalValidator.validate(
        proposal,
        {"s1", "s2", "s3", "s4", "s5"},
        30000,
        max_shots=10,
    )
    assert any("far exceeding maxShots" in e for e in errors)


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
    assert "clips[0] has an invalid Timeline position for CUT" in errors
    assert "Timeline duration does not match the VIDEO track" in errors


def test_timeline_validator_accepts_fade_and_cross_dissolve() -> None:
    timeline = {
        "canvas": {"width": 1280, "height": 720, "fps": 30},
        "durationMs": 6000,
        "tracks": [{"type": "VIDEO", "clips": [
            {
                "clipId": "clip-a", "shotId": "shot-a", "assetId": "a1",
                "sourceProxyArtifactId": "p1",
                "sourceInMs": 0, "sourceOutMs": 3000,
                "sourceShotStartMs": 0, "sourceShotEndMs": 5000,
                "timelineInMs": 0, "timelineOutMs": 3000,
                "playbackRate": 1.0,
                "transitionIn": {"type": "CUT", "durationMs": 0},
                "selectionRank": 1, "storyRole": "HOOK", "selectionReasons": [],
            },
            {
                "clipId": "clip-b", "shotId": "shot-b", "assetId": "a2",
                "sourceProxyArtifactId": "p2",
                "sourceInMs": 1000, "sourceOutMs": 4500,
                "sourceShotStartMs": 0, "sourceShotEndMs": 5000,
                "timelineInMs": 2500, "timelineOutMs": 6000,
                "playbackRate": 1.0,
                "transitionIn": {"type": "CROSS_DISSOLVE", "durationMs": 500},
                "selectionRank": 2, "storyRole": "INTRO", "selectionReasons": [],
            },
        ]}],
    }
    errors = TimelineValidator.validate(timeline)
    assert len(errors) == 0


def test_timeline_validator_rejects_fade_below_minimum() -> None:
    timeline = {
        "canvas": {"width": 1280, "height": 720, "fps": 30},
        "durationMs": 5000,
        "tracks": [{"type": "VIDEO", "clips": [
            {
                "clipId": "clip-a", "shotId": "shot-a", "assetId": "a1",
                "sourceProxyArtifactId": "p1",
                "sourceInMs": 0, "sourceOutMs": 2000,
                "sourceShotStartMs": 0, "sourceShotEndMs": 5000,
                "timelineInMs": 0, "timelineOutMs": 2000,
                "playbackRate": 1.0,
                "transitionIn": {"type": "CUT", "durationMs": 0},
                "selectionRank": 1, "storyRole": "HOOK", "selectionReasons": [],
            },
            {
                "clipId": "clip-b", "shotId": "shot-b", "assetId": "a2",
                "sourceProxyArtifactId": "p2",
                "sourceInMs": 0, "sourceOutMs": 3000,
                "sourceShotStartMs": 0, "sourceShotEndMs": 5000,
                "timelineInMs": 2000, "timelineOutMs": 5000,
                "playbackRate": 1.0,
                "transitionIn": {"type": "FADE", "durationMs": 100},
                "selectionRank": 2, "storyRole": "INTRO", "selectionReasons": [],
            },
        ]}],
    }
    errors = TimelineValidator.validate(timeline)
    assert any("out of range" in e and "FADE" in e for e in errors)


def test_timeline_validator_rejects_cross_dissolve_on_first_clip() -> None:
    timeline = {
        "canvas": {"width": 1280, "height": 720, "fps": 30},
        "durationMs": 3000,
        "tracks": [{"type": "VIDEO", "clips": [
            {
                "clipId": "clip-a", "shotId": "shot-a", "assetId": "a1",
                "sourceProxyArtifactId": "p1",
                "sourceInMs": 0, "sourceOutMs": 3000,
                "sourceShotStartMs": 0, "sourceShotEndMs": 5000,
                "timelineInMs": 0, "timelineOutMs": 3000,
                "playbackRate": 1.0,
                "transitionIn": {"type": "CROSS_DISSOLVE", "durationMs": 500},
                "selectionRank": 1, "storyRole": "HOOK", "selectionReasons": [],
            },
        ]}],
    }
    errors = TimelineValidator.validate(timeline)
    assert any("first clip" in e for e in errors)


def test_timeline_validator_accepts_audio_and_subtitle_tracks() -> None:
    timeline = {
        "canvas": {"width": 1280, "height": 720, "fps": 30},
        "durationMs": 3000,
        "tracks": [
            {"type": "VIDEO", "clips": [{
                "clipId": "clip-a", "shotId": "shot-a", "assetId": "a1",
                "sourceProxyArtifactId": "p1",
                "sourceInMs": 0, "sourceOutMs": 3000,
                "sourceShotStartMs": 0, "sourceShotEndMs": 5000,
                "timelineInMs": 0, "timelineOutMs": 3000,
                "playbackRate": 1.0,
                "transitionIn": {"type": "CUT", "durationMs": 0},
                "selectionRank": 1, "storyRole": "HOOK", "selectionReasons": [],
            }]},
            {"type": "AUDIO", "source": {"uri": "file:///bgm.mp3", "volume": 0.3}},
            {"type": "SUBTITLE", "source": {"uri": "file:///subs.srt", "format": "SRT"}},
        ],
    }
    errors = TimelineValidator.validate(timeline)
    assert len(errors) == 0


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


def ranked_shot(shot_id: str, start_ms: int, end_ms: int, rank: int) -> dict:
    score = 1.0 - rank * 0.05
    return {
        **quality_shot(shot_id, "short-asset", start_ms, end_ms, score, f"{rank:016x}"),
        "durationFitness": 0.8,
        "finalScore": score,
        "rank": rank,
        "eligible": True,
        "rankingReasons": ["USEFUL_SHOT_DURATION"],
    }
