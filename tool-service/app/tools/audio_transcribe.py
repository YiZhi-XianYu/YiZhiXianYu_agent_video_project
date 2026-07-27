"""ASR speech-to-text tool — transcribes video proxy audio to SRT subtitles using faster-whisper."""

from __future__ import annotations

import hashlib
import logging
import subprocess
import tempfile
from collections.abc import Callable
from pathlib import Path
from typing import Any
from uuid import uuid4

from app.core.config import settings
from app.core.models import ArtifactDescriptor, ToolExecutionRequest
from app.tools.artifact_json import matching_inputs, read_json_artifact

logger = logging.getLogger(__name__)

# Lazy-loaded WhisperModel singleton
_whisper_model: Any = None
_loaded_model_size: str | None = None


class SpeechTranscribeTool:
    name = "audio.speech-transcribe"
    version = "1.0.0"

    def manifest(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "version": self.version,
            "description": "Transcribe source video audio to timed SRT subtitles using Whisper ASR",
            "executionMode": "ASYNC",
            "resourceClass": "CPU_MEDIUM",
            "resourceGroup": "MODEL",
            "timeoutSeconds": 600,
            "supportsCancellation": False,
            "deterministic": True,
            "cacheable": True,
            "inputTypes": ["TIMELINE", "VIDEO_PROXY"],
            "outputTypes": ["SUBTITLE_SRT"],
        }

    def execute(
        self,
        request: ToolExecutionRequest,
        report_progress: Callable[[int], None] | None = None,
    ) -> list[ArtifactDescriptor]:
        # Read timeline to get clip metadata (for time offset calculation)
        timeline_inputs = matching_inputs(request.inputs, "timeline")
        proxy_inputs = matching_inputs(request.inputs, "proxy")

        clips_meta: list[dict[str, Any]] = []
        if timeline_inputs:
            timeline = read_json_artifact(timeline_inputs[0])
            for track in timeline.get("tracks", []):
                if track.get("type") == "VIDEO":
                    clips_meta = track.get("clips", [])
                    break

        if not proxy_inputs:
            logger.warning("No proxy video inputs for ASR — producing empty subtitles")
            return [_write_empty_srt()]

        # Transcribe each proxy video that has audio, merging with timeline offsets
        all_segments: list[dict[str, Any]] = []
        segment_index = 0
        proxy_paths = _resolve_proxy_paths_for_asr(proxy_inputs)
        proxy_count = max(len(proxy_paths), 1)

        for i, (artifact_id, proxy_path) in enumerate(proxy_paths):
            if not proxy_path.is_file():
                logger.warning("Proxy file not found for ASR: %s", proxy_path)
                continue

            if not _has_audio(proxy_path):
                logger.info("Proxy %s has no audio stream, skipping", artifact_id)
                continue

            if report_progress is not None:
                report_progress(15 + int(i / proxy_count * 70))

            # Find timeline offset for this source
            offset_ms = _find_timeline_offset(clips_meta, artifact_id)

            # Extract audio to WAV
            try:
                segments = _transcribe(
                    proxy_path,
                    offset_ms,
                    segment_index,
                    None if report_progress is None else (
                        lambda fraction, index=i: report_progress(
                            15 + int((index + fraction) / proxy_count * 70)
                        )
                    ),
                )
                all_segments.extend(segments)
                segment_index += len(segments)
            except Exception as exc:
                logger.warning("ASR transcription failed for %s: %s", artifact_id, exc)
                continue

        if report_progress is not None:
            report_progress(90)

        if not all_segments:
            return [_write_empty_srt()]

        srt_content = _format_srt(all_segments)
        return [_write_srt_artifact(srt_content, len(all_segments))]


def _resolve_proxy_paths_for_asr(proxy_inputs: list[Any]) -> list[tuple[str, Path]]:
    """Resolve proxy artifact URIs to local file paths."""
    from urllib.parse import unquote, urlparse
    result = []
    for inp in proxy_inputs:
        parsed = urlparse(inp.uri)
        path_str = unquote(parsed.path)
        if len(path_str) >= 3 and path_str[0] == "/" and path_str[2] == ":":
            path_str = path_str[1:]
        result.append((inp.artifact_id, Path(path_str)))
    return result


def _find_timeline_offset(clips_meta: list[dict[str, Any]], artifact_id: str) -> int:
    """Find the timeline position of the first clip from a given proxy artifact."""
    for clip in clips_meta:
        if clip.get("sourceProxyArtifactId") == artifact_id:
            return clip.get("timelineInMs", 0)
    return 0


def _has_audio(proxy_path: Path) -> bool:
    """Check if a video file has an audio stream."""
    command = [
        settings.ffprobe_path, "-v", "error",
        "-select_streams", "a",
        "-show_entries", "stream=codec_type",
        "-of", "default=noprint_wrappers=1:nokey=1",
        str(proxy_path),
    ]
    process = subprocess.run(command, capture_output=True, text=True, encoding="utf-8")
    return process.returncode == 0 and process.stdout.strip() == "audio"


