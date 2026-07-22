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
from app.tools.timeline_validator import TimelineValidator
from app.tools.artifact_json import matching_inputs, read_json_artifact


class VideoRenderTool:
    name = "video.render"
    version = "1.0.0"

    def manifest(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "version": self.version,
            "description": "Compile a validated TIMELINE into an H.264 MP4 final video via FFmpeg concat filter graph",
            "executionMode": "ASYNC",
            "resourceClass": "CPU_MEDIUM",
            "timeoutSeconds": 900,
            "supportsCancellation": False,
            "deterministic": True,
            "cacheable": False,
            "inputTypes": ["TIMELINE"],
            "outputTypes": ["RENDERED_VIDEO"],
        }

    def execute(
        self,
        request: ToolExecutionRequest,
        report_progress: Callable[[int], None] | None = None,
    ) -> list[ArtifactDescriptor]:
        timeline_inputs = matching_inputs(request.inputs, "timeline")
        if len(timeline_inputs) != 1:
            raise ValueError("video.render requires one TIMELINE input")
        timeline = read_json_artifact(timeline_inputs[0])

        errors = TimelineValidator.validate(timeline)
        if errors:
            raise ValueError("Timeline validation failed: " + "; ".join(errors))

        proxy_map, missing = _resolve_proxy_paths(timeline)
        if missing:
            raise ValueError(f"Proxy files not found: {', '.join(missing)}")

        audio_map = _probe_audio_map(proxy_map)
        if report_progress is not None:
            report_progress(5)

        canvas = timeline["canvas"]
        clips = timeline["tracks"][0]["clips"]
        input_args, filter_complex = _build_filter_graph(clips, canvas, proxy_map, audio_map)
        command = _assemble_command(input_args, filter_complex)

        total_duration_sec = timeline["durationMs"] / 1000.0

        artifact_id = f"art_{uuid4().hex}"
        output_dir = settings.artifact_root / artifact_id
        output_dir.mkdir(parents=True, exist_ok=False)
        output_path = output_dir / "rendered-video.mp4"

        command.append(str(output_path))

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
            key, _, value = line.strip().partition("=")
            if key == "out_time" and report_progress is not None:
                report_progress(_transcode_progress(value, total_duration_sec))
        stderr = process.stderr.read() if process.stderr is not None else ""
        return_code = process.wait()
        if return_code != 0:
            raise RuntimeError(stderr.strip() or "ffmpeg render failed")
        if not output_path.is_file() or output_path.stat().st_size == 0:
            raise RuntimeError("ffmpeg completed without producing a rendered video")

        probe = _probe_output(output_path)
        content_hash = _sha256(output_path)
        metadata = {
            "timelineId": timeline["timelineId"],
            "sourceTimelineArtifactId": timeline_inputs[0].artifact_id,
            "canvas": canvas,
            "durationMs": timeline["durationMs"],
            "clipCount": len(clips),
            "sourceProxyArtifactIds": sorted(proxy_map),
            "videoCodec": "h264",
            "audioCodec": "aac" if probe.get("hasAudio") else None,
            "preset": "veryfast",
            "crf": 20,
        }
        if report_progress is not None:
            report_progress(100)

        return [
            ArtifactDescriptor(
                artifactId=artifact_id,
                type="RENDERED_VIDEO",
                uri=output_path.resolve().as_uri(),
                mediaType="video/mp4",
                size=output_path.stat().st_size,
                contentHash=content_hash,
                metadata=metadata,
            )
        ]


def _resolve_proxy_paths(timeline: dict[str, Any]) -> tuple[dict[str, Path], list[str]]:
    paths: dict[str, Path] = {}
    missing: list[str] = []
    seen: set[str] = set()
    for clip in timeline["tracks"][0]["clips"]:
        aid = clip.get("sourceProxyArtifactId", "")
        if not aid or aid in seen:
            continue
        seen.add(aid)
        p = settings.artifact_root / aid / "video-proxy.mp4"
        if p.is_file():
            paths[aid] = p
        else:
            missing.append(aid)
    return paths, missing


def _probe_audio_map(proxy_map: dict[str, Path]) -> dict[str, bool]:
    result: dict[str, bool] = {}
    for aid, path in proxy_map.items():
        result[aid] = _probe_source_audio(path)
    return result


