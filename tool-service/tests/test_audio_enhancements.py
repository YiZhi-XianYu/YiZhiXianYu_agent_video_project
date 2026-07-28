from __future__ import annotations

import json

from app.core.config import settings
from app.core.models import ArtifactInput, ToolExecutionRequest
from app.tools.audio_bgm import BgmSelectTool
from app.music.providers import LocalMusicProvider
from app.tools.subtitle_compose import SubtitleComposeTool


def _input(tmp_path, name: str, payload: dict) -> ArtifactInput:
    path = tmp_path / name
    path.write_text(json.dumps(payload), encoding="utf-8")
    return ArtifactInput(artifactId=name, uri=path.resolve().as_uri(), fileName=name)


def _request(tool: str, inputs: dict[str, ArtifactInput]) -> ToolExecutionRequest:
    return ToolExecutionRequest(
        tool=tool,
        version="1.0.0",
        idempotencyKey=f"{tool}:test",
        inputs=inputs,
        parameters={},
    )


def test_bgm_unavailable_returns_no_artifact(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(settings, "bgm_library_root", tmp_path / "missing")
    monkeypatch.setattr("app.tools.audio_bgm.configured_music_providers", lambda: [])
    story = _input(tmp_path, "story.json", {"beats": [{"role": "CLIMAX"}]})

    outputs = BgmSelectTool().execute(_request("audio.bgm-select", {"story": story}))

    assert outputs == []


def test_bgm_artifact_points_to_immutable_mp3(tmp_path, monkeypatch) -> None:
    library = tmp_path / "bgm"
    library.mkdir()
    source = library / "epic_reveal.mp3"
    source.write_bytes(b"fake-mp3")
    monkeypatch.setattr(settings, "bgm_library_root", library)
    monkeypatch.setattr(settings, "artifact_root", tmp_path / "artifacts")
    monkeypatch.setattr(
        "app.tools.audio_bgm.configured_music_providers",
        lambda: [LocalMusicProvider(library)],
    )
    monkeypatch.setattr("app.tools.audio_bgm._probe_duration", lambda _path: 1000)
    story = _input(tmp_path, "story.json", {"beats": [{"role": "CLIMAX"}]})

    output = BgmSelectTool().execute(_request("audio.bgm-select", {"story": story}))[0]

    assert output.media_type == "audio/mpeg"
    assert output.uri.endswith("bgm-audio.mp3")
    assert (tmp_path / "artifacts" / output.artifact_id / "bgm-audio.mp3").read_bytes() == b"fake-mp3"


def test_subtitle_compose_clips_and_offsets_segments(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(settings, "artifact_root", tmp_path / "artifacts")
    timeline = _input(tmp_path, "timeline.json", {
        "tracks": [{"type": "VIDEO", "clips": [{
            "sourceProxyArtifactId": "proxy-a",
            "sourceInMs": 1000,
            "sourceOutMs": 3000,
            "timelineInMs": 5000,
        }]}],
    })
    transcript = _input(tmp_path, "transcript.json", {
        "sources": [{"sourceProxyArtifactId": "proxy-a", "segments": [
            {"startMs": 500, "endMs": 1500, "text": "before"},
            {"startMs": 2000, "endMs": 3500, "text": "after"},
        ]}],
    })

    output = SubtitleComposeTool().execute(_request("subtitle.compose", {
        "timeline": timeline,
        "transcript": transcript,
    }))[0]
    srt = (tmp_path / "artifacts" / output.artifact_id / "subtitles.srt").read_text(encoding="utf-8")

    assert "00:00:05,000 --> 00:00:05,500" in srt
    assert "00:00:06,000 --> 00:00:07,000" in srt


def test_subtitle_compose_without_transcript_degrades_to_no_output(tmp_path) -> None:
    timeline = _input(tmp_path, "timeline.json", {"tracks": [{"type": "VIDEO", "clips": []}]})
    outputs = SubtitleComposeTool().execute(_request("subtitle.compose", {"timeline": timeline}))
    assert outputs == []
