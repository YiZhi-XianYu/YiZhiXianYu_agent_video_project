from __future__ import annotations

import json
import logging
import time
import uuid
from collections.abc import Callable
from pathlib import Path
from typing import Any

from app.llm.audit import LlmAuditRecord
from app.llm.provider import LlmError, get_provider
from app.llm.prompt import DurationParsingPrompt, StoryProposalPrompt
from app.core.models import ArtifactDescriptor, ToolExecutionRequest
from app.tools.artifact_json import matching_inputs, read_json_artifact, write_json_artifact

logger = logging.getLogger(__name__)

# Path to the shared LLM contract schema for structured output
_CONTRACTS_DIR = Path(__file__).resolve().parent.parent.parent.parent / "contracts" / "llm"


def _load_proposal_schema() -> dict[str, Any]:
    """Load the story-plan-proposal JSON Schema for strict structured output."""
    schema_path = _CONTRACTS_DIR / "story-plan-proposal.schema.json"
    if schema_path.exists():
        with open(schema_path, encoding="utf-8") as f:
            return json.load(f)
    logger.warning("Story proposal schema not found at %s", schema_path)
    return {}


def _load_duration_schema() -> dict[str, Any]:
    """Load the duration-parsing JSON Schema for strict structured output."""
    schema_path = _CONTRACTS_DIR / "duration-parsing.schema.json"
    if schema_path.exists():
        with open(schema_path, encoding="utf-8") as f:
            return json.load(f)
    logger.warning("Duration parsing schema not found at %s", schema_path)
    return {}

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
            "description": "Build a five-beat travel Story Plan from ranked Shots (LLM-assisted with deterministic fallback)",
            "executionMode": "ASYNC",
            "resourceClass": "CPU_LIGHT",
            "timeoutSeconds": 120,
            "supportsCancellation": False,
            "deterministic": False,
            "cacheable": False,
            "inputTypes": ["SHOT_RANKING", "SCENE_TAGS", "OBJECT_TAGS", "PERSON_TAGS"],
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
        duration_prompt = str(request.parameters.get("durationPrompt", "")).strip()
        if duration_prompt:
            parsed = _parse_duration_prompt(duration_prompt)
            if parsed is not None and 5000 <= parsed <= 60000:
                target_duration = parsed
                logger.info("Duration prompt '%s' parsed to %d ms", duration_prompt, target_duration)
            else:
                logger.warning("Duration prompt '%s' could not be parsed, using %d ms", duration_prompt, target_duration)
        if target_duration < 5000 or target_duration > 60000 or max_shots < 5 or max_shots > 100:
            raise ValueError(
                f"Story Plan duration {target_duration} ms is out of range. "
                "Supported: 5000–60000 ms (5–60 seconds)."
            )

        candidates = [shot for shot in ranking.get("shots") or [] if shot.get("eligible", True)]
        if len(candidates) < 5:
            candidates = list(ranking.get("shots") or [])

        semantic_by_shot = _build_semantic_map(request.inputs)

        audit = LlmAuditRecord(
            provider="none",
            model="none",
            temperature=0.3,
            request_id=uuid.uuid4().hex[:12],
            input_candidate_count=len(candidates),
        )

        provider = get_provider()
        if provider is not None and provider.name != "noop":
            llm_result = _try_llm_story_plan(
                provider, ranking, candidates, target_duration, max_shots, audit, semantic_by_shot
            )
            if llm_result is not None:
                llm_result["llmAudit"] = audit.to_dict()
                return [write_json_artifact("STORY_PLAN", "story-plan.json", llm_result, llm_result)]

        # deterministic fallback
        if audit.final_source == "DETERMINISTIC_FALLBACK":
            logger.warning(
                "Story Plan [%s] LLM unavailable or validation failed, using deterministic fallback",
                audit.request_id,
            )
        proposal = _build_deterministic_story_plan(
            ranking, ranking_inputs[0], candidates, target_duration, max_shots, STORY_BEATS
        )
        proposal["llmAudit"] = audit.to_dict()
        return [write_json_artifact("STORY_PLAN", "story-plan.json", proposal, proposal)]


