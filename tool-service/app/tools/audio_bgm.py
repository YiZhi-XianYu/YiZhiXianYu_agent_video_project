"""Search, rank and materialize background-music candidates."""

from __future__ import annotations

import hashlib
import json
import logging
import shutil
import subprocess
from collections.abc import Callable
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from uuid import uuid4

from app.core.config import settings
from app.core.models import ArtifactDescriptor, ToolExecutionRequest
from app.music.providers import MusicCandidate, configured_music_providers
from app.tools.artifact_json import matching_inputs, read_json_artifact

logger = logging.getLogger(__name__)

BEAT_ROLE_TO_MOOD = {
    "HOOK": "energetic", "INTRO": "calm", "JOURNEY": "upbeat",
    "CLIMAX": "epic", "ENDING": "serene",
}


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
        mood = _dominant_mood(beats)
        target_duration_ms = int(timeline.get("durationMs") or 30_000)
        limit = max(1, min(settings.music_candidate_limit, 5))
        auto_select = bool(request.parameters.get("autoSelect", False))
        candidate_set_id = f"music_{uuid4().hex}"
        if report_progress is not None:
            report_progress(15)
        candidates = _search_candidates(mood, target_duration_ms, limit)
        if not candidates:
            logger.info("BGM provider returned no candidates for mood '%s'", mood)
            return []

        outputs: list[ArtifactDescriptor] = []
        for index, (candidate, source_path) in enumerate(candidates, start=1):
            selected = auto_select and index == 1
            metadata = _candidate_metadata(
                candidate, source_path, index, selected, candidate_set_id
            )
            outputs.append(write_bgm_artifact(
                metadata, source_path,
                artifact_type="BGM_AUDIO" if selected else "BGM_CANDIDATE",
            ))
            if report_progress is not None:
                report_progress(20 + round(index / len(candidates) * 80))
        return outputs


def _search_candidates(mood: str, target_duration_ms: int, limit: int) -> list[tuple[MusicCandidate, Path]]:
    selected: list[tuple[MusicCandidate, Path]] = []
    seen: set[tuple[str, str]] = set()
    for provider in configured_music_providers():
        for candidate in provider.search(mood, target_duration_ms, limit):
            key = (candidate.provider, candidate.track_id)
            if key in seen:
                continue
            try:
                path = provider.cache(candidate)
            except Exception as exc:
                logger.warning("Music candidate cache failed for %s:%s: %s", *key, exc)
                continue
            seen.add(key)
            selected.append((candidate, path))
            if len(selected) >= limit:
                return selected
    return selected


def _candidate_metadata(
    candidate: MusicCandidate,
    source_path: Path,
    rank: int,
    selected: bool,
    candidate_set_id: str,
) -> dict[str, Any]:
    return {
        "available": True, "provider": candidate.provider, "providerTrackId": candidate.track_id,
        "title": candidate.title, "artist": candidate.artist, "selectedMood": candidate.mood,
        "bgmDurationMs": candidate.duration_ms or _probe_duration(source_path),
        "rank": rank, "score": candidate.score, "selected": selected,
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


def _dominant_mood(beats: list[dict[str, Any]]) -> str:
    if not beats:
        return "epic"
    weights = {"CLIMAX": 5, "JOURNEY": 4, "HOOK": 3, "INTRO": 2, "ENDING": 1}
    role = max(beats, key=lambda beat: weights.get(beat.get("role", ""), 0)).get("role", "CLIMAX")
    return BEAT_ROLE_TO_MOOD.get(role, "epic")


def _probe_duration(path: Path) -> int:
    process = subprocess.run([
        settings.ffprobe_path, "-v", "error", "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1", str(path),
    ], capture_output=True, text=True, encoding="utf-8")
    try:
        return round(float(process.stdout.strip()) * 1000)
    except (ValueError, TypeError):
        return 0
