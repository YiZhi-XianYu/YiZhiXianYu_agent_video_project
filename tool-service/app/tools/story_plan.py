from __future__ import annotations

from collections.abc import Callable
from typing import Any

from app.core.models import ArtifactDescriptor, ToolExecutionRequest
from app.tools.artifact_json import matching_inputs, read_json_artifact, write_json_artifact


STORY_BEATS = [
    ("HOOK", 0.1167),
    ("INTRO", 0.15),
    ("JOURNEY", 0.30),
    ("CLIMAX", 0.30),
    ("ENDING", 0.1333),
]


class StoryPlanTool:
    name = "planning.story-template"
    version = "1.0.0"

    def manifest(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "version": self.version,
            "description": "Build a deterministic five-beat travel Story Plan from ranked Shots",
            "executionMode": "ASYNC",
            "resourceClass": "CPU_LIGHT",
            "timeoutSeconds": 120,
            "supportsCancellation": False,
            "deterministic": True,
            "cacheable": True,
            "inputTypes": ["SHOT_RANKING"],
            "outputTypes": ["STORY_PLAN"],
        }

    def execute(
        self,
        request: ToolExecutionRequest,
        report_progress: Callable[[int], None] | None = None,
    ) -> list[ArtifactDescriptor]:
        ranking_inputs = matching_inputs(request.inputs, "ranking")
        if len(ranking_inputs) != 1:
            raise ValueError("planning.story-template requires one SHOT_RANKING Artifact")
        ranking = read_json_artifact(ranking_inputs[0])
        target_duration = int(request.parameters.get("targetDurationMs", 30000))
        max_shots = int(request.parameters.get("maxShots", 12))
        if target_duration < 5000 or target_duration > 300000 or max_shots < 5 or max_shots > 100:
            raise ValueError("Invalid Story Plan duration or Shot limit")

        candidates = [shot for shot in ranking.get("shots") or [] if shot.get("eligible", True)]
        if len(candidates) < 5:
            candidates = list(ranking.get("shots") or [])
        budgets = _beat_budgets(target_duration)
        used_ids: set[str] = set()
        asset_counts = {shot["sourceAssetId"]: 0 for shot in candidates}
        beats = []
        remaining_slots = max_shots
        for beat_index, ((role, _), budget) in enumerate(zip(STORY_BEATS, budgets)):
            remaining_beats = len(STORY_BEATS) - beat_index
            slot_limit = max(1, remaining_slots - (remaining_beats - 1))
            available_count = sum(shot["shotId"] not in used_ids for shot in candidates)
            candidate_limit = available_count - (remaining_beats - 1)
            beat_shots = _select_for_beat(
                role,
                candidates,
                used_ids,
                asset_counts,
                budget,
                min(slot_limit, candidate_limit),
            )
            if not beat_shots:
                raise ValueError(f"No candidate Shot is available for Story beat {role}")
            used_ids.update(shot["shotId"] for shot in beat_shots)
            for shot in beat_shots:
                asset_id = shot["sourceAssetId"]
                asset_counts[asset_id] = asset_counts.get(asset_id, 0) + 1
            remaining_slots -= len(beat_shots)
            beats.append({
                "role": role,
                "targetDurationMs": budget,
                "actualDurationMs": sum(shot["selectedDurationMs"] for shot in beat_shots),
                "shots": beat_shots,
            })

        proposal = {
            "schemaVersion": "1.0",
            "template": "TRAVEL_JOURNEY_V1",
            "sourceRankingArtifactId": ranking_inputs[0].artifact_id,
            "targetDurationMs": target_duration,
            "maxShots": max_shots,
            "beats": beats,
            "assumptions": [
                "No semantic scene labels are available; beat roles use deterministic quality, motion and chronology signals.",
                "Story selection prefers the least-used source Asset when it can still fill the current beat exactly.",
                "Only eligible ranked Shots are preferred; rejected Shots are used only when fewer than five eligible candidates exist.",
            ],
        }
        errors = StoryProposalValidator.validate(
            proposal,
            {shot["shotId"] for shot in ranking.get("shots") or []},
            target_duration,
            max_shots,
        )
        if errors:
            raise ValueError("Story Plan validation failed: " + "; ".join(errors))
        proposal["validation"] = {"valid": True, "errors": []}
        return [write_json_artifact("STORY_PLAN", "story-plan.json", proposal, proposal)]


