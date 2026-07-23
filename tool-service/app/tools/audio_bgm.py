"""BGM selection tool — maps story beat roles to background music moods."""

from __future__ import annotations

import hashlib
import json
import logging
import subprocess
from collections.abc import Callable
from pathlib import Path
from typing import Any
from uuid import uuid4

from app.core.config import settings
from app.core.models import ArtifactDescriptor, ToolExecutionRequest
from app.tools.artifact_json import matching_inputs, read_json_artifact

logger = logging.getLogger(__name__)

# Mood-to-beat-role mapping for travel vlog storytelling
BEAT_ROLE_TO_MOOD = {
    "HOOK":    "energetic",
    "INTRO":   "calm",
    "JOURNEY": "upbeat",
    "CLIMAX":  "epic",
    "ENDING":  "serene",
}

# BGM catalog — mood → list of candidate filenames (looked up in settings.bgm_library_root)
_DEFAULT_BGM_CATALOG = {
    "energetic": ["energetic_travel.mp3", "upbeat_adventure.mp3"],
    "calm":      ["calm_morning.mp3", "gentle_start.mp3"],
    "upbeat":    ["upbeat_journey.mp3", "walking_beat.mp3"],
    "epic":      ["epic_reveal.mp3", "cinematic_peak.mp3"],
    "serene":    ["serene_ending.mp3", "soft_close.mp3"],
}


class BgmSelectTool:
    name = "audio.bgm-select"
    version = "1.0.0"

    def manifest(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "version": self.version,
            "description": "Select background music from a royalty-free catalog based on story beat roles",
            "executionMode": "ASYNC",
            "resourceClass": "CPU_LIGHT",
            "timeoutSeconds": 30,
            "supportsCancellation": False,
            "deterministic": True,
            "cacheable": True,
            "inputTypes": ["STORY_PLAN"],
            "outputTypes": ["BGM_AUDIO"],
        }

    def execute(
        self,
        request: ToolExecutionRequest,
        report_progress: Callable[[int], None] | None = None,
    ) -> list[ArtifactDescriptor]:
        story_inputs = matching_inputs(request.inputs, "story")
        beats: list[dict[str, Any]] = []
        if story_inputs:
            story = read_json_artifact(story_inputs[0])
            beats = story.get("beats", [])

        # Determine dominant mood from beat roles
        mood = _dominant_mood(beats)
        bgm_path = _find_bgm(mood)

        if bgm_path is None or not bgm_path.is_file():
            logger.info("BGM not available for mood '%s' — producing empty selection", mood)
            payload = {
                "available": False,
                "selectedMood": mood,
                "bgmPath": None,
                "bgmDurationMs": 0,
                "message": "No BGM files found in library. Place royalty-free MP3 files in runtime/bgm/",
            }
            return [write_bgm_artifact(payload, available=False)]

        duration_ms = _probe_duration(bgm_path)
        payload = {
            "available": True,
            "selectedMood": mood,
            "bgmPath": str(bgm_path.resolve()),
            "bgmFileName": bgm_path.name,
            "bgmDurationMs": duration_ms,
        }

        if report_progress is not None:
            report_progress(100)

        return [write_bgm_artifact(payload, bgm_path)]


def write_bgm_artifact(payload: dict[str, Any], bgm_path: Path | None = None, *, available: bool = True) -> ArtifactDescriptor:
    artifact_id = f"art_{uuid4().hex}"
    output_dir = settings.artifact_root / artifact_id
    output_dir.mkdir(parents=True, exist_ok=False)

    # Write metadata JSON
    meta_path = output_dir / "bgm-selection.json"
    meta_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    uri = meta_path.resolve().as_uri()
    size = meta_path.stat().st_size
    content_hash = hashlib.sha256(meta_path.read_bytes()).hexdigest()

    return ArtifactDescriptor(
        artifactId=artifact_id,
        type="BGM_AUDIO",
        uri=uri,
        mediaType="application/json",
        size=size,
        contentHash=content_hash,
        metadata=payload,
    )


def _dominant_mood(beats: list[dict[str, Any]]) -> str:
    """Pick the mood matching the majority beat role, defaulting to CLIMAX."""
    if not beats:
        return "epic"
    # Weight: climax > journey > hook > intro > ending
    weights = {"CLIMAX": 5, "JOURNEY": 4, "HOOK": 3, "INTRO": 2, "ENDING": 1}
    best_role = max(beats, key=lambda b: weights.get(b.get("role", ""), 0))
    role = best_role.get("role", "CLIMAX")
    return BEAT_ROLE_TO_MOOD.get(role, "epic")


def _find_bgm(mood: str) -> Path | None:
    """Find a BGM file for the given mood from the library directory."""
    library_root = settings.bgm_library_root
    if not library_root.exists():
        return None

    candidates = _DEFAULT_BGM_CATALOG.get(mood, [])
    for filename in candidates:
        candidate = library_root / filename
        if candidate.is_file():
            return candidate

    # Fallback: any MP3 in the library directory
    mp3_files = sorted(library_root.glob("*.mp3"))
    if mp3_files:
        return mp3_files[0]

    return None


def _probe_duration(path: Path) -> int:
    """Get audio duration in milliseconds via ffprobe."""
    command = [
        settings.ffprobe_path, "-v", "error",
        "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1",
        str(path),
    ]
    process = subprocess.run(command, capture_output=True, text=True, encoding="utf-8")
    try:
        return round(float(process.stdout.strip()) * 1000)
    except (ValueError, TypeError):
        return 0
