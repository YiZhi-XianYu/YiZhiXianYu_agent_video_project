from __future__ import annotations

import hashlib
import json
import subprocess
from collections.abc import Callable
from pathlib import Path
from typing import Any
from uuid import uuid4

from app.core.config import settings
from app.core.models import ArtifactDescriptor, ToolExecutionRequest
from app.tools.video_probe import VideoProbeTool


QUALITY_PROFILES = {
    "4K": {"maxWidth": 3840, "maxHeight": 2160, "crf": 20},
    "2K": {"maxWidth": 2560, "maxHeight": 1440, "crf": 21},
    "1080P": {"maxWidth": 1920, "maxHeight": 1080, "crf": 22},
    "720P": {"maxWidth": 1280, "maxHeight": 720, "crf": 23},
}


class VideoProxyGenerateTool:
    name = "video.proxy-generate"
    version = "1.0.0"

    def manifest(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "version": self.version,
            "description": "Generate a browser-playable low-resolution MP4 proxy",
            "executionMode": "ASYNC",
            "resourceClass": "CPU_MEDIUM",
            "timeoutSeconds": 900,
            "supportsCancellation": False,
            "deterministic": True,
            "cacheable": True,
            "inputTypes": ["VIDEO_SOURCE"],
            "outputTypes": ["VIDEO_PROXY"],
        }

    def execute(
        self,
        request: ToolExecutionRequest,
        report_progress: Callable[[int], None] | None = None,
    ) -> list[ArtifactDescriptor]:
        video_input = request.inputs.get("video")
        if video_input is None:
            raise ValueError("video.proxy-generate requires inputs.video")

        video_path = VideoProbeTool._uri_to_path(video_input.uri)
        if not video_path.is_file():
            raise ValueError(f"Video file not found: {video_path}")

        quality = str(request.parameters.get("quality", "1080P")).upper()
        profile = self.quality_profile(quality)

        artifact_id = f"art_{uuid4().hex}"
        output_dir = settings.artifact_root / artifact_id
        output_dir.mkdir(parents=True, exist_ok=False)
        output_path = output_dir / "video-proxy.mp4"

        duration_seconds = self._duration_seconds(video_path)
        command = self.build_command(video_path, output_path, quality)
        process = subprocess.Popen(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
        )
        if process.stdout is None:
            raise RuntimeError("ffmpeg progress stream is unavailable")
        for line in process.stdout:
            key, separator, value = line.strip().partition("=")
            if separator and key == "out_time" and report_progress is not None:
                report_progress(self._transcode_progress(value, duration_seconds))
        stderr = process.stderr.read() if process.stderr is not None else ""
        return_code = process.wait()
        if return_code != 0:
            raise RuntimeError(stderr.strip() or "ffmpeg proxy generation failed")
        if not output_path.is_file() or output_path.stat().st_size == 0:
            raise RuntimeError("ffmpeg completed without producing a proxy video")

        probe = self._probe_output(output_path)
        content_hash = self._sha256(output_path)
        metadata = {
            **probe,
            "sourceArtifactId": video_input.artifact_id,
            "sourceFileName": video_input.file_name,
            "profile": f"H264_{quality}_30_PROXY",
            "quality": quality,
            "maxWidth": profile["maxWidth"],
            "maxHeight": profile["maxHeight"],
            "targetFps": 30,
            "videoCodec": "h264",
            "audioCodec": "aac" if probe.get("hasAudio") else None,
            "crf": profile["crf"],
            "preset": "veryfast",
        }
        return [
            ArtifactDescriptor(
                artifactId=artifact_id,
                type="VIDEO_PROXY",
                uri=output_path.resolve().as_uri(),
                mediaType="video/mp4",
                size=output_path.stat().st_size,
                contentHash=content_hash,
                metadata=metadata,
            )
        ]

    @staticmethod
    def quality_profile(quality: str) -> dict[str, int]:
        profile = QUALITY_PROFILES.get(quality.upper())
        if profile is None:
            allowed = ", ".join(QUALITY_PROFILES)
            raise ValueError(f"Unsupported proxy quality: {quality}. Allowed values: {allowed}")
        return profile

    @staticmethod
    def build_command(video_path: Path, output_path: Path, quality: str = "1080P") -> list[str]:
        profile = VideoProxyGenerateTool.quality_profile(quality)
        max_width = profile["maxWidth"]
        max_height = profile["maxHeight"]
        scale = (
            "scale="
            f"w='if(gte(iw,ih),min({max_width},iw),min({max_height},iw))':"
            f"h='if(gte(iw,ih),min({max_height},ih),min({max_width},ih))':"
            "force_original_aspect_ratio=decrease:force_divisible_by=2,"
            "fps=30"
        )
        return [
            settings.ffmpeg_path,
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-progress",
            "pipe:1",
            "-nostats",
            "-i",
            str(video_path),
            "-map",
            "0:v:0",
            "-map",
            "0:a?",
            "-vf",
            scale,
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-crf",
            str(profile["crf"]),
            "-pix_fmt",
            "yuv420p",
            "-c:a",
            "aac",
            "-b:a",
            "128k",
            "-movflags",
            "+faststart",
            str(output_path),
        ]

    @staticmethod
    def _probe_output(output_path: Path) -> dict[str, Any]:
        command = [
            settings.ffprobe_path,
            "-v",
            "error",
            "-print_format",
            "json",
            "-show_format",
            "-show_streams",
            str(output_path),
        ]
        process = subprocess.run(command, capture_output=True, text=True, encoding="utf-8")
        if process.returncode != 0:
            raise RuntimeError(process.stderr.strip() or "ffprobe failed for generated proxy")
        return VideoProbeTool._normalize(json.loads(process.stdout))

    @staticmethod
    def _duration_seconds(path: Path) -> float:
        command = [
            settings.ffprobe_path,
            "-v",
            "error",
            "-show_entries",
            "format=duration",
            "-of",
            "default=noprint_wrappers=1:nokey=1",
            str(path),
        ]
        process = subprocess.run(command, capture_output=True, text=True, encoding="utf-8")
        if process.returncode != 0:
            raise RuntimeError(process.stderr.strip() or "ffprobe failed for source video")
        try:
            return float(process.stdout.strip())
        except ValueError as exc:
            raise RuntimeError("ffprobe returned an invalid source duration") from exc

    @staticmethod
    def _transcode_progress(out_time: str, duration_seconds: float) -> int:
        try:
            hours, minutes, seconds = out_time.split(":")
            elapsed = int(hours) * 3600 + int(minutes) * 60 + float(seconds)
        except (TypeError, ValueError):
            return 10
        if duration_seconds <= 0:
            return 10
        return 10 + min(80, int(elapsed / duration_seconds * 80))

    @staticmethod
    def _sha256(path: Path) -> str:
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        return digest.hexdigest()
