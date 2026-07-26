from __future__ import annotations

import hashlib
import json
import logging
from collections.abc import Callable
from typing import Any

from app.core.models import ArtifactDescriptor, ToolExecutionRequest
from app.llm.provider import LlmError, get_provider
from app.tools.artifact_json import matching_inputs, read_json_artifact, write_json_artifact
from app.tools.timeline_validator import TimelineValidator

logger = logging.getLogger(__name__)


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


HIGHLIGHT_REVIEW_SYSTEM = """You are a professional video editor reviewing a Story Plan. Your job is to verify and optionally improve shot selections.

You will receive:
1. The current Story Plan with selected shots for each beat (HOOK, INTRO, JOURNEY, CLIMAX, ENDING)
2. A ranked candidate pool of ALL available shots with their quality scores and visual tags

Review criteria:
- VISUAL DIVERSITY: Adjacent shots should not look identical (different scenes, colors, compositions)
- ASSET BALANCE: Shots from different source videos should be well-distributed
- BEAT FIT: HOOK should be visually striking, ENDING should feel calm/complete, CLIMAX should be the most impressive
- QUALITY: Prefer higher qualityScore, clarity, and motionInterest
- EMOTIONAL ARC: The sequence should have a natural rise and fall in energy

You may suggest up to 3 changes by replacing specific shots with better alternatives from the candidate pool.
You may also confirm the current plan with no changes.

Return JSON: {"changes": [{"beatIndex": 0, "oldShotId": "s1", "newShotId": "s5", "reason": "..."}], "confidence": 0.85, "notes": "..."}"""

HIGHLIGHT_REVIEW_USER = """Story Plan:
{story_json}

Candidate Pool ({candidate_count} shots):
{ranking_json}

Review the selection. Suggest improvements or confirm it is good."""