def _transcribe(
    proxy_path: Path,
    offset_ms: int,
    start_index: int,
    report_progress: Callable[[float], None] | None = None,
) -> list[dict[str, Any]]:
    """Transcribe a single proxy video, returning SRT-ready segments."""
    model = _get_whisper_model()

    # Extract audio to temporary WAV (16kHz mono for Whisper)
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        wav_path = Path(tmp.name)

    try:
        extract_cmd = [
            settings.ffmpeg_path, "-y", "-v", "error",
            "-i", str(proxy_path),
            "-vn", "-acodec", "pcm_s16le", "-ar", "16000", "-ac", "1",
            str(wav_path),
        ]
        subprocess.run(extract_cmd, capture_output=True, text=True, encoding="utf-8", check=True)

        segments_raw, info = model.transcribe(
            str(wav_path),
            language=None,  # auto-detect
            vad_filter=True,
            vad_parameters={"min_silence_duration_ms": 500},
        )

        segments = []
        duration_seconds = max(float(getattr(info, "duration", 0.0) or 0.0), 0.001)
        for seg in segments_raw:
            start_ms = int(seg.start * 1000) + offset_ms
            end_ms = int(seg.end * 1000) + offset_ms
            text = seg.text.strip()
            if text:
                segments.append({
                    "index": start_index + len(segments) + 1,
                    "startMs": start_ms,
                    "endMs": end_ms,
                    "text": text,
                })
            if report_progress is not None:
                report_progress(min(1.0, max(0.0, float(seg.end) / duration_seconds)))

        if report_progress is not None:
            report_progress(1.0)

        return segments
    finally:
        try:
            wav_path.unlink()
        except OSError:
            pass


def _get_whisper_model() -> Any:
    """Lazy-load and cache the Whisper model."""
    global _whisper_model, _loaded_model_size
    model_size = getattr(settings, "asr_model_size", "small") or "small"
    local_path = getattr(settings, "asr_model_path", None)

    if _whisper_model is not None and _loaded_model_size == model_size:
        return _whisper_model

    # Use local model path if provided (no download)
    if local_path:
        model_path = Path(local_path)
        if model_path.is_dir():
            model_size = str(model_path)

    try:
        from faster_whisper import WhisperModel
        # Use int8 quantization on CPU for lower memory
        _whisper_model = WhisperModel(
            model_size,
            device="cpu",
            compute_type="int8",
        )
        _loaded_model_size = model_size
        logger.info("Whisper model loaded: size=%s, device=cpu", model_size)
        return _whisper_model
    except ImportError:
        raise RuntimeError(
            "faster-whisper is not installed. Run: pip install faster-whisper"
        )
    except Exception as exc:
        raise RuntimeError(f"Failed to load Whisper model '{model_size}': {exc}")


def release_whisper_model() -> bool:
    global _whisper_model, _loaded_model_size
    released = _whisper_model is not None
    _whisper_model = None
    _loaded_model_size = None
    return released


def _format_srt(segments: list[dict[str, Any]]) -> str:
    """Format segments as SRT subtitle text."""
    lines = []
    for i, seg in enumerate(segments):
        start = _ms_to_srt_time(seg["startMs"])
        end = _ms_to_srt_time(seg["endMs"])
        lines.append(f"{i + 1}")
        lines.append(f"{start} --> {end}")
        lines.append(seg["text"])
        lines.append("")
    return "\n".join(lines)


def _ms_to_srt_time(ms: int) -> str:
    """Convert milliseconds to SRT timestamp format: HH:MM:SS,mmm"""
    h = ms // 3600000
    m = (ms % 3600000) // 60000
    s = (ms % 60000) // 1000
    ms_remainder = ms % 1000
    return f"{h:02d}:{m:02d}:{s:02d},{ms_remainder:03d}"


def _write_srt_artifact(srt_text: str, segment_count: int) -> ArtifactDescriptor:
    artifact_id = f"art_{uuid4().hex}"
    output_dir = settings.artifact_root / artifact_id
    output_dir.mkdir(parents=True, exist_ok=False)
    output_path = output_dir / "subtitles.srt"
    output_path.write_text(srt_text, encoding="utf-8")

    content = srt_text.encode("utf-8")
    return ArtifactDescriptor(
        artifactId=artifact_id,
        type="SUBTITLE_SRT",
        uri=output_path.resolve().as_uri(),
        mediaType="text/plain",
        size=len(content),
        contentHash=hashlib.sha256(content).hexdigest(),
        metadata={"format": "SRT", "segmentCount": segment_count},
    )


def _write_empty_srt() -> ArtifactDescriptor:
    return _write_srt_artifact("", 0)
