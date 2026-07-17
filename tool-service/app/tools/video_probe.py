from __future__ import annotations

import hashlib
import json
import subprocess
from pathlib import Path
from typing import Any
from urllib.parse import unquote, urlparse
from uuid import uuid4

from app.core.config import settings
from app.core.models import ArtifactDescriptor, ToolExecutionRequest


class VideoProbeTool:
    name = "video.probe"
    version = "1.0.0"

    def manifest(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "version": self.version,
            "description": "Read technical metadata from a video with ffprobe",
            "executionMode": "ASYNC",
            "resourceClass": "CPU_LOW",
            "timeoutSeconds": 60,
            "supportsCancellation": False,
            "deterministic": True,
            "cacheable": True,
            "inputTypes": ["VIDEO_SOURCE"],
            "outputTypes": ["VIDEO_METADATA"],
        }

    def execute(self, request: ToolExecutionRequest) -> list[ArtifactDescriptor]:
        video_input = request.inputs.get("video")
        if video_input is None:
            raise ValueError("video.probe requires inputs.video")

        video_path = self._uri_to_path(video_input.uri)
        if not video_path.is_file():
            raise ValueError(f"Video file not found: {video_path}")

        command = [
            settings.ffprobe_path,
            "-v",
            "error",
            "-print_format",
            "json",
            "-show_format",
            "-show_streams",
            str(video_path),
        ]
        process = subprocess.run(command, capture_output=True, text=True, encoding="utf-8")
        if process.returncode != 0:
            raise RuntimeError(process.stderr.strip() or "ffprobe failed")

        raw = json.loads(process.stdout)
        metadata = self._normalize(raw)
        payload = json.dumps(metadata, ensure_ascii=False, indent=2).encode("utf-8")
        content_hash = hashlib.sha256(payload).hexdigest()
        artifact_id = f"art_{uuid4().hex}"
        output_dir = settings.artifact_root / artifact_id
        output_dir.mkdir(parents=True, exist_ok=False)
        output_path = output_dir / "video-metadata.json"
        output_path.write_bytes(payload)

        return [
            ArtifactDescriptor(
                artifactId=artifact_id,
                type="VIDEO_METADATA",
                uri=output_path.resolve().as_uri(),
                mediaType="application/json",
                size=len(payload),
                contentHash=content_hash,
                metadata=metadata,
            )
        ]

    @staticmethod
    def _uri_to_path(uri: str) -> Path:
        parsed = urlparse(uri)
        if parsed.scheme == "file":
            path = unquote(parsed.path)
            if parsed.netloc:
                path = f"//{parsed.netloc}{path}"
            if len(path) >= 3 and path[0] == "/" and path[2] == ":":
                path = path[1:]
            return Path(path)
        if parsed.scheme:
            raise ValueError(f"Unsupported artifact URI for local mode: {uri}")
        return Path(uri)

    @staticmethod
    def _normalize(raw: dict[str, Any]) -> dict[str, Any]:
        streams = raw.get("streams", [])
        video = next((item for item in streams if item.get("codec_type") == "video"), {})
        audio = next((item for item in streams if item.get("codec_type") == "audio"), {})
        format_info = raw.get("format", {})

        duration = VideoProbeTool._float(format_info.get("duration"))
        fps = VideoProbeTool._fps(video.get("avg_frame_rate") or video.get("r_frame_rate"))
        return {
            "durationMs": round(duration * 1000),
            "width": video.get("width"),
            "height": video.get("height"),
            "fps": fps,
            "formatName": format_info.get("format_name"),
            "sizeBytes": VideoProbeTool._int(format_info.get("size")),
            "bitRate": VideoProbeTool._int(format_info.get("bit_rate")),
            "videoCodec": video.get("codec_name"),
            "audioCodec": audio.get("codec_name"),
            "hasAudio": bool(audio),
            "audioSampleRate": VideoProbeTool._int(audio.get("sample_rate")),
            "audioChannels": audio.get("channels"),
        }

    @staticmethod
    def _float(value: Any) -> float:
        try:
            return float(value)
        except (TypeError, ValueError):
            return 0.0

    @staticmethod
    def _int(value: Any) -> int | None:
        try:
            return int(value)
        except (TypeError, ValueError):
            return None

    @staticmethod
    def _fps(value: Any) -> float | None:
        if not value or value == "0/0":
            return None
        try:
            numerator, denominator = str(value).split("/", maxsplit=1)
            return round(float(numerator) / float(denominator), 3)
        except (ValueError, ZeroDivisionError):
            return None

