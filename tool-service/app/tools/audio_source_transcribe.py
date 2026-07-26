"""Transcribe proxy audio into reusable source-time transcript segments."""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

from app.core.models import ArtifactDescriptor, ToolExecutionRequest
from app.tools.artifact_json import matching_inputs, write_json_artifact
from app.tools.audio_transcribe import _has_audio, _resolve_proxy_paths_for_asr, _transcribe


class SourceTranscribeTool:
    name = "audio.source-transcribe"
    version = "1.0.0"

    def manifest(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "version": self.version,
            "description": "Transcribe each proxy audio stream into immutable source-time segments",
            "executionMode": "ASYNC",
            "resourceClass": "CPU_MEDIUM",
            "timeoutSeconds": 600,
            "supportsCancellation": False,
            "deterministic": True,
            "cacheable": True,
            "inputTypes": ["VIDEO_PROXY"],
            "outputTypes": ["SOURCE_TRANSCRIPT"],
        }

    def execute(
        self,
        request: ToolExecutionRequest,
        report_progress: Callable[[int], None] | None = None,
    ) -> list[ArtifactDescriptor]:
        proxy_inputs = matching_inputs(request.inputs, "video")
        if not proxy_inputs:
            raise ValueError("audio.source-transcribe requires VIDEO_PROXY input")

        sources: list[dict[str, Any]] = []
        proxy_paths = _resolve_proxy_paths_for_asr(proxy_inputs)
        for index, (artifact_id, proxy_path) in enumerate(proxy_paths):
            if not proxy_path.is_file() or not _has_audio(proxy_path):
                continue
            segments = _transcribe(proxy_path, 0, 0)
            if segments:
                sources.append({
                    "sourceProxyArtifactId": artifact_id,
                    "segments": [
                        {"startMs": item["startMs"], "endMs": item["endMs"], "text": item["text"]}
                        for item in segments
                    ],
                })
            if report_progress is not None:
                report_progress(int((index + 1) / len(proxy_paths) * 90))

        if not sources:
            return []
        payload = {"schemaVersion": "1.0", "sources": sources}
        return [write_json_artifact("SOURCE_TRANSCRIPT", "source-transcript.json", payload, {
            "sourceCount": len(sources),
            "segmentCount": sum(len(item["segments"]) for item in sources),
        })]