def _probe_source_audio(proxy_path: Path) -> bool:
    command = [
        settings.ffprobe_path, "-v", "error",
        "-select_streams", "a",
        "-show_entries", "stream=codec_type",
        "-of", "default=noprint_wrappers=1:nokey=1",
        str(proxy_path),
    ]
    process = subprocess.run(command, capture_output=True, text=True, encoding="utf-8")
    return process.returncode == 0 and process.stdout.strip() == "audio"


def _build_filter_graph(
    clips: list[dict[str, Any]],
    canvas: dict[str, Any],
    proxy_map: dict[str, Path],
    audio_map: dict[str, bool],
) -> tuple[list[str], str]:
    unique_sources = list(dict.fromkeys(c["sourceProxyArtifactId"] for c in clips))
    source_to_index = {sid: idx for idx, sid in enumerate(unique_sources)}
    inputs = [str(proxy_map[sid]) for sid in unique_sources]

    width = canvas["width"]
    height = canvas["height"]
    fps = canvas["fps"]

    video_chains: list[str] = []
    audio_chains: list[str] = []

    for i, clip in enumerate(clips):
        src_idx = source_to_index[clip["sourceProxyArtifactId"]]
        src_in = clip["sourceInMs"] / 1000.0
        dur = (clip["timelineOutMs"] - clip["timelineInMs"]) / 1000.0

        video_chains.append(
            f"[{src_idx}:v]"
            f"trim=start={src_in:.3f}:duration={dur:.3f},setpts=PTS-STARTPTS,"
            f"scale={width}:{height}:force_original_aspect_ratio=decrease:force_divisible_by=2,"
            f"pad={width}:{height}:(ow-iw)/2:(oh-ih)/2:color=black,"
            f"fps={fps},format=yuv420p"
            f"[v{i}]"
        )

        sid = clip["sourceProxyArtifactId"]
        if audio_map.get(sid, False):
            audio_chains.append(
                f"[{src_idx}:a]"
                f"atrim=start={src_in:.3f}:duration={dur:.3f},asetpts=PTS-STARTPTS"
                f"[a{i}]"
            )
        else:
            audio_chains.append(
                f"anullsrc=r=48000:cl=stereo:d={dur:.3f}"
                f"[a{i}]"
            )

    n = len(clips)
    v_labels = "".join(f"[v{j}]" for j in range(n))
    a_labels = "".join(f"[a{j}]" for j in range(n))
    concat_v = f"{v_labels}concat=n={n}:v=1:a=0[outv]"
    concat_a = f"{a_labels}concat=n={n}:v=0:a=1[outa]"

    filter_complex = "; ".join(video_chains + audio_chains + [concat_v, concat_a])
    return inputs, filter_complex


def _assemble_command(input_paths: list[str], filter_complex: str) -> list[str]:
    cmd = [
        settings.ffmpeg_path, "-hide_banner", "-loglevel", "error", "-y",
        "-progress", "pipe:1", "-nostats",
    ]
    for p in input_paths:
        cmd.extend(["-i", p])
    cmd.extend(["-filter_complex", filter_complex])
    cmd.extend([
        "-map", "[outv]", "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
        "-pix_fmt", "yuv420p",
    ])
    cmd.extend(["-map", "[outa]", "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "2"])
    cmd.extend(["-movflags", "+faststart"])
    return cmd


def _transcode_progress(out_time: str, duration_seconds: float) -> int:
    try:
        h, m, s = out_time.split(":")
        elapsed = int(h) * 3600 + int(m) * 60 + float(s)
    except (TypeError, ValueError):
        return 10
    if duration_seconds <= 0:
        return 10
    return 10 + min(80, int(elapsed / duration_seconds * 80))


def _probe_output(output_path: Path) -> dict[str, Any]:
    command = [
        settings.ffprobe_path, "-v", "error",
        "-print_format", "json", "-show_format", "-show_streams",
        str(output_path),
    ]
    process = subprocess.run(command, capture_output=True, text=True, encoding="utf-8")
    if process.returncode != 0:
        raise RuntimeError(process.stderr.strip() or "ffprobe failed for rendered video")
    streams = json.loads(process.stdout).get("streams", [])
    has_audio = any(s.get("codec_type") == "audio" for s in streams)
    return {"hasAudio": has_audio}


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()
