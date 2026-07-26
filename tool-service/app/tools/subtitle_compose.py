"""Compile source-time transcript segments into final Timeline SRT subtitles."""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

from app.core.models import ArtifactDescriptor, ToolExecutionRequest
from app.tools.artifact_json import matching_inputs, read_json_artifact
from app.tools.audio_transcribe import _format_srt, _write_srt_artifact


class SubtitleComposeTool:
    name = "subtitle.compose"
    version = "1.0.0"

    def manifest(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "version": self.version,
            "description": "Map source transcript segments onto a validated final Timeline and emit SRT",
            "executionMode": "ASYNC",
            "resourceClass": "CPU_LIGHT",
            "timeoutSeconds": 120,
            "supportsCancellation": False,
            "deterministic": True,
            "cacheable": True,
            "inputTypes": ["TIMELINE", "SOURCE_TRANSCRIPT"],
            "outputTypes": ["SUBTITLE_SRT"],
        }

    def execute(
        self,
        request: ToolExecutionRequest,
        report_progress: Callable[[int], None] | None = None,
    ) -> list[ArtifactDescriptor]:
        timeline_inputs = matching_inputs(request.inputs, "timeline")
        if len(timeline_inputs) != 1:
            raise ValueError("subtitle.compose requires one TIMELINE input")
        transcript_inputs = matching_inputs(request.inputs, "transcript")
        if not transcript_inputs:
            return []

        timeline = read_json_artifact(timeline_inputs[0])
        transcripts: dict[str, list[dict[str, Any]]] = {}
        for transcript_input in transcript_inputs:
            payload = read_json_artifact(transcript_input)
            for source in payload.get("sources", []):
                transcripts.setdefault(source.get("sourceProxyArtifactId", ""), []).extend(source.get("segments", []))

        video_track = next((track for track in timeline.get("tracks", []) if track.get("type") == "VIDEO"), None)
        if video_track is None:
            raise ValueError("subtitle.compose requires a VIDEO track")

        composed: list[dict[str, Any]] = []
        for clip in video_track.get("clips", []):
            source_in = int(clip["sourceInMs"])
            source_out = int(clip["sourceOutMs"])
            timeline_in = int(clip["timelineInMs"])
            for segment in transcripts.get(clip.get("sourceProxyArtifactId", ""), []):
                overlap_in = max(source_in, int(segment["startMs"]))
                overlap_out = min(source_out, int(segment["endMs"]))
                if overlap_out <= overlap_in or not str(segment.get("text", "")).strip():
                    continue
                composed.append({
                    "startMs": timeline_in + overlap_in - source_in,
                    "endMs": timeline_in + overlap_out - source_in,
                    "text": str(segment["text"]).strip(),
                })

        composed.sort(key=lambda item: (item["startMs"], item["endMs"], item["text"]))
        if not composed:
            return []
        if report_progress is not None:
            report_progress(100)
        return [_write_srt_artifact(_format_srt(composed), len(composed))]
