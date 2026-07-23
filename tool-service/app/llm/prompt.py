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
    version = "1.2"

    SYSTEM = """\
You are a video editing assistant specialized in travel vlog storytelling.

Your task is to select video shots from a ranked candidate list and assign them
to the five-beat structure: HOOK -> INTRO -> JOURNEY -> CLIMAX -> ENDING.

Each candidate shot includes semantic tags (scene, object, person) that describe
what is visually present in the keyframe. Use these tags to make smarter narrative
choices — for example, assign SNOW_MOUNTAIN to CLIMAX, OLD_TOWN to INTRO/JOURNEY,
WATERSIDE to JOURNEY, and shots with HAS_PERSON to HOOK or CLIMAX.

IMPORTANT — You only assign shotIds and reasonCodes. Beat durations are computed
deterministically by the system; you do NOT need to output or calculate them.

RULES:
1. Only use shotIds from the provided candidate list. Never invent IDs.
2. Every beat must have at least 1 shot. Total shots MUST NOT exceed maxShots (given in the user message).
   For a typical 30-second video with maxShots=12: pick 2 shots per beat on average (HOOK:2, INTRO:2, JOURNEY:3, CLIMAX:3, ENDING:2 = 12).
3. Each shotId can appear in ONLY ONE beat.
4. Every beat must include reasonCodes from this list:
   HIGH_VISUAL_QUALITY, INTERESTING_MOTION, STRONG_OPENING,
   ESTABLISHING_CONTEXT, JOURNEY_CONTINUITY, CLIMAX_CANDIDATE,
   CALM_ENDING, ASSET_DIVERSITY, SCENE_MATCH, PERSON_PRESENCE, SEMANTIC_RELEVANCE.
5. Distribute shots across source assets evenly (ASSET_DIVERSITY).
6. HOOK: high motionInterest, strong opening, preferably with HAS_PERSON or CLOSE_UP.
   INTRO: early establishing shots, OLD_TOWN/MODERN_CITY/COUNTRYSIDE preferred.
   JOURNEY: good motionInterest + durationFitness, WATERSIDE/FOREST/HIKING_TRAIL preferred.
   CLIMAX: highest qualityScore, SNOW_MOUNTAIN/TEMPLE/PERSON_CLOSEUP preferred.
   ENDING: stable, calm, later in timeline, SKY_DOMINANT/OPEN_FIELD/NIGHT_SCENE preferred.
7. Output MUST be valid JSON conforming to the schema described below.

OUTPUT JSON STRUCTURE (your entire response must be exactly this):
{
  "schemaVersion": "1.1",
  "template": "TRAVEL_JOURNEY_V1",
  "targetDurationMs": <copy the target total duration from the request>,
  "beats": [
    {
      "role": "HOOK",
      "shotIds": ["<candidate shotId>"],
      "reasonCodes": ["STRONG_OPENING", ...]
    },
    {
      "role": "INTRO",
      "shotIds": ["<candidate shotId>"],
      "reasonCodes": ["ESTABLISHING_CONTEXT", ...]
    },
    {
      "role": "JOURNEY",
      "shotIds": ["<candidate shotId>"],
      "reasonCodes": ["JOURNEY_CONTINUITY", "INTERESTING_MOTION", ...]
    },
    {
      "role": "CLIMAX",
      "shotIds": ["<candidate shotId>"],
      "reasonCodes": ["CLIMAX_CANDIDATE", ...]
    },
    {
      "role": "ENDING",
      "shotIds": ["<candidate shotId>"],
      "reasonCodes": ["CALM_ENDING", ...]
    }
  ],
  "assumptions": ["short explanation of your choices"],
  "confidence": 0.85
}

Beat order is FIXED: HOOK, INTRO, JOURNEY, CLIMAX, ENDING.
Every shotId MUST be from the candidate list provided in the user message."""

    USER_TEMPLATE = """\
Target total duration: {target_duration_ms} ms
Maximum shots allowed: {max_shots}
Number of source assets: {asset_count}

Beat duration budgets:
{beat_budgets_text}

Candidate shots (shotId, asset, rank, score, quality, motion, duration, semantic tags):
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
        semantic_by_shot: dict[str, dict[str, list[str]]] | None = None,
    ) -> str:
        """Build the user prompt from ranked candidate data."""
        beat_names = ["HOOK", "INTRO", "JOURNEY", "CLIMAX", "ENDING"]
        beat_budgets_text = "\n".join(
            f"  {name}: {budget} ms" for name, budget in zip(beat_names, beat_budgets)
        )
        sem = semantic_by_shot or {}

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

            parts = [
                f"  {sid} | asset_{aid} | rank={rank} | "
                f"finalScore={fscore:.3f} qualityScore={qscore:.3f} "
                f"motionInterest={motion:.3f} durationMs={dur}",
            ]
            shot_sem = sem.get(sid, {})
            scene_str = ", ".join(shot_sem.get("scene", [])) or "none"
            obj_str = ", ".join(shot_sem.get("object", [])) or "none"
            person_str = ", ".join(shot_sem.get("person", [])) or "none"
            parts.append(f"scene=[{scene_str}] objects=[{obj_str}] person=[{person_str}]")
            parts.append(f"reasons: {', '.join(reasons)}")
            candidate_lines.append(" | ".join(parts))

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


@PromptRegistry.register
class DurationParsingPrompt:
    """Parse natural-language duration descriptions into targetDurationMs."""

    prompt_key = "duration-parsing"
    version = "1.0"

    SYSTEM = """\
You are a video production assistant. Your task is to parse natural-language
descriptions of desired video duration into a precise millisecond value.

Rules:
- Understand both Chinese and English descriptions
- "秒" = seconds, "分钟" or "分" = minutes
- Descriptors like "快节奏", "快速", "fast-paced" suggest shorter durations (8-20s)
- Descriptors like "慢旅行", "慢", "slow", "relaxed" suggest longer durations (45-120s)
- Default: if no strong signal, lean toward 30 seconds

Examples:
- "快节奏15秒" → 15000
- "1分钟慢旅行" → 60000
- "30 seconds" → 30000
- "a 2-minute highlight reel" → 120000
- "45秒" → 45000
- "一分半钟" → 90000
- "3分钟" → 180000
- "fast-paced 20 second clip" → 20000
- "slow travel 90 seconds" → 90000

Return ONLY valid JSON: {"targetDurationMs": <int>, "parsedFrom": "<original prompt>"}"""

    USER_TEMPLATE = """Duration prompt: {duration_prompt}

Parse this into targetDurationMs (integer, milliseconds)."""

    @classmethod
    def build_system_prompt(cls) -> str:
        return cls.SYSTEM

    @classmethod
    def build_user_prompt(cls, duration_prompt: str) -> str:
        return cls.USER_TEMPLATE.format(duration_prompt=duration_prompt)

    @classmethod
    def hash_system_prompt(cls) -> str:
        return hashlib.sha256(cls.SYSTEM.encode()).hexdigest()[:16]
