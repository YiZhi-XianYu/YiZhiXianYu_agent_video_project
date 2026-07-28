"""Search, diversify and materialize background-music candidates."""

from __future__ import annotations

import hashlib
import json
import logging
import shutil
import subprocess
from collections import defaultdict
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from uuid import uuid4

from app.core.config import settings
from app.core.models import ArtifactDescriptor, ToolExecutionRequest
from app.music.providers import MusicCandidate, MusicSearchProfile, configured_music_providers
from app.tools.artifact_json import matching_inputs, read_json_artifact

logger = logging.getLogger(__name__)

BEAT_ROLE_TO_MOOD = {
    "HOOK": "energetic", "INTRO": "calm", "JOURNEY": "upbeat",
    "CLIMAX": "epic", "ENDING": "serene",
}

REASON_TAGS = {
    "CALM": "calm", "SERENE": "serene", "ENDING": "serene",
    "ENERGY": "energetic", "MOTION": "energetic", "OPENING": "energetic",
    "JOURNEY": "upbeat", "TRAVEL": "upbeat", "DIVERSITY": "cinematic",
    "CLIMAX": "epic", "EPIC": "epic", "NATURE": "acoustic",
    "LANDSCAPE": "cinematic", "PERSON": "inspirational",
}


@dataclass(frozen=True)
class RankedCandidate:
    candidate: MusicCandidate
    provider_name: str
    score: float
    reasons: tuple[str, ...]


class BgmSelectTool:
    name = "audio.bgm-select"
    version = "1.0.0"

    def manifest(self) -> dict[str, Any]:
        return {
            "name": self.name, "version": self.version,
            "description": "Search and rank background music for the current Story Plan and Timeline",
            "executionMode": "ASYNC", "resourceClass": "CPU_LIGHT", "timeoutSeconds": 90,
            "supportsCancellation": False, "deterministic": False, "cacheable": False,
            "inputTypes": ["STORY_PLAN", "TIMELINE"],
            "outputTypes": ["BGM_AUDIO", "BGM_CANDIDATE", "BGM_SELECTION"],
        }

    def execute(
        self, request: ToolExecutionRequest,
        report_progress: Callable[[int], None] | None = None,
    ) -> list[ArtifactDescriptor]:
        story_inputs = matching_inputs(request.inputs, "story")
        timeline_inputs = matching_inputs(request.inputs, "timeline")
        beats = read_json_artifact(story_inputs[0]).get("beats", []) if story_inputs else []
        timeline = read_json_artifact(timeline_inputs[0]) if timeline_inputs else {}
        target_duration_ms = int(timeline.get("durationMs") or 30_000)
        limit = max(1, min(settings.music_candidate_limit, 5))
        auto_select = bool(request.parameters.get("autoSelect", False))
        batch_index = max(0, min(int(request.parameters.get("recommendationBatch", 0)), 100))
        seed = str(request.parameters.get("recommendationSeed") or request.trace_context.workflow_run_id or "default")
        excluded_track_ids = {
            str(track_id) for track_id in request.parameters.get("excludedTrackIds", [])
            if isinstance(track_id, (str, int)) and str(track_id).strip()
        }
        profile = _music_profile(beats, seed, batch_index)
        candidate_set_id = f"music_{uuid4().hex}"
        if report_progress is not None:
            report_progress(15)
        candidates = _search_candidates(
            profile, target_duration_ms, limit, excluded_track_ids, seed
        )
        if not candidates:
            logger.info("BGM provider returned no candidates for tags %s", profile.tags)
            return []

        outputs: list[ArtifactDescriptor] = []
        for index, (ranked, source_path) in enumerate(candidates, start=1):
            selected = auto_select and index == 1
            metadata = _candidate_metadata(
                ranked, source_path, index, selected, candidate_set_id, profile, batch_index
            )
            outputs.append(write_bgm_artifact(
                metadata, source_path,
                artifact_type="BGM_AUDIO" if selected else "BGM_CANDIDATE",
            ))
            if report_progress is not None:
                report_progress(20 + round(index / len(candidates) * 80))
        return outputs


