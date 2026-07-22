"""Unit tests for video.render tool — filter graph construction and contract."""

from __future__ import annotations

from pathlib import Path
from unittest.mock import patch

from app.tools.video_render import (
    VideoRenderTool,
    _build_filter_graph,
    _resolve_proxy_paths,
)


def test_manifest():
    tool = VideoRenderTool()
    m = tool.manifest()
    assert m["name"] == "video.render"
    assert m["version"] == "1.0.0"
    assert "TIMELINE" in m["inputTypes"]
    assert "RENDERED_VIDEO" in m["outputTypes"]
    assert m["cacheable"] is False


def test_build_filter_graph_single_clip():
    clips = [{
        "sourceProxyArtifactId": "art_abc",
        "sourceInMs": 0,
        "sourceOutMs": 3000,
        "timelineInMs": 0,
        "timelineOutMs": 3000,
    }]
    canvas = {"width": 1280, "height": 720, "fps": 30}
    proxy_map = {"art_abc": Path("/tmp/art_abc/video-proxy.mp4")}
    audio_map = {"art_abc": True}

    inputs, fc = _build_filter_graph(clips, canvas, proxy_map, audio_map)

    assert len(inputs) == 1
    assert "[0:v]" in fc
    assert "trim=start=0.000:duration=3.000" in fc
    assert "scale=1280:720" in fc
    assert "concat=n=1:v=1:a=0[outv]" in fc
    assert "concat=n=1:v=0:a=1[outa]" in fc
    assert "[0:a]atrim=start=0.000:duration=3.000" in fc


def test_build_filter_graph_multi_clip_two_sources():
    clips = [
        {"sourceProxyArtifactId": "art_abc", "sourceInMs": 0, "sourceOutMs": 2000,
         "timelineInMs": 0, "timelineOutMs": 2000},
        {"sourceProxyArtifactId": "art_def", "sourceInMs": 500, "sourceOutMs": 3500,
         "timelineInMs": 2000, "timelineOutMs": 5000},
        {"sourceProxyArtifactId": "art_abc", "sourceInMs": 4000, "sourceOutMs": 6000,
         "timelineInMs": 5000, "timelineOutMs": 7000},
    ]
    canvas = {"width": 1920, "height": 1080, "fps": 30}
    proxy_map = {
        "art_abc": Path("/tmp/art_abc/video-proxy.mp4"),
        "art_def": Path("/tmp/art_def/video-proxy.mp4"),
    }
    audio_map = {"art_abc": True, "art_def": True}

    inputs, fc = _build_filter_graph(clips, canvas, proxy_map, audio_map)

    assert len(inputs) == 2
    assert "concat=n=3:v=1:a=0[outv]" in fc
    assert "concat=n=3:v=0:a=1[outa]" in fc
    assert "[0:v]" in fc
    assert "[1:v]" in fc
    assert "[v0][v1][v2]" in fc
    assert "scale=1920:1080" in fc


def test_build_filter_graph_no_audio():
    clips = [{
        "sourceProxyArtifactId": "art_abc",
        "sourceInMs": 1000, "sourceOutMs": 4000,
        "timelineInMs": 0, "timelineOutMs": 3000,
    }]
    canvas = {"width": 1280, "height": 720, "fps": 30}
    proxy_map = {"art_abc": Path("/tmp/art_abc/video-proxy.mp4")}
    audio_map = {"art_abc": False}

    _inputs, fc = _build_filter_graph(clips, canvas, proxy_map, audio_map)

    assert "anullsrc=r=48000:cl=stereo:d=3.000[a0]" in fc
    assert "atrim" not in fc


def test_build_filter_graph_mixed_audio():
    clips = [
        {"sourceProxyArtifactId": "art_with", "sourceInMs": 0, "sourceOutMs": 2000,
         "timelineInMs": 0, "timelineOutMs": 2000},
        {"sourceProxyArtifactId": "art_without", "sourceInMs": 0, "sourceOutMs": 2000,
         "timelineInMs": 2000, "timelineOutMs": 4000},
    ]
    canvas = {"width": 1280, "height": 720, "fps": 30}
    proxy_map = {
        "art_with": Path("/tmp/art_with/video-proxy.mp4"),
        "art_without": Path("/tmp/art_without/video-proxy.mp4"),
    }
    audio_map = {"art_with": True, "art_without": False}

    _inputs, fc = _build_filter_graph(clips, canvas, proxy_map, audio_map)

    assert "[0:a]atrim" in fc
    assert "anullsrc" in fc
    assert "concat=n=2:v=1:a=0[outv]" in fc


def test_resolve_proxy_paths_finds_and_misses(monkeypatch, tmp_path):
    proxy_dir = tmp_path / "art_abc"
    proxy_dir.mkdir()
    (proxy_dir / "video-proxy.mp4").write_text("fake")

    import app.tools.video_render as vr
    monkeypatch.setattr(vr.settings, "artifact_root", tmp_path)

    timeline = {"tracks": [{"clips": [
        {"sourceProxyArtifactId": "art_abc", "sourceInMs": 0, "sourceOutMs": 1000,
         "timelineInMs": 0, "timelineOutMs": 1000},
        {"sourceProxyArtifactId": "art_missing", "sourceInMs": 0, "sourceOutMs": 1000,
         "timelineInMs": 1000, "timelineOutMs": 2000},
    ]}]}

    proxy_map, missing = _resolve_proxy_paths(timeline)

    assert "art_abc" in proxy_map
    assert "art_missing" in missing


def test_rejects_invalid_timeline():
    tool = VideoRenderTool()
    from app.core.models import ToolExecutionRequest

    req = ToolExecutionRequest(
        tool="video.render", version="1.0.0",
        idempotencyKey="test-1", inputs={}, parameters={},
    )
    try:
        tool.execute(req)
        assert False, "Should have raised ValueError"
    except ValueError as exc:
        assert "TIMELINE" in str(exc)