class StoryProposalValidator:
    ALLOWED_ROLES = [role for role, _ in STORY_BEATS]

    @classmethod
    def validate(
        cls,
        proposal: dict[str, Any],
        allowed_shot_ids: set[str],
        target_duration_ms: int,
        max_shots: int,
    ) -> list[str]:
        errors: list[str] = []
        if proposal.get("template") != "TRAVEL_JOURNEY_V1":
            errors.append("template must be TRAVEL_JOURNEY_V1")
        if proposal.get("targetDurationMs") != target_duration_ms:
            errors.append("targetDurationMs does not match the request")
        beats = proposal.get("beats")
        if not isinstance(beats, list) or [beat.get("role") for beat in beats] != cls.ALLOWED_ROLES:
            errors.append("beats must use the fixed HOOK, INTRO, JOURNEY, CLIMAX, ENDING order")
            return errors
        seen: set[str] = set()
        total_duration = 0
        shot_count = 0
        for beat_index, beat in enumerate(beats):
            target = beat.get("targetDurationMs")
            actual = beat.get("actualDurationMs")
            shots = beat.get("shots")
            if not isinstance(target, int) or target < 600:
                errors.append(f"beats[{beat_index}] has an invalid targetDurationMs")
            if not isinstance(shots, list) or not shots:
                errors.append(f"beats[{beat_index}] must contain at least one Shot")
                continue
            calculated = 0
            for shot_index, shot in enumerate(shots):
                prefix = f"beats[{beat_index}].shots[{shot_index}]"
                shot_id = shot.get("shotId")
                if shot_id not in allowed_shot_ids:
                    errors.append(f"{prefix}.shotId is not an allowed Ranking candidate")
                if shot_id in seen:
                    errors.append(f"{prefix}.shotId is duplicated")
                seen.add(shot_id)
                if shot.get("storyRole") != beat.get("role"):
                    errors.append(f"{prefix}.storyRole does not match its beat")
                source_in = shot.get("sourceInMs")
                source_out = shot.get("sourceOutMs")
                start = shot.get("startMs")
                end = shot.get("endMs")
                duration = shot.get("selectedDurationMs")
                if not all(isinstance(value, int) for value in (source_in, source_out, start, end, duration)):
                    errors.append(f"{prefix} time values must be integers")
                    continue
                if source_in < start or source_out > end or source_out <= source_in:
                    errors.append(f"{prefix} selection exceeds its source Shot")
                if duration != source_out - source_in or duration < 600:
                    errors.append(f"{prefix} selectedDurationMs is inconsistent")
                calculated += duration
                shot_count += 1
            if actual != calculated or target != calculated:
                errors.append(f"beats[{beat_index}] duration does not match its selected Shots")
            total_duration += calculated
        if shot_count > max_shots:
            errors.append("Story Plan exceeds maxShots")
        if total_duration != target_duration_ms:
            errors.append("Story Plan does not exactly fill targetDurationMs")
        return errors


class LlmStoryProposalValidator:
    ALLOWED_REASON_CODES = {
        "HIGH_VISUAL_QUALITY", "INTERESTING_MOTION", "STRONG_OPENING", "ESTABLISHING_CONTEXT",
        "JOURNEY_CONTINUITY", "CLIMAX_CANDIDATE", "CALM_ENDING", "ASSET_DIVERSITY",
    }

    @classmethod
    def validate(
        cls,
        proposal: dict[str, Any],
        allowed_shot_ids: set[str],
        requested_duration_ms: int,
        max_shots: int,
    ) -> list[str]:
        errors: list[str] = []
        if proposal.get("schemaVersion") != "1.0":
            errors.append("schemaVersion must be 1.0")
        if proposal.get("template") != "TRAVEL_JOURNEY_V1":
            errors.append("template must be TRAVEL_JOURNEY_V1")
        if proposal.get("targetDurationMs") != requested_duration_ms:
            errors.append("targetDurationMs does not match the request")
        confidence = proposal.get("confidence")
        if not isinstance(confidence, (int, float)) or confidence < 0 or confidence > 1:
            errors.append("confidence must be between 0 and 1")
        beats = proposal.get("beats")
        if not isinstance(beats, list) or [beat.get("role") for beat in beats] != StoryProposalValidator.ALLOWED_ROLES:
            errors.append("beats must use the fixed HOOK, INTRO, JOURNEY, CLIMAX, ENDING order")
            return errors
        seen: set[str] = set()
        total_duration = 0
        total_shots = 0
        for beat_index, beat in enumerate(beats):
            duration = beat.get("targetDurationMs")
            if not isinstance(duration, int) or duration < 600:
                errors.append(f"beats[{beat_index}].targetDurationMs is invalid")
            else:
                total_duration += duration
            shot_ids = beat.get("shotIds")
            if not isinstance(shot_ids, list) or not shot_ids:
                errors.append(f"beats[{beat_index}].shotIds must not be empty")
                continue
            total_shots += len(shot_ids)
            for shot_id in shot_ids:
                if shot_id not in allowed_shot_ids:
                    errors.append(f"beats[{beat_index}] references an unknown shotId: {shot_id}")
                if shot_id in seen:
                    errors.append(f"shotId is duplicated across beats: {shot_id}")
                seen.add(shot_id)
            reason_codes = beat.get("reasonCodes")
            if not isinstance(reason_codes, list) or any(code not in cls.ALLOWED_REASON_CODES for code in reason_codes):
                errors.append(f"beats[{beat_index}].reasonCodes contains an unsupported value")
        if total_duration != requested_duration_ms:
            errors.append("beat durations do not exactly fill targetDurationMs")
        if total_shots > max_shots:
            errors.append("proposal exceeds maxShots")
        return errors


