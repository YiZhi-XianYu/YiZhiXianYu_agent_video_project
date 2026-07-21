"""Prompt templates for LLM interactions.

`PromptRegistry` is the single source of truth for prompt versions.
When LLM permissions expand (Phase 6+), add new prompt classes here
and register them in the registry.
"""

from __future__ import annotations

import hashlib
from typing import Any


class PromptRegistry:
    """Versioned prompt store. Each prompt class registers itself with a key."""

    _prompts: dict[str, type] = {}

    @classmethod
    def register(cls, prompt_cls: type) -> type:
        cls._prompts[prompt_cls.prompt_key] = prompt_cls
        return prompt_cls

    @classmethod
    def get(cls, key: str) -> type | None:
        return cls._prompts.get(key)


@PromptRegistry.register
class StoryProposalPrompt:
    """Prompt for Phase 5: LLM selects shots for the five-beat travel story."""

    prompt_key = "story-proposal"
    version = "1.1"

    SYSTEM = """\
You are a video editing assistant specialized in travel vlog storytelling.

Your task is to select video shots from a ranked candidate list and assign them
to the five-beat structure: HOOK -> INTRO -> JOURNEY -> CLIMAX -> ENDING.

RULES:
1. Only use shotIds from the provided candidate list. Never invent IDs.
2. Every beat must have at least 1 shot. Total shots <= maxShots.
3. Each shotId can appear in ONLY ONE beat.
4. Beat target durations must match the budget exactly.
5. Every beat must include reasonCodes from this list:
   HIGH_VISUAL_QUALITY, INTERESTING_MOTION, STRONG_OPENING,
   ESTABLISHING_CONTEXT, JOURNEY_CONTINUITY, CLIMAX_CANDIDATE,
   CALM_ENDING, ASSET_DIVERSITY.
6. Distribute shots across source assets evenly (ASSET_DIVERSITY).
7. HOOK: high motionInterest, strong opening.  INTRO: early establishing shots.
   JOURNEY: good motionInterest + durationFitness.  CLIMAX: highest qualityScore.
   ENDING: stable, calm, later in timeline.
8. Output MUST be valid JSON conforming to the schema described below.

OUTPUT JSON STRUCTURE (your entire response must be exactly this):
{
  "schemaVersion": "1.0",
  "template": "TRAVEL_JOURNEY_V1",
  "targetDurationMs": <copy the target total duration from the request>,
  "beats": [
    {
      "role": "HOOK",
      "targetDurationMs": <budget from request>,
      "shotIds": ["<candidate shotId>"],
      "reasonCodes": ["STRONG_OPENING", ...]
    },
    {
      "role": "INTRO",
      "targetDurationMs": <budget from request>,
      "shotIds": ["<candidate shotId>"],
      "reasonCodes": ["ESTABLISHING_CONTEXT", ...]
    },
    {
      "role": "JOURNEY",
      "targetDurationMs": <budget from request>,
      "shotIds": ["<candidate shotId>"],
      "reasonCodes": ["JOURNEY_CONTINUITY", "INTERESTING_MOTION", ...]
    },
    {
      "role": "CLIMAX",
      "targetDurationMs": <budget from request>,
      "shotIds": ["<candidate shotId>"],
      "reasonCodes": ["CLIMAX_CANDIDATE", ...]
    },
    {
      "role": "ENDING",
      "targetDurationMs": <budget from request>,
      "shotIds": ["<candidate shotId>"],
      "reasonCodes": ["CALM_ENDING", ...]
    }
  ],
  "assumptions": ["short explanation of your choices"],
  "confidence": 0.85
}

Beat order is FIXED: HOOK, INTRO, JOURNEY, CLIMAX, ENDING.
The sum of all beat targetDurationMs MUST equal the top-level targetDurationMs.
Every shotId MUST be from the candidate list provided in the user message."""

    USER_TEMPLATE = """\
Target total duration: {target_duration_ms} ms
Maximum shots allowed: {max_shots}
Number of source assets: {asset_count}

Beat duration budgets:
{beat_budgets_text}

Candidate shots (shotId, asset, rank, score, quality, motion, duration):
{candidates_text}

Please propose a shot-to-beat assignment that best tells a travel story."""

    @classmethod
    def build_system_prompt(cls) -> str:
        return cls.SYSTEM

    @classmethod
    def build_user_prompt(
        cls,
        candidates: list[dict[str, Any]],
        target_duration_ms: int,
        beat_budgets: list[int],
        asset_count: int,
        max_shots: int,
    ) -> str:
        """Build the user prompt from ranked candidate data."""
        beat_names = ["HOOK", "INTRO", "JOURNEY", "CLIMAX", "ENDING"]
        beat_budgets_text = "\n".join(
            f"  {name}: {budget} ms" for name, budget in zip(beat_names, beat_budgets)
        )

        candidate_lines = []
        for shot in candidates[:max_shots * 4]:
            sid = shot.get("shotId", "?")
            aid = shot.get("sourceAssetId", "?")[-8:] if shot.get("sourceAssetId") else "?"
            rank = shot.get("rank", "?")
            fscore = shot.get("finalScore", 0)
            qscore = shot.get("qualityScore", 0)
            motion = shot.get("motionInterest", 0)
            dur = shot.get("durationMs", 0)
            reasons = shot.get("rankingReasons", [])[:3]
            candidate_lines.append(
                f"  {sid} | asset_{aid} | rank={rank} | "
                f"finalScore={fscore:.3f} qualityScore={qscore:.3f} "
                f"motionInterest={motion:.3f} durationMs={dur} | "
                f"reasons: {', '.join(reasons)}"
            )

        return cls.USER_TEMPLATE.format(
            target_duration_ms=target_duration_ms,
            max_shots=max_shots,
            asset_count=asset_count,
            beat_budgets_text=beat_budgets_text,
            candidates_text="\n".join(candidate_lines),
        )

    @classmethod
    def hash_system_prompt(cls) -> str:
        return hashlib.sha256(cls.SYSTEM.encode()).hexdigest()[:16]
