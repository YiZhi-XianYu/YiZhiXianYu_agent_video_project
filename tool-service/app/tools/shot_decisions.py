from __future__ import annotations

import hashlib
from collections.abc import Callable
from typing import Any

from app.core.models import ArtifactDescriptor, ToolExecutionRequest
from app.tools.artifact_json import matching_inputs, read_json_artifact, write_json_artifact
from app.tools.timeline_validator import TimelineValidator


class ShotRankingTool:
    name = "decision.shot-rank"
    version = "1.0.0"

    def manifest(self) -> dict[str, Any]:
        return _manifest(self, "Rank shots across all workflow Assets", ["SHOT_QUALITY"], ["SHOT_RANKING"])

    def execute(self, request: ToolExecutionRequest, report_progress: Callable[[int], None] | None = None) -> list[ArtifactDescriptor]:
        quality_inputs = sorted(matching_inputs(request.inputs, "quality"), key=lambda item: item.artifact_id)
        if not quality_inputs:
            raise ValueError("decision.shot-rank requires at least one SHOT_QUALITY Artifact")
        candidates = []
        for source in quality_inputs:
            payload = read_json_artifact(source)
            for shot in payload.get("shots") or []:
                duration_fit = _duration_fitness(int(shot["durationMs"]))
                rejection_reasons = _rejection_reasons(shot)
                base_score = (
                    0.72 * shot["qualityScore"]
                    + 0.13 * shot.get("motionInterest", 0.0)
                    + 0.10 * duration_fit
                    + 0.05 * shot.get("boundaryConfidence", 0.5)
                )
                candidates.append({
                    **shot,
                    "qualityArtifactId": source.artifact_id,
                    "durationFitness": round(duration_fit, 4),
                    "baseScore": round(base_score, 4),
                    "eligible": not rejection_reasons,
                    "rejectionReasons": rejection_reasons,
                })
        ranked = _diversified_ranking(candidates)
        for index, item in enumerate(ranked):
            item["rank"] = index + 1
            item["rankingReasons"] = _ranking_reasons(item)
        payload = {
            "schemaVersion": "1.0",
            "strategy": "DETERMINISTIC_MMR_QUALITY_MOTION_DIVERSITY_V2",
            "sourceQualityArtifactIds": [item.artifact_id for item in quality_inputs],
            "shotCount": len(ranked),
            "eligibleShotCount": sum(item["eligible"] for item in ranked),
            "thresholds": {"qualityScore": 0.45, "clarity": 0.25, "exposure": 0.25, "minDurationMs": 800},
            "shots": ranked,
        }
        return [write_json_artifact("SHOT_RANKING", "shot-ranking.json", payload, payload)]


class HighlightSelectionTool:
    name = "decision.highlight-select"
    version = "1.0.0"

    def manifest(self) -> dict[str, Any]:
        return _manifest(self, "Compile a deterministic Story Plan into a highlight set", ["STORY_PLAN"], ["HIGHLIGHT_SET"])

    def execute(self, request: ToolExecutionRequest, report_progress: Callable[[int], None] | None = None) -> list[ArtifactDescriptor]:
        story_inputs = matching_inputs(request.inputs, "story")
        if len(story_inputs) != 1:
            raise ValueError("decision.highlight-select requires one STORY_PLAN Artifact")
        story = read_json_artifact(story_inputs[0])
        selected = [
            {**shot, "selected": True}
            for beat in story.get("beats") or []
            for shot in beat.get("shots") or []
        ]
        used = sum(int(shot["selectedDurationMs"]) for shot in selected)
        payload = {
            "schemaVersion": "1.0",
            "strategy": "STORY_PLAN_COMPILATION_V1",
            "sourceStoryPlanArtifactId": story_inputs[0].artifact_id,
            "sourceRankingArtifactId": story["sourceRankingArtifactId"],
            "targetDurationMs": story["targetDurationMs"],
            "selectedDurationMs": used,
            "selectedShotCount": len(selected),
            "shots": selected,
        }
        return [write_json_artifact("HIGHLIGHT_SET", "highlight-set.json", payload, payload)]


