from __future__ import annotations

import hashlib
import json
import subprocess
import tempfile
from collections.abc import Callable
from pathlib import Path
from typing import Any
from uuid import uuid4

from app.core.config import settings
from app.core.errors import ToolExecutionError
from app.core.models import ArtifactDescriptor, ToolExecutionRequest
from app.tools.timeline_validator import TimelineValidator
from app.tools.artifact_json import matching_inputs, read_json_artifact


class VideoRenderTool:
    name = "video.render"
    version = "1.1.0"

    def manifest(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "version": self.version,
            "description": "Compile a validated TIMELINE into an H.264 MP4 final video via FFmpeg with transitions, BGM, and subtitles",
            "executionMode": "ASYNC",
            "resourceClass": "CPU_MEDIUM",
            "resourceGroup": "RENDER",
            "timeoutSeconds": 900,
            "supportsCancellation": False,
            "deterministic": True,
            "cacheable": False,
            "inputTypes": ["TIMELINE", "BGM_AUDIO", "SUBTITLE_SRT"],
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

        # Optional BGM and subtitle inputs
        bgm_inputs = matching_inputs(request.inputs, "bgm")
        srt_inputs = matching_inputs(request.inputs, "subtitle")

        bgm_path: Path | None = None
        if bgm_inputs:
            bgm_path = _resolve_extra_path(bgm_inputs[0].uri)

        srt_path: Path | None = None
        if srt_inputs:
            srt_path = _resolve_extra_path(srt_inputs[0].uri)

        canvas = timeline["canvas"]
        clips = timeline["tracks"][0]["clips"]
        input_args, filter_complex, final_video_label, final_audio_label = _build_filter_graph(
            clips, canvas, proxy_map, audio_map,
            bgm_path=bgm_path, srt_path=srt_path,
        )
        command = _assemble_command(input_args, filter_complex, final_video_label, final_audio_label)

        total_duration_sec = timeline["durationMs"] / 1000.0

        artifact_id = f"art_{uuid4().hex}"
        output_dir = settings.artifact_root / artifact_id
        output_dir.mkdir(parents=True, exist_ok=False)
        output_path = output_dir / "rendered-video.mp4"

        command.append(str(output_path))

        # A file-backed stderr stream avoids deadlocking when FFmpeg emits more
        # diagnostics than an unread pipe can buffer while progress is parsed.
        with tempfile.TemporaryFile(mode="w+", encoding="utf-8") as stderr_stream:
            process = subprocess.Popen(
                command,
                stdout=subprocess.PIPE,
                stderr=stderr_stream,
                text=True,
                encoding="utf-8",
            )
            if process.stdout is None:
                raise RuntimeError("ffmpeg progress stream is unavailable")
            for line in process.stdout:
                key, _, value = line.strip().partition("=")
                if key == "out_time" and report_progress is not None:
                    report_progress(_transcode_progress(value, total_duration_sec))
            return_code = process.wait()
            stderr_stream.seek(0)
            stderr = stderr_stream.read()
        if return_code != 0:
            raise _ffmpeg_render_error(stderr, return_code)
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
            "hasBgm": bgm_path is not None,
            "hasSubtitles": srt_path is not None,
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


# ── Path resolution ─────────────────────────────────────────────────────────

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


def _resolve_extra_path(uri: str) -> Path | None:
    """Resolve a BGM or SRT artifact URI to a local path."""
    from urllib.parse import unquote, urlparse
    parsed = urlparse(uri)
    path_str = unquote(parsed.path)
    # Windows drive letter fix
    if len(path_str) >= 3 and path_str[0] == "/" and path_str[2] == ":":
        path_str = path_str[1:]
    p = Path(path_str)
    return p if p.is_file() else None


# ── Audio probing ───────────────────────────────────────────────────────────

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


# ── Filter graph construction ───────────────────────────────────────────────

def _build_filter_graph(
    clips: list[dict[str, Any]],
    canvas: dict[str, Any],
    proxy_map: dict[str, Path],
    audio_map: dict[str, bool],
    *,
    bgm_path: Path | None = None,
    srt_path: Path | None = None,
) -> tuple[list[str], str, str, str]:
    """Build a transition-aware FFmpeg filter graph.

    Returns (input_paths, filter_complex_string, final_video_label, final_audio_label).
    """
    unique_sources = list(dict.fromkeys(c["sourceProxyArtifactId"] for c in clips))
    source_to_index = {sid: idx for idx, sid in enumerate(unique_sources)}
    inputs = [str(proxy_map[sid]) for sid in unique_sources]
    bgm_input_index = len(inputs)  # BGM gets the next index if present

    width = canvas["width"]
    height = canvas["height"]
    fps = canvas["fps"]

    n = len(clips)
    clip_durations_sec = [(clip["timelineOutMs"] - clip["timelineInMs"]) / 1000.0 for clip in clips]

    # ── Step 1: Per-clip video intermediates [s0]...[s{N-1}] ──
    clip_chains: list[str] = []
    for i, clip in enumerate(clips):
        src_idx = source_to_index[clip["sourceProxyArtifactId"]]
        src_in = clip["sourceInMs"] / 1000.0
        dur = clip_durations_sec[i]

        trans = clip.get("transitionIn", {})
        trans_type = trans.get("type", "CUT")
        trans_dur_ms = trans.get("durationMs", 0)

        # Base chain: trim → setpts → scale → pad → fps → format
        base = (
            f"[{src_idx}:v]"
            f"trim=start={src_in:.3f}:duration={dur:.3f},setpts=PTS-STARTPTS,settb=AVTB,"
            f"scale={width}:{height}:force_original_aspect_ratio=decrease:force_divisible_by=2,"
            f"pad={width}:{height}:(ow-iw)/2:(oh-ih)/2:color=black,"
            f"fps={fps},format=yuv420p"
        )

        # FADE: add fade-in filter to the clip
        if trans_type == "FADE":
            fade_sec = trans_dur_ms / 1000.0
            clip_chains.append(f"{base},fade=t=in:d={fade_sec:.3f}[s{i}]")
        else:
            clip_chains.append(f"{base}[s{i}]")

    # ── Step 2: Build transition chain ──
    acc_v_label = "[s0]"
    acc_v_duration = clip_durations_sec[0]
    transition_filters: list[str] = []
    xfade_counter = 0

    for i in range(1, n):
        trans = clips[i].get("transitionIn", {})
        trans_type = trans.get("type", "CUT")
        trans_dur_ms = trans.get("durationMs", 0)
        trans_dur_sec = trans_dur_ms / 1000.0

        if trans_type in ("CUT", "FADE"):
            # Simple concat (FADE already applied per-clip)
            new_label = f"[vc{xfade_counter}]"
            transition_filters.append(
                f"{acc_v_label}[s{i}]concat=n=2:v=1:a=0{new_label}"
            )
            acc_v_label = new_label
            acc_v_duration += clip_durations_sec[i]
            xfade_counter += 1
        elif trans_type == "CROSS_DISSOLVE":
            new_label = f"[vf{xfade_counter}]"
            offset = acc_v_duration - trans_dur_sec
            if offset < 0:
                offset = 0.0
            transition_filters.append(
                f"{acc_v_label}[s{i}]xfade=transition=fade:"
                f"duration={trans_dur_sec:.3f}:offset={offset:.3f}{new_label}"
            )
            acc_v_label = new_label
            acc_v_duration = acc_v_duration + clip_durations_sec[i] - trans_dur_sec
            xfade_counter += 1

    final_video_label = acc_v_label

    # ── Step 3: Per-clip audio intermediates [a0]...[a{N-1}] ──
    audio_chains: list[str] = []
    for i, clip in enumerate(clips):
        sid = clip["sourceProxyArtifactId"]
        src_idx = source_to_index[sid]
        src_in = clip["sourceInMs"] / 1000.0
        dur = clip_durations_sec[i]

        trans = clip.get("transitionIn", {})
        trans_type = trans.get("type", "CUT")
        trans_dur_ms = trans.get("durationMs", 0)

        if audio_map.get(sid, False):
            base_audio = (
                f"[{src_idx}:a]"
                f"atrim=start={src_in:.3f}:duration={dur:.3f},asetpts=PTS-STARTPTS"
            )
            if trans_type == "FADE":
                fade_sec = trans_dur_ms / 1000.0
                audio_chains.append(f"{base_audio},afade=t=in:d={fade_sec:.3f}[a{i}]")
            else:
                audio_chains.append(f"{base_audio}[a{i}]")
        else:
            audio_chains.append(
                f"anullsrc=r=48000:cl=stereo:d={dur:.3f}[a{i}]"
            )

    # ── Step 4: Audio transition chain ──
    acc_a_label = "[a0]"
    acc_a_duration = clip_durations_sec[0]
    audio_xfade_counter = 0

    for i in range(1, n):
        trans = clips[i].get("transitionIn", {})
        trans_type = trans.get("type", "CUT")
        trans_dur_ms = trans.get("durationMs", 0)
        trans_dur_sec = trans_dur_ms / 1000.0

        if trans_type in ("CUT", "FADE"):
            new_label = f"[ac{audio_xfade_counter}]"
            transition_filters.append(
                f"{acc_a_label}[a{i}]concat=n=2:v=0:a=1{new_label}"
            )
            acc_a_label = new_label
            acc_a_duration += clip_durations_sec[i]
            audio_xfade_counter += 1
        elif trans_type == "CROSS_DISSOLVE":
            new_label = f"[af{audio_xfade_counter}]"
            transition_filters.append(
                f"{acc_a_label}[a{i}]acrossfade="
                f"d={trans_dur_sec:.3f}:c1=tri:c2=tri{new_label}"
            )
            acc_a_label = new_label
            acc_a_duration = acc_a_duration + clip_durations_sec[i] - trans_dur_sec
            audio_xfade_counter += 1

    final_audio_label = acc_a_label

    # ── Step 5: BGM mixing (optional) ──
    if bgm_path is not None and bgm_path.is_file():
        inputs.append(str(bgm_path))
        total_dur = acc_a_duration

        # Check BGM track from timeline metadata if available
        bgm_volume = 0.3
        for track in _find_extra_tracks(clips):
            pass  # BGM settings are on the command args, not clips

        transition_filters.append(
            f"[{bgm_input_index}:a]atrim=0:duration={total_dur:.3f},"
            f"volume={bgm_volume}[bgm]"
        )
        transition_filters.append(
            f"[{final_audio_label}][bgm]amix=inputs=2:duration=first:"
            f"dropout_transition=0[outa_mixed]"
        )
        final_audio_label = "[outa_mixed]"

    # ── Step 6: Subtitle burning (optional) ──
    if srt_path is not None and srt_path.is_file():
        # Escape backslashes and colons for FFmpeg subtitles filter on Windows
        srt_escaped = str(srt_path).replace("\\", "/").replace(":", "\\:")
        transition_filters.append(
            f"{final_video_label}subtitles='{srt_escaped}':"
            f"force_style='FontSize=24,PrimaryColour=&H00FFFFFF,"
            f"OutlineColour=&H00000000,Outline=1,Shadow=1,MarginV=50'[outv_sub]"
        )
        final_video_label = "[outv_sub]"

    # ── Assemble ──
    all_filters = clip_chains + audio_chains + transition_filters
    filter_complex = "; ".join(all_filters)

    return inputs, filter_complex, final_video_label, final_audio_label


def _find_extra_tracks(clips: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Stub for extracting non-VIDEO track metadata from timeline. (Used for BGM volume lookup.)"""
    return []


# ── Command assembly ────────────────────────────────────────────────────────

def _assemble_command(
    input_paths: list[str],
    filter_complex: str,
    final_video_label: str,
    final_audio_label: str,
) -> list[str]:
    cmd = [
        settings.ffmpeg_path, "-hide_banner", "-loglevel", "error", "-y",
        "-filter_threads", "1", "-filter_complex_threads", "1",
        "-progress", "pipe:1", "-nostats",
    ]
    for p in input_paths:
        cmd.extend(["-i", p])
    cmd.extend(["-filter_complex", filter_complex])
    cmd.extend([
        "-map", final_video_label, "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
        "-threads", "2",
        "-pix_fmt", "yuv420p",
    ])
    cmd.extend(["-map", final_audio_label, "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "2"])
    cmd.extend(["-movflags", "+faststart"])
    return cmd


_DETERMINISTIC_FFMPEG_ERRORS = (
    "error parsing",
    "invalid argument",
    "no option name",
    "no such filter",
    "unable to parse option value",
    "matches no streams",
)


def _ffmpeg_render_error(stderr: str, return_code: int = 1) -> ToolExecutionError:
    diagnostic = stderr.strip()
    if len(diagnostic) > 8000:
        diagnostic = diagnostic[-8000:]
    if diagnostic:
        message = f"ffmpeg exited with code {return_code}: {diagnostic}"
    elif return_code < 0:
        message = f"ffmpeg was terminated by signal {-return_code}; possible memory or container limit"
    else:
        message = f"ffmpeg exited with code {return_code} without diagnostic output"
    normalized = message.lower()
    retryable = not any(marker in normalized for marker in _DETERMINISTIC_FFMPEG_ERRORS)
    return ToolExecutionError(message, retryable=retryable)


# ── Progress / probe helpers ────────────────────────────────────────────────

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
