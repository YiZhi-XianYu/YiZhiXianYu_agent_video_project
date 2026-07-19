from __future__ import annotations

import hashlib
import json
import re
import subprocess
from collections.abc import Callable
from pathlib import Path
from typing import Any
from uuid import uuid4

from app.core.config import settings
from app.core.models import ArtifactDescriptor, ToolExecutionRequest
from app.tools.video_probe import VideoProbeTool


PTS_TIME = re.compile(r"pts_time:([0-9]+(?:\.[0-9]+)?)")


class VideoShotDetectTool:
    name = "video.shot-detect"
    version = "1.0.0"

    def manifest(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "version": self.version,
            "description": "Detect ordered video shots and extract one keyframe per shot",
            "executionMode": "ASYNC",
            "resourceClass": "CPU_MEDIUM",
            "timeoutSeconds": 900,
            "supportsCancellation": False,
            "deterministic": True,
            "cacheable": True,
            "inputTypes": ["VIDEO_PROXY"],
            "outputTypes": ["SHOT_LIST", "KEYFRAME_IMAGE"],
        }

    def execute(
        self,
        request: ToolExecutionRequest,
        report_progress: Callable[[int], None] | None = None,
    ) -> list[ArtifactDescriptor]:
        video_input = request.inputs.get("video")
        if video_input is None:
            raise ValueError("video.shot-detect requires inputs.video")
        video_path = VideoProbeTool._uri_to_path(video_input.uri)
        if not video_path.is_file():
            raise ValueError(f"Video proxy not found: {video_path}")

        threshold = self._float_parameter(request.parameters, "sceneThreshold", 0.30, 0.01, 0.99)
        min_duration_ms = self._int_parameter(request.parameters, "minShotDurationMs", 600, 100, 10000)
        source_asset_id = str(request.parameters.get("sourceAssetId") or "")
        if not source_asset_id:
            raise ValueError("video.shot-detect requires parameters.sourceAssetId")

        duration_ms = self._duration_ms(video_path)
        cut_times_ms = self._detect_cut_times(video_path, threshold)
        shots = self.build_shots(duration_ms, cut_times_ms, min_duration_ms)
        if report_progress is not None:
            report_progress(35)

        outputs: list[ArtifactDescriptor] = []
        shot_payloads: list[dict[str, Any]] = []
        for index, shot in enumerate(shots):
            shot_id = f"shot_{uuid4().hex}"
            keyframe = self._extract_keyframe(video_path, shot_id, shot["keyframeMs"])
            outputs.append(keyframe)
            shot_payloads.append({
                "shotId": shot_id,
                "sourceAssetId": source_asset_id,
                "sourceProxyArtifactId": video_input.artifact_id,
                "index": index,
                "startMs": shot["startMs"],
                "endMs": shot["endMs"],
                "durationMs": shot["durationMs"],
                "boundaryConfidence": shot["boundaryConfidence"],
                "keyframeArtifactId": keyframe.artifact_id,
            })
            if report_progress is not None:
                report_progress(35 + int((index + 1) / len(shots) * 55))

        payload = {
            "sourceAssetId": source_asset_id,
            "sourceProxyArtifactId": video_input.artifact_id,
            "durationMs": duration_ms,
            "sceneThreshold": threshold,
            "minShotDurationMs": min_duration_ms,
            "shotCount": len(shot_payloads),
            "shots": shot_payloads,
        }
        outputs.insert(0, self._write_shot_list(payload))
        return outputs

    @staticmethod
    def parse_cut_times(stderr: str) -> list[int]:
        return sorted({round(float(value) * 1000) for value in PTS_TIME.findall(stderr)})

    @staticmethod
    def build_shots(duration_ms: int, cut_times_ms: list[int], min_duration_ms: int) -> list[dict[str, Any]]:
        if duration_ms <= 0:
            raise ValueError("Video duration must be positive")
        accepted = [0]
        for cut in sorted(set(cut_times_ms)):
            if cut <= 0 or cut >= duration_ms:
                continue
            if cut - accepted[-1] < min_duration_ms:
                continue
            if duration_ms - cut < min_duration_ms:
                continue
            accepted.append(cut)
        accepted.append(duration_ms)

        shots: list[dict[str, Any]] = []
        for index in range(len(accepted) - 1):
            start = accepted[index]
            end = accepted[index + 1]
            shots.append({
                "startMs": start,
                "endMs": end,
                "durationMs": end - start,
                "keyframeMs": start + (end - start) // 2,
                "boundaryConfidence": 1.0 if index == 0 else 0.5,
            })
        return shots

    def _detect_cut_times(self, video_path: Path, threshold: float) -> list[int]:
        command = [
            settings.ffmpeg_path,
            "-hide_banner",
            "-i",
            str(video_path),
            "-vf",
            f"select=gt(scene\\,{threshold}),showinfo",
            "-an",
            "-f",
            "null",
            "NUL" if __import__("os").name == "nt" else "/dev/null",
        ]
        process = subprocess.run(
            command,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        if process.returncode != 0:
            raise RuntimeError(process.stderr.strip() or "ffmpeg scene detection failed")
        return self.parse_cut_times(process.stderr)

    def _extract_keyframe(self, video_path: Path, shot_id: str, timestamp_ms: int) -> ArtifactDescriptor:
        artifact_id = f"art_{uuid4().hex}"
        output_dir = settings.artifact_root / artifact_id
        output_dir.mkdir(parents=True, exist_ok=False)
        output_path = output_dir / "keyframe.jpg"
        command = [
            settings.ffmpeg_path,
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-ss",
            f"{timestamp_ms / 1000:.3f}",
            "-i",
            str(video_path),
            "-frames:v",
            "1",
            "-vf",
            "scale='min(960,iw)':-2",
            "-q:v",
            "3",
            str(output_path),
        ]
        process = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace")
        if process.returncode != 0 or not output_path.is_file():
            raise RuntimeError(process.stderr.strip() or "ffmpeg keyframe extraction failed")
        return ArtifactDescriptor(
            artifactId=artifact_id,
            type="KEYFRAME_IMAGE",
            uri=output_path.resolve().as_uri(),
            mediaType="image/jpeg",
            size=output_path.stat().st_size,
            contentHash=self._sha256(output_path),
            metadata={"shotId": shot_id, "timestampMs": timestamp_ms},
        )

    def _write_shot_list(self, payload: dict[str, Any]) -> ArtifactDescriptor:
        artifact_id = f"art_{uuid4().hex}"
        output_dir = settings.artifact_root / artifact_id
        output_dir.mkdir(parents=True, exist_ok=False)
        output_path = output_dir / "shot-list.json"
        content = json.dumps(payload, ensure_ascii=False, indent=2).encode("utf-8")
        output_path.write_bytes(content)
        return ArtifactDescriptor(
            artifactId=artifact_id,
            type="SHOT_LIST",
            uri=output_path.resolve().as_uri(),
            mediaType="application/json",
            size=len(content),
            contentHash=hashlib.sha256(content).hexdigest(),
            metadata={
                "sourceAssetId": payload["sourceAssetId"],
                "sourceProxyArtifactId": payload["sourceProxyArtifactId"],
                "shotCount": payload["shotCount"],
                "shots": payload["shots"],
            },
        )

    def _duration_ms(self, video_path: Path) -> int:
        command = [
            settings.ffprobe_path,
            "-v",
            "error",
            "-show_entries",
            "format=duration",
            "-of",
            "default=noprint_wrappers=1:nokey=1",
            str(video_path),
        ]
        process = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace")
        if process.returncode != 0:
            raise RuntimeError(process.stderr.strip() or "ffprobe duration failed")
        return round(float(process.stdout.strip()) * 1000)

    @staticmethod
    def _float_parameter(parameters: dict[str, Any], key: str, default: float, minimum: float, maximum: float) -> float:
        value = float(parameters.get(key, default))
        if value < minimum or value > maximum:
            raise ValueError(f"{key} must be between {minimum} and {maximum}")
        return value

    @staticmethod
    def _int_parameter(parameters: dict[str, Any], key: str, default: int, minimum: int, maximum: int) -> int:
        value = int(parameters.get(key, default))
        if value < minimum or value > maximum:
            raise ValueError(f"{key} must be between {minimum} and {maximum}")
        return value

    @staticmethod
    def _sha256(path: Path) -> str:
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        return digest.hexdigest()