class TimelineComposeTool:
    name = "timeline.compose"
    version = "1.0.0"

    def manifest(self) -> dict[str, Any]:
        return _manifest(self, "Compose a validated declarative video Timeline", ["HIGHLIGHT_SET"], ["TIMELINE"])

    def execute(self, request: ToolExecutionRequest, report_progress: Callable[[int], None] | None = None) -> list[ArtifactDescriptor]:
        highlight_inputs = matching_inputs(request.inputs, "highlights")
        if len(highlight_inputs) != 1:
            raise ValueError("timeline.compose requires one HIGHLIGHT_SET Artifact")
        highlights = read_json_artifact(highlight_inputs[0])
        width = int(request.parameters.get("width", 1920))
        height = int(request.parameters.get("height", 1080))
        fps = int(request.parameters.get("fps", 30))
        if width < 320 or height < 240 or fps < 1 or fps > 120:
            raise ValueError("Invalid Timeline canvas")
        clips = []
        timeline_in = 0
        for shot in highlights.get("shots") or []:
            duration = int(shot["sourceOutMs"]) - int(shot["sourceInMs"])
            if duration <= 0 or int(shot["sourceOutMs"]) > int(shot["endMs"]):
                raise ValueError("Highlight clip is outside its Shot range")
            clips.append({
                "clipId": f"clip_{shot['shotId']}",
                "shotId": shot["shotId"],
                "assetId": shot["sourceAssetId"],
                "sourceProxyArtifactId": shot["sourceProxyArtifactId"],
                "sourceInMs": shot["sourceInMs"],
                "sourceOutMs": shot["sourceOutMs"],
                "sourceShotStartMs": shot["startMs"],
                "sourceShotEndMs": shot["endMs"],
                "timelineInMs": timeline_in,
                "timelineOutMs": timeline_in + duration,
                "playbackRate": 1.0,
                "transitionIn": {"type": "CUT", "durationMs": 0},
                "selectionRank": shot["rank"],
                "storyRole": shot["storyRole"],
                "selectionReasons": shot["selectionReasons"],
            })
            timeline_in += duration
        identity_source = ";".join(
            f"{clip['assetId']}:{clip['sourceInMs']}:{clip['sourceOutMs']}" for clip in clips
        ) + f":{width}:{height}:{fps}"
        identity = hashlib.sha256(identity_source.encode("utf-8")).hexdigest()[:16]
        payload = {
            "timelineId": f"tl_{identity}",
            "version": 1,
            "schemaVersion": "1.0",
            "sourceHighlightArtifactId": highlight_inputs[0].artifact_id,
            "canvas": {"width": width, "height": height, "fps": fps},
            "durationMs": timeline_in,
            "tracks": [{"type": "VIDEO", "clips": clips}],
        }
        errors = TimelineValidator.validate(payload)
        if errors:
            raise ValueError("Timeline validation failed: " + "; ".join(errors))
        payload["validation"] = {"valid": True, "errors": []}
        return [write_json_artifact("TIMELINE", "timeline.json", payload, payload)]


def _manifest(tool: Any, description: str, inputs: list[str], outputs: list[str]) -> dict[str, Any]:
    return {
        "name": tool.name,
        "version": tool.version,
        "description": description,
        "executionMode": "ASYNC",
        "resourceClass": "CPU_LIGHT",
        "timeoutSeconds": 120,
        "supportsCancellation": False,
        "deterministic": True,
        "cacheable": True,
        "inputTypes": inputs,
        "outputTypes": outputs,
    }


def _ranking_reasons(shot: dict[str, Any]) -> list[str]:
    reasons = list(shot.get("reasonCodes") or [])
    if shot["durationFitness"] >= 0.8:
        reasons.append("USEFUL_SHOT_DURATION")
    if shot["nearDuplicatePenalty"] > 0:
        reasons.append("NEAR_DUPLICATE_PENALTY")
    if shot["assetBalancePenalty"] > 0:
        reasons.append("ASSET_BALANCE_PENALTY")
    if shot["temporalProximityPenalty"] > 0:
        reasons.append("TEMPORAL_PROXIMITY_PENALTY")
    reasons.extend(shot["rejectionReasons"])
    return reasons or ["DETERMINISTIC_SCORE"]