def _beat_budgets(target_duration_ms: int) -> list[int]:
    budgets = [round(target_duration_ms * ratio) for _, ratio in STORY_BEATS[:-1]]
    budgets.append(target_duration_ms - sum(budgets))
    for index in range(len(budgets) - 1):
        if budgets[index] < 600:
            difference = 600 - budgets[index]
            budgets[index] = 600
            budgets[-1] -= difference
    return budgets


def _select_for_beat(
    role: str,
    candidates: list[dict[str, Any]],
    used_ids: set[str],
    asset_counts: dict[str, int],
    budget_ms: int,
    slot_limit: int,
) -> list[dict[str, Any]]:
    available = [shot for shot in candidates if shot["shotId"] not in used_ids]
    available.sort(key=lambda shot: _beat_sort_key(role, shot), reverse=True)
    selected = []
    working_asset_counts = dict(asset_counts)
    remaining = budget_ms
    while remaining > 0 and len(selected) < slot_limit:
        unused = [shot for shot in available if shot["shotId"] not in {item["shotId"] for item in selected}]
        covering = [shot for shot in unused if int(shot["durationMs"]) >= remaining]
        fitting = [shot for shot in unused if int(shot["durationMs"]) <= remaining - 600]
        viable = covering or fitting
        if viable:
            minimum_count = min(working_asset_counts.get(shot["sourceAssetId"], 0) for shot in viable)
            viable = [
                shot for shot in viable
                if working_asset_counts.get(shot["sourceAssetId"], 0) == minimum_count
            ]
        shot = viable[0] if viable else None
        if shot is None:
            break
        duration = min(int(shot["durationMs"]), remaining)
        source_in = _source_in_for_role(role, shot, duration)
        selected.append({
            **shot,
            "storyRole": role,
            "sourceInMs": source_in,
            "sourceOutMs": source_in + duration,
            "selectedDurationMs": duration,
            "selectionReasons": [
                f"STORY_ROLE_{role}",
                "STORY_ASSET_DIVERSITY",
                *shot.get("rankingReasons", []),
            ],
        })
        asset_id = shot["sourceAssetId"]
        working_asset_counts[asset_id] = working_asset_counts.get(asset_id, 0) + 1
        remaining -= duration
    return selected if remaining == 0 else []


def _beat_sort_key(role: str, shot: dict[str, Any]) -> tuple[Any, ...]:
    chronology = shot["startMs"] / max(1, shot["endMs"])
    if role == "HOOK":
        role_score = 0.55 * shot["finalScore"] + 0.45 * shot.get("motionInterest", 0.0)
    elif role == "INTRO":
        role_score = 0.45 * shot["finalScore"] + 0.30 * shot["composition"] + 0.25 * (1.0 - chronology)
    elif role == "JOURNEY":
        role_score = 0.55 * shot["finalScore"] + 0.25 * shot.get("motionInterest", 0.0) + 0.20 * shot["durationFitness"]
    elif role == "CLIMAX":
        role_score = 0.55 * shot["qualityScore"] + 0.30 * shot.get("motionInterest", 0.0) + 0.15 * shot["finalScore"]
    else:
        role_score = 0.45 * shot["finalScore"] + 0.30 * shot["stability"] + 0.25 * chronology
    return role_score, shot["finalScore"], -shot["rank"]


def _source_in_for_role(role: str, shot: dict[str, Any], duration: int) -> int:
    start = int(shot["startMs"])
    end = int(shot["endMs"])
    if role == "ENDING":
        return end - duration
    if role in {"JOURNEY", "CLIMAX"}:
        return start + max(0, (end - start - duration) // 2)
    return start
