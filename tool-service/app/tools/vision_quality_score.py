from __future__ import annotations

import math
import subprocess
from collections.abc import Callable
from pathlib import Path
from typing import Any

from app.core.config import settings
from app.core.models import ArtifactDescriptor, ToolExecutionRequest
from app.tools.artifact_json import matching_inputs, read_json_artifact, write_json_artifact
from app.tools.video_probe import VideoProbeTool


FRAME_WIDTH = 160
FRAME_HEIGHT = 90


class VisionQualityScoreTool:
    name = "vision.quality-score"
    version = "1.0.0"

    def manifest(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "version": self.version,
            "description": "Compute deterministic shot clarity, exposure, stability and composition scores",
            "executionMode": "ASYNC",
            "resourceClass": "CPU_MEDIUM",
            "timeoutSeconds": 900,
            "supportsCancellation": False,
            "deterministic": True,
            "cacheable": True,
            "inputTypes": ["VIDEO_PROXY", "SHOT_LIST"],
            "outputTypes": ["SHOT_QUALITY"],
        }

    def execute(
        self,
        request: ToolExecutionRequest,
        report_progress: Callable[[int], None] | None = None,
    ) -> list[ArtifactDescriptor]:
        videos = matching_inputs(request.inputs, "video")
        shot_lists = matching_inputs(request.inputs, "shots")
        if len(videos) != 1 or len(shot_lists) != 1:
            raise ValueError("vision.quality-score requires one VIDEO_PROXY and one SHOT_LIST")
        video_path = VideoProbeTool._uri_to_path(videos[0].uri)
        shot_payload = read_json_artifact(shot_lists[0])
        sample_frames = int(request.parameters.get("sampleFrames", 3))
        if sample_frames < 2 or sample_frames > 7:
            raise ValueError("sampleFrames must be between 2 and 7")

        scores: list[dict[str, Any]] = []
        shots = shot_payload.get("shots") or []
        for index, shot in enumerate(shots):
            frames = [self._read_frame(video_path, timestamp) for timestamp in self._sample_times(shot, sample_frames)]
            components = self.score_frames(frames)
            scores.append({
                "shotId": shot["shotId"],
                "sourceAssetId": shot["sourceAssetId"],
                "sourceProxyArtifactId": shot["sourceProxyArtifactId"],
                "shotListArtifactId": shot_lists[0].artifact_id,
                "keyframeArtifactId": shot["keyframeArtifactId"],
                "index": shot["index"],
                "startMs": shot["startMs"],
                "endMs": shot["endMs"],
                "durationMs": shot["durationMs"],
                "boundaryConfidence": shot["boundaryConfidence"],
                **components,
                "visualFingerprint": self.visual_fingerprint(frames[len(frames) // 2]),
                "reasonCodes": self.reason_codes(components),
            })
            if report_progress is not None:
                report_progress(20 + int((index + 1) / max(1, len(shots)) * 70))

        payload = {
            "schemaVersion": "1.0",
            "sourceAssetId": shot_payload["sourceAssetId"],
            "sourceProxyArtifactId": videos[0].artifact_id,
            "sourceShotListArtifactId": shot_lists[0].artifact_id,
            "scoreModel": "DETERMINISTIC_FRAME_METRICS_V2",
            "weights": {
                "clarity": 0.30,
                "exposure": 0.22,
                "stability": 0.18,
                "composition": 0.18,
                "motionInterest": 0.12,
            },
            "shots": scores,
        }
        return [write_json_artifact(
            "SHOT_QUALITY",
            "shot-quality.json",
            payload,
            {
                "sourceAssetId": payload["sourceAssetId"],
                "sourceProxyArtifactId": payload["sourceProxyArtifactId"],
                "sourceShotListArtifactId": payload["sourceShotListArtifactId"],
                "shotCount": len(scores),
                "shots": scores,
            },
        )]

    @staticmethod
    def score_frames(frames: list[bytes]) -> dict[str, float]:
        if len(frames) < 2 or any(len(frame) != FRAME_WIDTH * FRAME_HEIGHT for frame in frames):
            raise ValueError("Quality scoring requires at least two normalized grayscale frames")
        clarity = sum(VisionQualityScoreTool._clarity(frame) for frame in frames) / len(frames)
        exposure = sum(VisionQualityScoreTool._exposure(frame) for frame in frames) / len(frames)
        composition = sum(VisionQualityScoreTool._composition(frame) for frame in frames) / len(frames)
        differences = [
            sum(abs(left - right) for left, right in zip(frames[index - 1], frames[index]))
            / (len(frames[index]) * 255)
            for index in range(1, len(frames))
        ]
        motion_level = sum(differences) / len(differences)
        stability = max(0.0, min(1.0, 1.0 - motion_level * 2.4))
        motion_interest = max(0.0, 1.0 - abs(motion_level - 0.08) / 0.08)
        total = (
            0.30 * clarity
            + 0.22 * exposure
            + 0.18 * stability
            + 0.18 * composition
            + 0.12 * motion_interest
        )
        return {key: round(value, 4) for key, value in {
            "clarity": clarity,
            "exposure": exposure,
            "stability": stability,
            "composition": composition,
            "motionLevel": motion_level,
            "motionInterest": motion_interest,
            "qualityScore": total,
        }.items()}

    @staticmethod
    def reason_codes(components: dict[str, float]) -> list[str]:
        codes = []
        labels = {
            "clarity": "HIGH_CLARITY",
            "exposure": "BALANCED_EXPOSURE",
            "stability": "STABLE_MOTION",
            "composition": "BALANCED_COMPOSITION",
            "motionInterest": "INTERESTING_MOTION",
        }
        for key, code in labels.items():
            if components[key] >= 0.70:
                codes.append(code)
        if components["qualityScore"] < 0.45:
            codes.append("LOW_VISUAL_QUALITY")
        return codes or ["ACCEPTABLE_VISUAL_QUALITY"]

    @staticmethod
    def _clarity(frame: bytes) -> float:
        values: list[int] = []
        for y in range(1, FRAME_HEIGHT - 1):
            row = y * FRAME_WIDTH
            for x in range(1, FRAME_WIDTH - 1):
                center = frame[row + x]
                laplacian = 4 * center - frame[row + x - 1] - frame[row + x + 1]
                laplacian -= frame[row - FRAME_WIDTH + x] + frame[row + FRAME_WIDTH + x]
                values.append(laplacian)
        mean = sum(values) / len(values)
        variance = sum((value - mean) ** 2 for value in values) / len(values)
        return max(0.0, min(1.0, math.log1p(variance) / math.log1p(1800)))

    @staticmethod
    def _exposure(frame: bytes) -> float:
        mean = sum(frame) / (len(frame) * 255)
        clipped = sum(value < 12 or value > 243 for value in frame) / len(frame)
        centered = max(0.0, 1.0 - abs(mean - 0.5) / 0.5)
        return max(0.0, min(1.0, centered * (1.0 - clipped)))

    @staticmethod
    def _composition(frame: bytes) -> float:
        left = right = top = bottom = 0
        for y in range(FRAME_HEIGHT):
            row = y * FRAME_WIDTH
            for x in range(FRAME_WIDTH):
                value = frame[row + x]
                if x < FRAME_WIDTH // 2:
                    left += value
                else:
                    right += value
                if y < FRAME_HEIGHT // 2:
                    top += value
                else:
                    bottom += value
        horizontal = 1.0 - abs(left - right) / max(1, left + right)
        vertical = 1.0 - abs(top - bottom) / max(1, top + bottom)
        return max(0.0, min(1.0, (horizontal + vertical) / 2))

    @staticmethod
    def visual_fingerprint(frame: bytes) -> str:
        if len(frame) != FRAME_WIDTH * FRAME_HEIGHT:
            raise ValueError("Visual fingerprint requires one normalized grayscale frame")
        values: list[float] = []
        for grid_y in range(8):
            start_y = grid_y * FRAME_HEIGHT // 8
            end_y = (grid_y + 1) * FRAME_HEIGHT // 8
            for grid_x in range(8):
                start_x = grid_x * FRAME_WIDTH // 8
                end_x = (grid_x + 1) * FRAME_WIDTH // 8
                total = 0
                count = 0
                for y in range(start_y, end_y):
                    row = y * FRAME_WIDTH
                    for x in range(start_x, end_x):
                        total += frame[row + x]
                        count += 1
                values.append(total / count)
        mean = sum(values) / len(values)
        fingerprint = 0
        for value in values:
            fingerprint = (fingerprint << 1) | int(value >= mean)
        return f"{fingerprint:016x}"

    def _read_frame(self, video_path: Path, timestamp_ms: int) -> bytes:
        command = [
            settings.ffmpeg_path,
            "-hide_banner", "-loglevel", "error", "-ss", f"{timestamp_ms / 1000:.3f}",
            "-i", str(video_path), "-frames:v", "1", "-vf", f"scale={FRAME_WIDTH}:{FRAME_HEIGHT},format=gray",
            "-f", "rawvideo", "pipe:1",
        ]
        process = subprocess.run(command, capture_output=True)
        if process.returncode != 0 or len(process.stdout) != FRAME_WIDTH * FRAME_HEIGHT:
            message = process.stderr.decode("utf-8", errors="replace").strip()
            raise RuntimeError(message or "ffmpeg frame sampling failed")
        return process.stdout

    @staticmethod
    def _sample_times(shot: dict[str, Any], count: int) -> list[int]:
        start = int(shot["startMs"])
        duration = int(shot["durationMs"])
        return [start + round(duration * (index + 1) / (count + 1)) for index in range(count)]