def _search_candidates(
    profile: MusicSearchProfile,
    target_duration_ms: int,
    limit: int,
    excluded_track_ids: set[str],
    seed: str,
) -> list[tuple[RankedCandidate, Path]]:
    providers = configured_music_providers()
    pool_limit = min(50, max(20, limit * 8))
    pooled: list[MusicCandidate] = []
    provider_by_name = {provider.name: provider for provider in providers}
    seen: set[tuple[str, str]] = set()
    for provider in providers:
        for candidate in provider.search(profile, target_duration_ms, pool_limit):
            key = (candidate.provider, candidate.track_id)
            if key in seen:
                continue
            seen.add(key)
            pooled.append(candidate)

    ranked = _rank_diverse_candidates(
        pooled, profile, target_duration_ms, limit, excluded_track_ids, seed
    )
    selected: list[tuple[RankedCandidate, Path]] = []
    for item in ranked:
        provider = provider_by_name.get(item.provider_name)
        if provider is None:
            continue
        try:
            path = provider.cache(item.candidate)
        except Exception as exc:
            logger.warning(
                "Music candidate cache failed for %s:%s: %s",
                item.candidate.provider, item.candidate.track_id, exc,
            )
            continue
        selected.append((item, path))
        if len(selected) >= limit:
            break
    return selected


def _rank_diverse_candidates(
    candidates: list[MusicCandidate],
    profile: MusicSearchProfile,
    target_duration_ms: int,
    limit: int,
    excluded_track_ids: set[str],
    seed: str,
) -> list[RankedCandidate]:
    scored: list[RankedCandidate] = []
    for candidate in candidates:
        duration_distance = abs(candidate.duration_ms - target_duration_ms) / max(target_duration_ms, 1) \
            if candidate.duration_ms > 0 else 0.5
        duration_score = max(0.0, 18.0 - min(18.0, duration_distance * 12.0))
        tag_score = 18.0 if candidate.matched_tag == profile.primary_mood else 10.0
        instrumental_score = 8.0 if candidate.instrumental else 0.0
        recent_penalty = 70.0 if candidate.track_id in excluded_track_ids else 0.0
        jitter = _stable_fraction(f"{seed}:{candidate.provider}:{candidate.track_id}") * 4.0
        score = candidate.score * 0.55 + duration_score + tag_score + instrumental_score + jitter - recent_penalty
        reasons = [f"匹配 {candidate.matched_tag or profile.primary_mood} 情绪"]
        if candidate.instrumental:
            reasons.append("器乐曲更适合作为视频背景")
        if candidate.duration_ms > 0:
            reasons.append("时长接近成片")
        if recent_penalty:
            reasons.append("近期已展示，已降低排序")
        scored.append(RankedCandidate(candidate, candidate.provider, round(score, 2), tuple(reasons)))

    scored.sort(key=lambda item: (-item.score, item.candidate.title, item.candidate.track_id))
    fresh = [item for item in scored if item.candidate.track_id not in excluded_track_ids]
    working = fresh if len(fresh) >= limit else scored
    result: list[RankedCandidate] = []
    used_artists: set[str] = set()
    while working and len(result) < limit:
        best_index = 0
        best_value = float("-inf")
        for index, item in enumerate(working):
            artist_key = item.candidate.artist.strip().lower()
            artist_penalty = 16.0 if artist_key and artist_key in used_artists else 0.0
            value = item.score - artist_penalty
            if value > best_value:
                best_index, best_value = index, value
        chosen = working.pop(best_index)
        result.append(chosen)
        if chosen.candidate.artist.strip():
            used_artists.add(chosen.candidate.artist.strip().lower())
    return result


