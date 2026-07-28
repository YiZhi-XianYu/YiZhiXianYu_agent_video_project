import json
from pathlib import Path

from app.core.config import settings
from app.core.models import ArtifactInput, ToolExecutionRequest
from app.music.providers import MusicCandidate
from app.tools import audio_bgm


class FakeMusicProvider:
    name = "fake"

    def __init__(self, root: Path) -> None:
        self.root = root

    def search(self, mood: str, target_duration_ms: int, limit: int) -> list[MusicCandidate]:
        return [
            MusicCandidate(
                provider=self.name,
                track_id=f"track-{index}",
                title=f"Track {index}",
                artist="Test Artist",
                duration_ms=target_duration_ms + index * 1000,
                mood=mood,
                source_url=f"https://example.test/tracks/{index}",
                audio_url=f"https://example.test/audio/{index}.mp3",
                score=100 - index,
            )
            for index in range(1, limit + 1)
        ]

    def cache(self, candidate: MusicCandidate) -> Path:
        path = self.root / f"{candidate.track_id}.mp3"
        path.write_bytes(f"audio:{candidate.track_id}".encode())
        return path


def write_json_input(tmp_path: Path, name: str, payload: dict) -> ArtifactInput:
    path = tmp_path / name
    path.write_text(json.dumps(payload), encoding="utf-8")
    return ArtifactInput(artifactId=name, uri=path.resolve().as_uri(), fileName=name)


def request(tmp_path: Path, auto_select: bool) -> ToolExecutionRequest:
    return ToolExecutionRequest(
        tool="audio.bgm-select",
        version="1.0.0",
        idempotencyKey=f"bgm:{auto_select}",
        inputs={
            "story": write_json_input(tmp_path, "story.json", {
                "beats": [{"role": "CLIMAX"}],
            }),
            "timeline": write_json_input(tmp_path, "timeline.json", {
                "durationMs": 10_000,
            }),
        },
        parameters={"autoSelect": auto_select},
    )


def test_manual_mode_returns_ranked_candidates_without_selecting(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(settings, "artifact_root", tmp_path / "artifacts")
    monkeypatch.setattr(settings, "music_candidate_limit", 3)
    monkeypatch.setattr(audio_bgm, "configured_music_providers", lambda: [FakeMusicProvider(tmp_path)])

    outputs = audio_bgm.BgmSelectTool().execute(request(tmp_path, auto_select=False))

    assert [output.type for output in outputs] == ["BGM_CANDIDATE"] * 3
    assert [output.metadata["rank"] for output in outputs] == [1, 2, 3]
    assert len({output.metadata["candidateSetId"] for output in outputs}) == 1
    assert all(output.metadata["selected"] is False for output in outputs)


def test_auto_mode_materializes_top_candidate_as_bgm_audio(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(settings, "artifact_root", tmp_path / "artifacts")
    monkeypatch.setattr(settings, "music_candidate_limit", 3)
    monkeypatch.setattr(audio_bgm, "configured_music_providers", lambda: [FakeMusicProvider(tmp_path)])

    outputs = audio_bgm.BgmSelectTool().execute(request(tmp_path, auto_select=True))

    assert [output.type for output in outputs] == [
        "BGM_AUDIO", "BGM_CANDIDATE", "BGM_CANDIDATE",
    ]
    assert outputs[0].metadata["selected"] is True
    assert outputs[0].metadata["rank"] == 1


def test_provider_unavailable_safely_returns_no_bgm(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(settings, "artifact_root", tmp_path / "artifacts")
    monkeypatch.setattr(audio_bgm, "configured_music_providers", lambda: [])

    assert audio_bgm.BgmSelectTool().execute(request(tmp_path, auto_select=False)) == []