class HighlightSelectionTool:
    name = "decision.highlight-select"
    version = "1.0.0"

    def manifest(self) -> dict[str, Any]:
        return _manifest(
            self,
            "Compile a Story Plan into highlights with optional LLM refinement",
            ["STORY_PLAN", "SHOT_RANKING"],
            ["HIGHLIGHT_SET"],
        )

    def execute(self, request: ToolExecutionRequest, report_progress: Callable[[int], None] | None = None) -> list[ArtifactDescriptor]:
        story_inputs = matching_inputs(request.inputs, "story")
        if len(story_inputs) != 1:
            raise ValueError("decision.highlight-select requires one STORY_PLAN Artifact")
        story = read_json_artifact(story_inputs[0])

        ranking_inputs = matching_inputs(request.inputs, "ranking")
        ranking_data: dict[str, Any] | None = None
        if ranking_inputs:
            ranking_data = read_json_artifact(ranking_inputs[0])

        if report_progress is not None:
            report_progress(10)

        strategy = "STORY_PLAN_COMPILATION_V1"
        llm_changes: list[dict[str, Any]] = []

        provider = get_provider()
        if provider.name != "noop" and ranking_data is not None:
            try:
                changes = self._llm_review(provider, story, ranking_data)
                if changes is not None:
                    llm_changes = changes
                    strategy = "LLM_REFINED_V1"
                    if report_progress is not None:
                        report_progress(50)
            except (LlmError, Exception) as exc:
                logger.warning("LLM highlight review failed, using plan as-is: %s", exc)

        selected = self._compile_shots(story, llm_changes, ranking_data)

        if report_progress is not None:
            report_progress(70)

        used = sum(int(shot["selectedDurationMs"]) for shot in selected)
        payload = {
            "schemaVersion": "1.0",
            "strategy": strategy,
            "sourceStoryPlanArtifactId": story_inputs[0].artifact_id,
            "sourceRankingArtifactId": story.get("sourceRankingArtifactId", ""),
            "targetDurationMs": story["targetDurationMs"],
            "selectedDurationMs": used,
            "selectedShotCount": len(selected),
            "llmRefinements": llm_changes,
            "shots": selected,
        }

        if report_progress is not None:
            report_progress(95)

        return [write_json_artifact("HIGHLIGHT_SET", "highlight-set.json", payload, payload)]

    def _llm_review(self, provider: Any, story: dict[str, Any], ranking: dict[str, Any]) -> list[dict[str, Any]] | None:
        """Ask LLM to review and optionally refine the shot selection."""
        # Build compact representations
        story_beats = []
        for bi, beat in enumerate(story.get("beats", [])):
            shots_info = []
            for shot in beat.get("shots", []):
                shots_info.append({
                    "shotId": shot["shotId"],
                    "qualityScore": shot.get("qualityScore", 0),
                    "motionInterest": shot.get("motionInterest", 0),
                    "composition": shot.get("composition", 0),
                    "durationMs": shot.get("selectedDurationMs", 0),
                    "sourceAssetId": shot.get("sourceAssetId", ""),
                    "sceneTags": self._summarize_tags(shot, "scene"),
                    "objectTags": self._summarize_tags(shot, "object"),
                })
            story_beats.append({"beatIndex": bi, "role": beat["role"], "shots": shots_info})

        candidates = []
        for shot in ranking.get("shots", []):
            if not shot.get("eligible", True):
                continue
            candidates.append({
                "shotId": shot["shotId"],
                "qualityScore": shot.get("qualityScore", 0),
                "motionInterest": shot.get("motionInterest", 0),
                "composition": shot.get("composition", 0),
                "durationMs": shot.get("durationMs", 0),
                "sourceAssetId": shot.get("sourceAssetId", ""),
                "rank": shot.get("rank", 999),
                "finalScore": shot.get("finalScore", 0),
                "sceneTags": self._summarize_tags(shot, "scene"),
                "objectTags": self._summarize_tags(shot, "object"),
                "personTags": self._summarize_tags(shot, "person"),
            })

        user_prompt = HIGHLIGHT_REVIEW_USER.format(
            story_json=json.dumps(story_beats, ensure_ascii=False, indent=2),
            candidate_count=len(candidates),
            ranking_json=json.dumps(candidates[:40], ensure_ascii=False, indent=2),
        )

        result = provider.generate_json(
            HIGHLIGHT_REVIEW_SYSTEM, user_prompt, {},
            temperature=0.3, max_tokens=2048, request_id="highlight-review",
        )

        if not isinstance(result, dict):
            return None

        changes = result.get("changes", [])
        if not isinstance(changes, list) or len(changes) == 0:
            logger.info("LLM highlight review: no changes suggested (plan confirmed)")
            return []

        validated = []
        for c in changes:
            if isinstance(c, dict) and "oldShotId" in c and "newShotId" in c:
                validated.append({
                    "beatIndex": c.get("beatIndex", 0),
                    "oldShotId": c["oldShotId"],
                    "newShotId": c["newShotId"],
                    "reason": c.get("reason", ""),
                })

        logger.info("LLM highlight review: %d changes suggested", len(validated))
        return validated

    def _compile_shots(
        self,
        story: dict[str, Any],
        llm_changes: list[dict[str, Any]],
        ranking_data: dict[str, Any] | None,
    ) -> list[dict[str, Any]]:
        """Compile story plan shots, applying LLM refinements where valid."""
        change_map: dict[str, str] = {}
        for c in llm_changes:
            change_map[c["oldShotId"]] = c["newShotId"]

        ranking_map: dict[str, dict[str, Any]] = {}
        if ranking_data is not None:
            for shot in ranking_data.get("shots", []):
                ranking_map[shot["shotId"]] = shot

        selected: list[dict[str, Any]] = []
        used_ids: set[str] = set()

        for beat in story.get("beats", []):
            for shot in beat.get("shots", []):
                sid = shot["shotId"]
                new_id = change_map.get(sid, sid)

                if new_id != sid and new_id in ranking_map and new_id not in used_ids:
                    replacement = ranking_map[new_id]
                    repl_start = int(replacement.get("startMs", 0))
                    repl_end = int(replacement.get("endMs", 10000))
                    desired_dur = shot.get("selectedDurationMs", 7000)
                    actual_dur = min(desired_dur, repl_end - repl_start)
                    shot = {
                        **replacement,
                        "storyRole": beat["role"],
                        "sourceInMs": repl_start,
                        "sourceOutMs": repl_start + actual_dur,
                        "selectedDurationMs": actual_dur,
                        "selectionReasons": [
                            f"STORY_ROLE_{beat['role']}",
                            "LLM_REFINED",
                        ],
                        "selected": True,
                    }

                used_ids.add(new_id if new_id != sid else sid)
                selected.append({**shot, "selected": True})

        # ── Redistribute duration lost from LLM-replaced shots ──
        target_ms = story.get("targetDurationMs", 0)
        total_selected = sum(int(s["selectedDurationMs"]) for s in selected)
        deficit = target_ms - total_selected
        if deficit > 0:
            # Extend shots that still have headroom (prefer non-replaced)
            replaced_ids = set(change_map.values())
            ordered = sorted(
                selected,
                key=lambda s: (0 if s["shotId"] in replaced_ids else 1, s.get("finalScore", 0)),
                reverse=True,
            )
            for shot in ordered:
                if deficit <= 0:
                    break
                shot_end = int(shot["endMs"])
                capacity = shot_end - int(shot["sourceInMs"]) - shot["selectedDurationMs"]
                if capacity <= 0:
                    continue
                extra = min(deficit, capacity)
                shot["selectedDurationMs"] += extra
                shot["sourceOutMs"] = shot["sourceInMs"] + shot["selectedDurationMs"]
                deficit -= extra
            if deficit > 0:
                logger.info(
                    "Highlight compilation deficit: %d ms (%.1f%% of target), within tolerance",
                    deficit, deficit / target_ms * 100,
                )

        return selected

    @staticmethod
    def _summarize_tags(shot: dict[str, Any], tag_type: str) -> list[str]:
        """Extract top tag labels from a shot's tag arrays."""
        tags = shot.get(f"{tag_type}Tags", [])
        if not tags:
            return []
        return [t.get("label", str(t)) if isinstance(t, dict) else str(t) for t in tags[:3]]