def _build_deterministic_story_plan(
    ranking: dict[str, Any],
    ranking_inputs: Any,
    candidates: list[dict[str, Any]],
    target_duration: int,
    max_shots: int,
    beats_def: list[tuple[str, float]],
) -> dict[str, Any]:
    """Pure deterministic story plan builder (extracted for fallback reuse)."""

    budgets = _beat_budgets(target_duration)
    used_ids: set[str] = set()
    asset_counts = {shot["sourceAssetId"]: 0 for shot in candidates}
    beats = []
    remaining_slots = max_shots
    for beat_index, ((role, _), budget) in enumerate(zip(beats_def, budgets)):
        remaining_beats = len(beats_def) - beat_index
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

    # ── Global balance: distribute any deficit proportionally across beats ──
    total_actual = sum(beat["actualDurationMs"] for beat in beats)
    global_remaining = target_duration - total_actual
    if global_remaining > 0:
        for beat in beats:
            if global_remaining <= 0:
                break
            role = beat["role"]
            beat_share = int(global_remaining * beat["targetDurationMs"] / target_duration)
            beat_share = max(1, min(beat_share, global_remaining))
            for shot in reversed(beat["shots"]):
                if beat_share <= 0:
                    break
                shot_start = int(shot["startMs"])
                shot_end = int(shot["endMs"])
                capacity = (shot_end - shot_start) - shot["selectedDurationMs"]
                extra = min(beat_share, capacity)
                if extra <= 0:
                    continue
                new_dur = shot["selectedDurationMs"] + extra
                if role == "ENDING":
                    shot["sourceInMs"] = shot_end - new_dur
                elif role in ("JOURNEY", "CLIMAX"):
                    shot["sourceInMs"] = shot_start + max(0, ((shot_end - shot_start) - new_dur) // 2)
                shot["sourceOutMs"] = shot["sourceInMs"] + new_dur
                shot["selectedDurationMs"] = new_dur
                beat_share -= extra
                global_remaining -= extra
            beat["actualDurationMs"] = sum(s["selectedDurationMs"] for s in beat["shots"])
        # Second pass: absorb leftover from beats that couldn't take their share
        if global_remaining > 0:
            for beat in beats:
                if global_remaining <= 0:
                    break
                role = beat["role"]
                for shot in reversed(beat["shots"]):
                    if global_remaining <= 0:
                        break
                    shot_start = int(shot["startMs"])
                    shot_end = int(shot["endMs"])
                    capacity = (shot_end - shot_start) - shot["selectedDurationMs"]
                    extra = min(global_remaining, capacity)
                    if extra <= 0:
                        continue
                    new_dur = shot["selectedDurationMs"] + extra
                    if role == "ENDING":
                        shot["sourceInMs"] = shot_end - new_dur
                    elif role in ("JOURNEY", "CLIMAX"):
                        shot["sourceInMs"] = shot_start + max(0, ((shot_end - shot_start) - new_dur) // 2)
                    shot["sourceOutMs"] = shot["sourceInMs"] + new_dur
                    shot["selectedDurationMs"] = new_dur
                    global_remaining -= extra
                beat["actualDurationMs"] = sum(s["selectedDurationMs"] for s in beat["shots"])
        if global_remaining > 0:
            logger.info(
                "Deterministic story plan global deficit: %d ms (%.1f%% of target)",
                global_remaining, global_remaining / target_duration * 100,
            )

    proposal = {
        "schemaVersion": "1.0",
        "template": "TRAVEL_JOURNEY_V1",
        "sourceRankingArtifactId": ranking_inputs.artifact_id,
        "targetDurationMs": target_duration,
        "maxShots": max_shots,
        "beats": beats,
        "assumptions": [
            "No semantic scene labels are available; beat roles use deterministic quality, motion and chronology signals.",
            "Story selection prefers the least-used source Asset when it can still fill the current beat exactly.",
            "Only eligible ranked Shots are preferred; rejected Shots are used only when fewer than five eligible candidates exist.",
        ],
    }
    allowed_ids = {shot["shotId"] for shot in ranking.get("shots") or []}
    errors = StoryProposalValidator.validate(proposal, allowed_ids, target_duration, max_shots)
    if errors:
        raise ValueError("Story Plan validation failed: " + "; ".join(errors))
    proposal["validation"] = {"valid": True, "errors": []}
    return proposal


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
            if actual != calculated:
                errors.append(f"beats[{beat_index}] actualDurationMs does not match its shots")
            deviation = abs(target - calculated)
            if deviation > max(900, target * 0.20):
                errors.append(f"beats[{beat_index}] duration deviates too far from targetDurationMs")
            total_duration += calculated
        if shot_count > max_shots:
            errors.append("Story Plan exceeds maxShots")
        if total_duration != target_duration_ms:
            deviation = abs(total_duration - target_duration_ms)
            if deviation > target_duration_ms * 0.10:
                errors.append("Story Plan does not exactly fill targetDurationMs")
        return errors


class LlmStoryProposalValidator:
    """Validates only structural correctness of the LLM raw proposal.

    Duration arithmetic is handled entirely by _compile_llm_proposal
    (which uses deterministic _beat_budgets).  The raw proposal only
    needs valid shotIds, correct schema structure, and no duplicates.

    Schema version: accepts both "1.0" (legacy, with beat-level
    targetDurationMs) and "1.1" (simplified, shotIds + reasonCodes only).
    """
    ALLOWED_REASON_CODES = {
        "HIGH_VISUAL_QUALITY", "INTERESTING_MOTION", "STRONG_OPENING", "ESTABLISHING_CONTEXT",
        "JOURNEY_CONTINUITY", "CLIMAX_CANDIDATE", "CALM_ENDING", "ASSET_DIVERSITY",
        "SCENE_MATCH", "PERSON_PRESENCE", "SEMANTIC_RELEVANCE",
    }
    ALLOWED_SCHEMA_VERSIONS = {"1.0", "1.1"}

    @classmethod
    def validate(
        cls,
        proposal: dict[str, Any],
        allowed_shot_ids: set[str],
        requested_duration_ms: int,
        max_shots: int,
    ) -> list[str]:
        errors: list[str] = []
        if proposal.get("schemaVersion") not in cls.ALLOWED_SCHEMA_VERSIONS:
            errors.append(f"schemaVersion must be one of {sorted(cls.ALLOWED_SCHEMA_VERSIONS)}")
        if proposal.get("template") != "TRAVEL_JOURNEY_V1":
            errors.append("template must be TRAVEL_JOURNEY_V1")
        beats = proposal.get("beats")
        if not isinstance(beats, list) or [beat.get("role") for beat in beats] != StoryProposalValidator.ALLOWED_ROLES:
            errors.append("beats must use the fixed HOOK, INTRO, JOURNEY, CLIMAX, ENDING order")
            return errors

        # Check total shot count does not wildly exceed budget (early reject)
        total_llm_shots = sum(len(beat.get("shotIds", [])) for beat in beats)
        if total_llm_shots > max_shots * 3:
            errors.append(
                f"LLM proposed {total_llm_shots} shots, far exceeding maxShots={max_shots}; "
                "likely hallucination"
            )

        seen: set[str] = set()
        for beat_index, beat in enumerate(beats):
            shot_ids = beat.get("shotIds")
            if not isinstance(shot_ids, list) or not shot_ids:
                errors.append(f"beats[{beat_index}].shotIds must not be empty")
                continue
            for shot_id in shot_ids:
                if shot_id not in allowed_shot_ids:
                    errors.append(f"beats[{beat_index}] references an unknown shotId: {shot_id}")
                if shot_id in seen:
                    errors.append(f"shotId is duplicated across beats: {shot_id}")
                seen.add(shot_id)
            reason_codes = beat.get("reasonCodes")
            if not isinstance(reason_codes, list) or any(code not in cls.ALLOWED_REASON_CODES for code in reason_codes):
                errors.append(f"beats[{beat_index}].reasonCodes contains an unsupported value")
        return errors


def _try_llm_story_plan(
    provider: Any,
    ranking: dict[str, Any],
    candidates: list[dict[str, Any]],
    target_duration: int,
    max_shots: int,
    audit: LlmAuditRecord,
    semantic_by_shot: dict[str, dict[str, list[str]]] | None = None,
) -> dict[str, Any] | None:
    """Attempt LLM story proposal. Returns None if LLM fails or validation fails."""
    audit.start()
    start = time.monotonic()

    try:
        asset_count = len({shot.get("sourceAssetId") for shot in candidates})
        budgets = _beat_budgets(target_duration)
        system = StoryProposalPrompt.build_system_prompt()
        user = StoryProposalPrompt.build_user_prompt(
            candidates, target_duration, budgets, asset_count, max_shots, semantic_by_shot,
        )
        audit.system_prompt_hash = StoryProposalPrompt.hash_system_prompt()

        # Load the canonical schema for strict structured output providers.
        # Non-strict providers (DeepSeek) ignore the schema parameter and use
        # their own response_format (json_object).
        proposal_schema = _load_proposal_schema()

        raw = provider.generate_json(
            system, user, proposal_schema,
            temperature=0.3, request_id=audit.request_id,
        )
    except (LlmError, Exception) as exc:
        logger.warning("LLM call failed [%s]: %s", audit.request_id, exc)
        audit.mark_llm_error(provider.name, getattr(provider, "model", provider.name))
        audit.duration_ms = int((time.monotonic() - start) * 1000)
        return None

    audit.mark_llm_success(provider.name, getattr(provider, "model", provider.name), raw)
    audit.duration_ms = int((time.monotonic() - start) * 1000)

    allowed_ids = {shot["shotId"] for shot in ranking.get("shots", [])}
    errors = LlmStoryProposalValidator.validate(raw, allowed_ids, target_duration, max_shots)
    if errors:
        logger.warning("LLM proposal validation failed [%s]: %s", audit.request_id, errors)
        audit.mark_validation_failed(errors)
        return None

    proposal = _compile_llm_proposal(raw, ranking, candidates, target_duration, max_shots)
    errors = StoryProposalValidator.validate(proposal, allowed_ids, target_duration, max_shots)
    if errors:
        logger.warning("LLM compiled Story Plan validation failed [%s]: %s", audit.request_id, errors)
        audit.mark_validation_failed(errors)
        return None

    proposal["validation"] = {"valid": True, "errors": []}
    return proposal


def _compile_llm_proposal(
    raw: dict[str, Any],
    ranking: dict[str, Any],
    candidates: list[dict[str, Any]],
    target_duration: int,
    max_shots: int,
) -> dict[str, Any]:
    """Compile an LLM proposal into a full Story Plan.

    The LLM only chooses shotIds per beat.  We use the deterministic timing
    helpers to compute sourceInMs / sourceOutMs so the LLM never sees raw
    frame numbers or clip boundaries.
    """
    budgets = _beat_budgets(target_duration)
    shot_map = {shot["shotId"]: shot for shot in candidates}
    used_ids: set[str] = set()
    beats: list[dict[str, Any]] = []
    remaining_slots = max_shots

    # ── Collect LLM shotIds per beat ──
    raw_beats = raw.get("beats", [])
    llm_ids_by_beat: list[list[str]] = []
    total_llm_shots = 0
    for raw_beat in raw_beats:
        sids = raw_beat.get("shotIds", [])
        llm_ids_by_beat.append(list(sids))
        total_llm_shots += len(sids)

    # ── Trim LLM shots proportionally per beat if needed ──
    if total_llm_shots > max_shots:
        trimmed_by_beat: list[list[str]] = []
        for beat_idx, sids in enumerate(llm_ids_by_beat):
            remaining_beats = len(llm_ids_by_beat) - beat_idx
            beat_quota = max(1, remaining_slots - (remaining_beats - 1))
            beat_quota = min(beat_quota, len(sids))
            # Keep top-N by finalScore within this beat
            scored = [(sid, shot_map.get(sid, {}).get("finalScore", 0)) for sid in sids]
            scored.sort(key=lambda x: x[1], reverse=True)
            trimmed_by_beat.append([sid for sid, _ in scored[:beat_quota]])
            remaining_slots -= min(beat_quota, len(sids))
        llm_ids_by_beat = trimmed_by_beat
        logger.info(
            "LLM proposed %d shots > maxShots=%d, trimmed proportionally per beat",
            total_llm_shots, max_shots,
        )
        remaining_slots = max_shots  # reset for the fill phase below

    for beat_index, ((role, _), budget) in enumerate(zip(STORY_BEATS, budgets)):
        shot_ids = llm_ids_by_beat[beat_index] if beat_index < len(llm_ids_by_beat) else []
        beat_shots: list[dict[str, Any]] = []
        remaining = budget

        for sid in shot_ids:
            if sid in used_ids:
                continue
            shot = shot_map.get(sid)
            if shot is None:
                continue
            duration = min(int(shot["durationMs"]), remaining)
            if duration <= 0:
                break
            if duration < 600:
                continue
            source_in = _source_in_for_role(role, shot, duration)
            beat_shots.append({
                **shot,
                "storyRole": role,
                "sourceInMs": source_in,
                "sourceOutMs": source_in + duration,
                "selectedDurationMs": duration,
                "selectionReasons": [
                    f"LLM_STORY_ROLE_{role}",
                    *(raw_beats[beat_index].get("reasonCodes", []) if beat_index < len(raw_beats) else []),
                ],
            })
            used_ids.add(sid)
            remaining_slots -= 1
            remaining -= duration

        # ── Slack distribution: extend existing shots to fill remaining ──
        if remaining > 0 and beat_shots:
            for shot in reversed(beat_shots):
                if remaining <= 0:
                    break
                shot_start = int(shot["startMs"])
                shot_end = int(shot["endMs"])
                max_available = shot_end - shot_start
                extra = min(remaining, max_available - shot["selectedDurationMs"])
                if extra <= 0:
                    continue
                new_dur = shot["selectedDurationMs"] + extra
                if role == "ENDING":
                    shot["sourceInMs"] = shot_end - new_dur
                elif role in ("JOURNEY", "CLIMAX"):
                    shot["sourceInMs"] = shot_start + max(0, (max_available - new_dur) // 2)
                shot["sourceOutMs"] = shot["sourceInMs"] + new_dur
                shot["selectedDurationMs"] = new_dur
                remaining -= extra

        # ── Hybrid fill: one extra shot if budget still unfilled and slots remain ──
        remaining_beats = len(STORY_BEATS) - beat_index
        slot_limit = remaining_slots - (remaining_beats - 1)
        if remaining >= 600 and slot_limit > 0:
            unused = [s for s in candidates if s["shotId"] not in used_ids]
            unused.sort(key=lambda s: _beat_sort_key(role, s), reverse=True)
            for s in unused:
                if remaining < 600 or slot_limit <= 0:
                    break
                dur = min(int(s["durationMs"]), remaining)
                if dur < 600:
                    continue
                source_in = _source_in_for_role(role, s, dur)
                beat_shots.append({
                    **s,
                    "storyRole": role,
                    "sourceInMs": source_in,
                    "sourceOutMs": source_in + dur,
                    "selectedDurationMs": dur,
                    "selectionReasons": [f"LLM_STORY_ROLE_{role}", "HYBRID_FILL"],
                })
                used_ids.add(s["shotId"])
                remaining_slots -= 1
                slot_limit -= 1
                remaining -= dur

        if not beat_shots:
            raise ValueError(f"No valid shots for beat {role}")

        # ── Slack distribution round 2 (after hybrid fill) ──
        if remaining > 0 and beat_shots:
            for shot in reversed(beat_shots):
                if remaining <= 0:
                    break
                shot_start = int(shot["startMs"])
                shot_end = int(shot["endMs"])
                max_available = shot_end - shot_start
                extra = min(remaining, max_available - shot["selectedDurationMs"])
                if extra <= 0:
                    continue
                new_dur = shot["selectedDurationMs"] + extra
                if role == "ENDING":
                    shot["sourceInMs"] = shot_end - new_dur
                elif role in ("JOURNEY", "CLIMAX"):
                    shot["sourceInMs"] = shot_start + max(0, (max_available - new_dur) // 2)
                shot["sourceOutMs"] = shot["sourceInMs"] + new_dur
                shot["selectedDurationMs"] = new_dur
                remaining -= extra

        # ── Final absorption: distribute any remaining budget proportionally ──
        if remaining > 0 and beat_shots:
            total_capacity = sum(
                int(s["endMs"]) - int(s["startMs"]) - s["selectedDurationMs"]
                for s in beat_shots
            )
            if total_capacity >= remaining:
                for shot in reversed(beat_shots):
                    if remaining <= 0:
                        break
                    shot_start = int(shot["startMs"])
                    shot_end = int(shot["endMs"])
                    capacity = shot_end - shot_start - shot["selectedDurationMs"]
                    if capacity <= 0:
                        continue
                    extra = min(remaining, capacity)
                    new_dur = shot["selectedDurationMs"] + extra
                    if role == "ENDING":
                        shot["sourceInMs"] = shot_end - new_dur
                    elif role in ("JOURNEY", "CLIMAX"):
                        shot["sourceInMs"] = shot_start + max(0, ((shot_end - shot_start) - new_dur) // 2)
                    shot["sourceOutMs"] = shot["sourceInMs"] + new_dur
                    shot["selectedDurationMs"] = new_dur
                    remaining -= extra

        # ── Absolute last resort: push tiny remainder into the last shot ──
        if 0 < remaining < 600 and beat_shots:
            last_shot = beat_shots[-1]
            shot_start = int(last_shot["startMs"])
            shot_end = int(last_shot["endMs"])
            max_dur = shot_end - shot_start
            clamped_dur = min(last_shot["selectedDurationMs"] + remaining, max_dur)
            extra = clamped_dur - last_shot["selectedDurationMs"]
            last_shot["selectedDurationMs"] = clamped_dur
            last_shot["sourceOutMs"] = last_shot["sourceInMs"] + clamped_dur
            remaining -= extra

        beats.append({
            "role": role,
            "targetDurationMs": budget,
            "actualDurationMs": sum(s["selectedDurationMs"] for s in beat_shots),
            "shots": beat_shots,
        })

    # ── Trim before global balance so deficit from removed shots is compensated ──
    beats = _trim_beats_to_max_shots(beats, max_shots)

    # ── Global balance: distribute deficit proportionally across all beats ──
    total_actual = sum(beat["actualDurationMs"] for beat in beats)
    global_remaining = target_duration - total_actual
    if global_remaining > 0:
        for beat in beats:
            if global_remaining <= 0:
                break
            role = beat["role"]
            # Proportional share for this beat (capped by global_remaining)
            beat_share = int(global_remaining * beat["targetDurationMs"] / target_duration)
            beat_share = max(1, min(beat_share, global_remaining))
            for shot in reversed(beat["shots"]):
                if beat_share <= 0:
                    break
                shot_start = int(shot["startMs"])
                shot_end = int(shot["endMs"])
                capacity = (shot_end - shot_start) - shot["selectedDurationMs"]
                extra = min(beat_share, capacity)
                if extra <= 0:
                    continue
                new_dur = shot["selectedDurationMs"] + extra
                if role == "ENDING":
                    shot["sourceInMs"] = shot_end - new_dur
                elif role in ("JOURNEY", "CLIMAX"):
                    shot["sourceInMs"] = shot_start + max(0, ((shot_end - shot_start) - new_dur) // 2)
                shot["sourceOutMs"] = shot["sourceInMs"] + new_dur
                shot["selectedDurationMs"] = new_dur
                beat_share -= extra
                global_remaining -= extra
            beat["actualDurationMs"] = sum(s["selectedDurationMs"] for s in beat["shots"])
        # Second pass: absorb any leftover from beats that couldn't take their share
        if global_remaining > 0:
            for beat in beats:
                if global_remaining <= 0:
                    break
                role = beat["role"]
                for shot in reversed(beat["shots"]):
                    if global_remaining <= 0:
                        break
                    shot_start = int(shot["startMs"])
                    shot_end = int(shot["endMs"])
                    capacity = (shot_end - shot_start) - shot["selectedDurationMs"]
                    extra = min(global_remaining, capacity)
                    if extra <= 0:
                        continue
                    new_dur = shot["selectedDurationMs"] + extra
                    if role == "ENDING":
                        shot["sourceInMs"] = shot_end - new_dur
                    elif role in ("JOURNEY", "CLIMAX"):
                        shot["sourceInMs"] = shot_start + max(0, ((shot_end - shot_start) - new_dur) // 2)
                    shot["sourceOutMs"] = shot["sourceInMs"] + new_dur
                    shot["selectedDurationMs"] = new_dur
                    global_remaining -= extra
                beat["actualDurationMs"] = sum(s["selectedDurationMs"] for s in beat["shots"])
        if global_remaining > 0:
            logger.info(
                "Story plan global deficit: %d ms (%.1f%% of target), within tolerance",
                global_remaining, global_remaining / target_duration * 100,
            )

    return {
        "schemaVersion": "1.0",
        "template": "TRAVEL_JOURNEY_V1",
        "sourceRankingArtifactId": "llm-proposal",
        "targetDurationMs": target_duration,
        "maxShots": max_shots,
        "beats": beats,
        "assumptions": (raw.get("assumptions") if isinstance(raw.get("assumptions"), list) else [str(raw.get("assumptions", ""))]),
        "confidence": raw.get("confidence", 0),
    }


def _trim_beats_to_max_shots(
    beats: list[dict[str, Any]], max_shots: int
) -> list[dict[str, Any]]:
    """Remove lowest-scoring shots if total exceeds maxShots.

    Hybrid-fill shots are removed first, then lowest finalScore shots.
    Removed shots' durations are redistributed to remaining shots in the same beat.
    """
    total = sum(len(beat["shots"]) for beat in beats)
    if total <= max_shots:
        return beats

    # Collect all (beat_idx, shot_idx, shot) triples sorted by removal priority
    indexed: list[tuple[int, int, dict[str, Any]]] = []
    for bi, beat in enumerate(beats):
        for si, shot in enumerate(beat["shots"]):
            indexed.append((bi, si, shot))

    # Sort: hybrid-fill first, then by finalScore ascending (lowest first to remove)
    indexed.sort(key=lambda x: (
        0 if "HYBRID_FILL" in x[2].get("selectionReasons", []) else 1,
        x[2].get("finalScore", 0),
    ))

    remove_count = total - max_shots
    to_remove: set[tuple[int, int]] = set()
    for bi, si, _shot in indexed:
        if remove_count <= 0:
            break
        beat = beats[bi]
        # Never remove the last shot in a beat
        if len(beat["shots"]) <= 1:
            continue
        to_remove.add((bi, si))
        remove_count -= 1

    if not to_remove:
        return beats

    # Rebuild beats without removed shots, redistributing their durations
    for bi, beat in enumerate(beats):
        role = beat["role"]
        remaining_shots = [
            shot for si, shot in enumerate(beat["shots"]) if (bi, si) not in to_remove
        ]
        # Redistribute removed durations to remaining shots
        removed_duration = sum(
            s["selectedDurationMs"] for si, s in enumerate(beat["shots"]) if (bi, si) in to_remove
        )
        for shot in reversed(remaining_shots):
            if removed_duration <= 0:
                break
            shot_start = int(shot["startMs"])
            shot_end = int(shot["endMs"])
            capacity = (shot_end - shot_start) - shot["selectedDurationMs"]
            extra = min(removed_duration, capacity)
            if extra <= 0:
                continue
            new_dur = shot["selectedDurationMs"] + extra
            if role == "ENDING":
                shot["sourceInMs"] = shot_end - new_dur
            elif role in ("JOURNEY", "CLIMAX"):
                shot["sourceInMs"] = shot_start + max(0, ((shot_end - shot_start) - new_dur) // 2)
            shot["sourceOutMs"] = shot["sourceInMs"] + new_dur
            shot["selectedDurationMs"] = new_dur
            removed_duration -= extra
        beat["shots"] = remaining_shots
        beat["actualDurationMs"] = sum(s["selectedDurationMs"] for s in remaining_shots)

    return beats


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
        # When no shot perfectly covers or cleanly fits, accept the best
        # available — slack distribution will absorb the small remainder.
        if not viable and unused:
            viable = unused
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
    # Slack distribution: extend existing shots to fill remaining budget
    if remaining > 0 and selected:
        for shot in reversed(selected):
            if remaining <= 0:
                break
            shot_start = int(shot["startMs"])
            shot_end = int(shot["endMs"])
            max_available = shot_end - shot_start
            extra = min(remaining, max_available - shot["selectedDurationMs"])
            if extra <= 0:
                continue
            shot["selectedDurationMs"] += extra
            shot["sourceOutMs"] = shot["sourceInMs"] + shot["selectedDurationMs"]
            remaining -= extra
    # Last resort: clamp tiny remainder into the last shot boundary
    if 0 < remaining < 600 and selected:
        last_shot = selected[-1]
        shot_start = int(last_shot["startMs"])
        shot_end = int(last_shot["endMs"])
        max_dur = shot_end - shot_start
        clamped_dur = min(last_shot["selectedDurationMs"] + remaining, max_dur)
        extra = clamped_dur - last_shot["selectedDurationMs"]
        last_shot["selectedDurationMs"] = clamped_dur
        last_shot["sourceOutMs"] = last_shot["sourceInMs"] + clamped_dur
        remaining -= extra
    return selected if selected else []


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


def _build_semantic_map(inputs: dict[str, Any]) -> dict[str, dict[str, list[str]]]:
    """Read SCENE_TAGS, OBJECT_TAGS, and PERSON_TAGS artifacts and build a
    per-shotId semantic context dict for LLM prompt injection."""
    sem: dict[str, dict[str, list[str]]] = {}

    scene_inputs = matching_inputs(inputs, "scene")
    for inp in scene_inputs:
        try:
            data = read_json_artifact(inp)
            for shot in data.get("shots") or []:
                sid = shot.get("shotId")
                if not sid:
                    continue
                if sid not in sem:
                    sem[sid] = {}
                sem[sid]["scene"] = [
                    f"{tag['label']}({tag['confidence']:.2f})"
                    for tag in shot.get("sceneTags", [])
                ]
        except Exception:
            continue

    object_inputs = matching_inputs(inputs, "object")
    for inp in object_inputs:
        try:
            data = read_json_artifact(inp)
            for shot in data.get("shots") or []:
                sid = shot.get("shotId")
                if not sid:
                    continue
                if sid not in sem:
                    sem[sid] = {}
                sem[sid]["object"] = [
                    f"{tag['label']}({tag['confidence']:.2f})"
                    for tag in shot.get("objectTags", [])
                ]
        except Exception:
            continue

    person_inputs = matching_inputs(inputs, "person")
    for inp in person_inputs:
        try:
            data = read_json_artifact(inp)
            for shot in data.get("shots") or []:
                sid = shot.get("shotId")
                if not sid:
                    continue
                if sid not in sem:
                    sem[sid] = {}
                sem[sid]["person"] = [
                    f"{tag['label']}({tag['confidence']:.2f})"
                    for tag in shot.get("personTags", [])
                ]
        except Exception:
            continue

    return sem


def _parse_duration_prompt(prompt: str) -> int | None:
    """Use LLM to parse a natural-language duration description into milliseconds.

    Returns None if the LLM is unavailable or the result is invalid.
    """
    provider = get_provider()
    if provider is None or provider.name == "noop":
        logger.warning("No LLM available for duration parsing, using default")
        return None

    system = DurationParsingPrompt.build_system_prompt()
    user = DurationParsingPrompt.build_user_prompt(prompt)

    try:
        duration_schema = _load_duration_schema()
        result = provider.generate_json(
            system, user, duration_schema,
            temperature=0.1, max_tokens=256,
            request_id="duration-parse",
        )
    except (LlmError, Exception) as exc:
        logger.warning("Duration parsing LLM call failed: %s", exc)
        return None

    if not isinstance(result, dict):
        return None

    ms = result.get("targetDurationMs")
    if isinstance(ms, (int, float)) and 5000 <= ms <= 300000:
        return int(ms)

    logger.warning("Duration parsing returned invalid value: %s", ms)
    return None