def _duration_fitness(duration_ms: int) -> float:
    if duration_ms < 800:
        return max(0.0, duration_ms / 800)
    if duration_ms <= 6000:
        return min(1.0, 0.65 + (duration_ms - 800) / 5200 * 0.35)
    return max(0.35, 1.0 - (duration_ms - 6000) / 24000 * 0.65)


def _rejection_reasons(shot: dict[str, Any]) -> list[str]:
    reasons = []
    if shot["qualityScore"] < 0.45:
        reasons.append("BELOW_QUALITY_THRESHOLD")
    if shot.get("clarity", shot["qualityScore"]) < 0.25:
        reasons.append("TOO_BLURRY")
    if shot.get("exposure", shot["qualityScore"]) < 0.25:
        reasons.append("POOR_EXPOSURE")
    if shot["durationMs"] < 800:
        reasons.append("TOO_SHORT")
    return reasons


def _diversified_ranking(candidates: list[dict[str, Any]]) -> list[dict[str, Any]]:
    remaining = [dict(item) for item in candidates]
    selected: list[dict[str, Any]] = []
    asset_counts: dict[str, int] = {item["sourceAssetId"]: 0 for item in candidates}
    while remaining:
        min_asset_count = min(asset_counts.values(), default=0)
        best_index = 0
        best_key: tuple[Any, ...] | None = None
        best_scores: tuple[float, float, float, float] | None = None
        for index, item in enumerate(remaining):
            similarities = [
                _fingerprint_similarity(item.get("visualFingerprint"), prior.get("visualFingerprint"))
                for prior in selected
            ]
            max_similarity = max(similarities, default=0.0)
            duplicate_penalty = max(0.0, (max_similarity - 0.82) / 0.18) * 0.22
            asset_prior = asset_counts.get(item["sourceAssetId"], 0)
            asset_penalty = min(0.14, max(0, asset_prior - min_asset_count) * 0.035)
            center = (item["startMs"] + item["endMs"]) / 2
            closest_same_asset = min((
                abs(center - (prior["startMs"] + prior["endMs"]) / 2)
                for prior in selected
                if prior["sourceAssetId"] == item["sourceAssetId"]
            ), default=60000)
            temporal_penalty = max(0.0, (8000 - closest_same_asset) / 8000) * 0.08
            eligibility_penalty = 0.75 if not item["eligible"] else 0.0
            final_score = max(
                0.0,
                item["baseScore"] - duplicate_penalty - asset_penalty - temporal_penalty - eligibility_penalty,
            )
            key = (
                final_score, item["baseScore"], -duplicate_penalty, -asset_penalty,
                item["sourceAssetId"], -item["startMs"], -item["endMs"], -item["index"],
            )
            if best_key is None or key > best_key:
                best_index = index
                best_key = key
                best_scores = (max_similarity, duplicate_penalty, asset_penalty, temporal_penalty)
        chosen = remaining.pop(best_index)
        assert best_scores is not None
        chosen.update({
            "maxVisualSimilarity": round(best_scores[0], 4),
            "nearDuplicatePenalty": round(best_scores[1], 4),
            "assetBalancePenalty": round(best_scores[2], 4),
            "temporalProximityPenalty": round(best_scores[3], 4),
            "finalScore": round(best_key[0], 4) if best_key is not None else 0.0,
        })
        selected.append(chosen)
        asset_counts[chosen["sourceAssetId"]] = asset_counts.get(chosen["sourceAssetId"], 0) + 1
    return selected


def _fingerprint_similarity(left: str | None, right: str | None) -> float:
    if not left or not right:
        return 0.0
    distance = (int(left, 16) ^ int(right, 16)).bit_count()
    return 1.0 - distance / 64