class TimelineComposeTool:
    name = "timeline.compose"
    version = "1.1.0"

    def manifest(self) -> dict[str, Any]:
        return _manifest(self, "Compose a validated declarative video Timeline with transitions", ["HIGHLIGHT_SET"], ["TIMELINE"])

    def execute(self, request: ToolExecutionRequest, report_progress: Callable[[int], None] | None = None) -> list[ArtifactDescriptor]:
        highlight_inputs = matching_inputs(request.inputs, "highlights")
        if len(highlight_inputs) != 1:
            raise ValueError("timeline.compose requires one HIGHLIGHT_SET Artifact")
        highlights = read_json_artifact(highlight_inputs[0])
        width = int(request.parameters.get("width", 1920))
        height = int(request.parameters.get("height", 1080))
        fps = int(request.parameters.get("fps", 30))
        transition_style = str(request.parameters.get("transitionStyle", "CUT"))
        if width < 320 or height < 240 or fps < 1 or fps > 120:
            raise ValueError("Invalid Timeline canvas")
        clips = []
        timeline_in = 0
        prev_story_role = None
        for shot in highlights.get("shots") or []:
            source_in = int(shot["sourceInMs"])
            source_out = int(shot["sourceOutMs"])
            duration = source_out - source_in
            if duration <= 0 or source_out > int(shot["endMs"]):
                raise ValueError("Highlight clip is outside its Shot range")

            story_role = shot.get("storyRole", "")
            transition = _assign_transition(transition_style, len(clips), story_role, prev_story_role)
            transition_duration = int(transition["durationMs"])
            if transition["type"] == "CROSS_DISSOLVE":
                if source_out + transition_duration <= int(shot["endMs"]):
                    source_out += transition_duration
                    duration += transition_duration
                    timeline_in -= transition_duration
                else:
                    transition = {"type": "CUT", "durationMs": 0}

            clips.append({
                "clipId": f"clip_{shot['shotId']}",
                "shotId": shot["shotId"],
                "assetId": shot["sourceAssetId"],
                "sourceProxyArtifactId": shot["sourceProxyArtifactId"],
                "sourceInMs": source_in,
                "sourceOutMs": source_out,
                "sourceShotStartMs": shot["startMs"],
                "sourceShotEndMs": shot["endMs"],
                "timelineInMs": timeline_in,
                "timelineOutMs": timeline_in + duration,
                "playbackRate": 1.0,
                "transitionIn": transition,
                "selectionRank": shot["rank"],
                "storyRole": story_role,
                "selectionReasons": shot["selectionReasons"],
            })
            timeline_in += duration
            prev_story_role = story_role
        identity_source = ";".join(
            f"{clip['assetId']}:{clip['sourceInMs']}:{clip['sourceOutMs']}" for clip in clips
        ) + f":{width}:{height}:{fps}"
        identity = hashlib.sha256(identity_source.encode("utf-8")).hexdigest()[:16]
        payload = {
            "timelineId": f"tl_{identity}",
            "version": 1,
            "schemaVersion": "1.1",
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


def _assign_transition(
    style: str,
    clip_index: int,
    story_role: str,
    prev_story_role: str | None,
) -> dict[str, Any]:
    """Heuristic transition assignment based on style, position, and beat boundaries.

    - "CUT": all transitions are CUT (backward-compatible default)
    - "FADE": first clip gets FADE (300ms), rest CUT
    - "CROSS_DISSOLVE": beat boundaries get CROSS_DISSOLVE (500ms), within-beat get CUT
    """
    if style == "CUT":
        return {"type": "CUT", "durationMs": 0}
    if style == "FADE":
        if clip_index == 0:
            return {"type": "FADE", "durationMs": 300}
        return {"type": "CUT", "durationMs": 0}
    if style == "CROSS_DISSOLVE":
        # First clip: FADE for a gentle opening
        if clip_index == 0:
            return {"type": "FADE", "durationMs": 300}
        # Beat boundary: CROSS_DISSOLVE for narrative flow
        if prev_story_role is not None and story_role != prev_story_role:
            return {"type": "CROSS_DISSOLVE", "durationMs": 500}
        return {"type": "CUT", "durationMs": 0}
    # Unknown style: default to CUT
    return {"type": "CUT", "durationMs": 0}


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