def _music_profile(beats: list[dict[str, Any]], seed: str, batch_index: int) -> MusicSearchProfile:
    mood_weights: dict[str, float] = defaultdict(float)
    for beat in beats:
        role = str(beat.get("role") or "")
        mood = BEAT_ROLE_TO_MOOD.get(role)
        shots = beat.get("shots") if isinstance(beat.get("shots"), list) else []
        duration = int(beat.get("actualDurationMs") or beat.get("targetDurationMs") or 0)
        if duration <= 0:
            duration = sum(
                max(0, int(shot.get("selectedDurationMs") or 0))
                for shot in shots if isinstance(shot, dict)
            ) or 1000
        if mood:
            mood_weights[mood] += duration
        for shot in shots:
            if not isinstance(shot, dict):
                continue
            for reason in shot.get("selectionReasons") or []:
                reason_text = str(reason).upper()
                for token, tag in REASON_TAGS.items():
                    if token in reason_text:
                        mood_weights[tag] += max(300, duration / max(len(shots), 1) * 0.35)

    if not mood_weights:
        mood_weights.update({"cinematic": 2.0, "upbeat": 1.0, "calm": 1.0})
    ranked_tags = sorted(
        mood_weights,
        key=lambda tag: (-mood_weights[tag], _stable_fraction(f"{seed}:{batch_index}:{tag}"), tag),
    )
    supporting = [tag for tag in ("cinematic", "instrumental") if tag not in ranked_tags]
    tags = tuple((ranked_tags + supporting)[:3])
    rotation = batch_index % len(tags)
    rotated = tags[rotation:] + tags[:rotation]
    return MusicSearchProfile(primary_mood=rotated[0], tags=rotated, batch_index=batch_index, seed=seed)


def _candidate_metadata(
    ranked: RankedCandidate,
    source_path: Path,
    rank: int,
    selected: bool,
    candidate_set_id: str,
    profile: MusicSearchProfile,
    batch_index: int,
) -> dict[str, Any]:
    candidate = ranked.candidate
    return {
        "available": True, "provider": candidate.provider, "providerTrackId": candidate.track_id,
        "title": candidate.title, "artist": candidate.artist, "selectedMood": profile.primary_mood,
        "musicProfileTags": list(profile.tags), "matchedTag": candidate.matched_tag,
        "recommendationBatch": batch_index, "recommendationReasons": list(ranked.reasons),
        "bgmDurationMs": candidate.duration_ms or _probe_duration(source_path),
        "rank": rank, "score": ranked.score, "selected": selected,
        "candidateSetId": candidate_set_id,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "sourceUrl": candidate.source_url, "licenseName": candidate.license_name,
        "licenseUrl": candidate.license_url, "attribution": candidate.attribution,
    }


def write_bgm_artifact(
    payload: dict[str, Any], bgm_path: Path | None = None, *, available: bool = True,
    artifact_type: str = "BGM_AUDIO",
) -> ArtifactDescriptor:
    artifact_id = f"art_{uuid4().hex}"
    output_dir = settings.artifact_root / artifact_id
    output_dir.mkdir(parents=True, exist_ok=False)
    if bgm_path is None or not bgm_path.is_file():
        raise ValueError("BGM artifact requires an audio file")
    audio_path = output_dir / "bgm-audio.mp3"
    shutil.copyfile(bgm_path, audio_path)
    (output_dir / "bgm-selection.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    content = audio_path.read_bytes()
    return ArtifactDescriptor(
        artifactId=artifact_id, type=artifact_type, uri=audio_path.resolve().as_uri(),
        mediaType="audio/mpeg", size=len(content),
        contentHash=hashlib.sha256(content).hexdigest(), metadata=payload,
    )


def _stable_fraction(value: str) -> float:
    return int(hashlib.sha256(value.encode("utf-8")).hexdigest()[:8], 16) / 0xFFFFFFFF


def _probe_duration(path: Path) -> int:
    process = subprocess.run([
        settings.ffprobe_path, "-v", "error", "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1", str(path),
    ], capture_output=True, text=True, encoding="utf-8")
    try:
        return round(float(process.stdout.strip()) * 1000)
    except (ValueError, TypeError):
        return 0
