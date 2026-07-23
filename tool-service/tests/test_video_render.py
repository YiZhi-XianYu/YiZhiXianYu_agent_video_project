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
    assert m["version"] == "1.1.0"
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
        "transitionIn": {"type": "CUT", "durationMs": 0},
    }]
    canvas = {"width": 1280, "height": 720, "fps": 30}
    proxy_map = {"art_abc": Path("/tmp/art_abc/video-proxy.mp4")}
    audio_map = {"art_abc": True}

    inputs, fc, v_label, a_label = _build_filter_graph(clips, canvas, proxy_map, audio_map)

    assert len(inputs) == 1
    assert "[0:v]" in fc
    assert "trim=start=0.000:duration=3.000" in fc
    assert "scale=1280:720" in fc
    assert "[s0]" in fc
    assert "[0:a]atrim=start=0.000:duration=3.000" in fc
    assert v_label == "[s0]"
    assert a_label == "[a0]"


def test_build_filter_graph_multi_clip_cut():
    clips = [
        {"sourceProxyArtifactId": "art_abc", "sourceInMs": 0, "sourceOutMs": 2000,
         "timelineInMs": 0, "timelineOutMs": 2000,
         "transitionIn": {"type": "CUT", "durationMs": 0}},
        {"sourceProxyArtifactId": "art_def", "sourceInMs": 500, "sourceOutMs": 3500,
         "timelineInMs": 2000, "timelineOutMs": 5000,
         "transitionIn": {"type": "CUT", "durationMs": 0}},
        {"sourceProxyArtifactId": "art_abc", "sourceInMs": 4000, "sourceOutMs": 6000,
         "timelineInMs": 5000, "timelineOutMs": 7000,
         "transitionIn": {"type": "CUT", "durationMs": 0}},
    ]
    canvas = {"width": 1920, "height": 1080, "fps": 30}
    proxy_map = {
        "art_abc": Path("/tmp/art_abc/video-proxy.mp4"),
        "art_def": Path("/tmp/art_def/video-proxy.mp4"),
    }
    audio_map = {"art_abc": True, "art_def": True}

    inputs, fc, v_label, a_label = _build_filter_graph(clips, canvas, proxy_map, audio_map)

    assert len(inputs) == 2
    assert "concat=n=2:v=1:a=0" in fc
    assert "[0:v]" in fc
    assert "[1:v]" in fc
    assert "[s0]" in fc
    assert "[s1]" in fc
    assert "[s2]" in fc
    assert "scale=1920:1080" in fc


def test_build_filter_graph_no_audio():
    clips = [{
        "sourceProxyArtifactId": "art_abc",
        "sourceInMs": 1000, "sourceOutMs": 4000,
        "timelineInMs": 0, "timelineOutMs": 3000,
        "transitionIn": {"type": "CUT", "durationMs": 0},
    }]
    canvas = {"width": 1280, "height": 720, "fps": 30}
    proxy_map = {"art_abc": Path("/tmp/art_abc/video-proxy.mp4")}
    audio_map = {"art_abc": False}

    _inputs, fc, _v, _a = _build_filter_graph(clips, canvas, proxy_map, audio_map)

    assert "anullsrc=r=48000:cl=stereo:d=3.000[a0]" in fc
    assert "atrim" not in fc


def test_build_filter_graph_mixed_audio():
    clips = [
        {"sourceProxyArtifactId": "art_with", "sourceInMs": 0, "sourceOutMs": 2000,
         "timelineInMs": 0, "timelineOutMs": 2000,
         "transitionIn": {"type": "CUT", "durationMs": 0}},
        {"sourceProxyArtifactId": "art_without", "sourceInMs": 0, "sourceOutMs": 2000,
         "timelineInMs": 2000, "timelineOutMs": 4000,
         "transitionIn": {"type": "CUT", "durationMs": 0}},
    ]
    canvas = {"width": 1280, "height": 720, "fps": 30}
    proxy_map = {
        "art_with": Path("/tmp/art_with/video-proxy.mp4"),
        "art_without": Path("/tmp/art_without/video-proxy.mp4"),
    }
    audio_map = {"art_with": True, "art_without": False}

    _inputs, fc, _v, _a = _build_filter_graph(clips, canvas, proxy_map, audio_map)

    assert "[0:a]atrim" in fc
    assert "anullsrc" in fc


def test_build_filter_graph_with_fade():
    clips = [
        {"sourceProxyArtifactId": "art_a", "sourceInMs": 0, "sourceOutMs": 3000,
         "timelineInMs": 0, "timelineOutMs": 3000,
         "transitionIn": {"type": "CUT", "durationMs": 0}},
        {"sourceProxyArtifactId": "art_a", "sourceInMs": 5000, "sourceOutMs": 8000,
         "timelineInMs": 3000, "timelineOutMs": 6000,
         "transitionIn": {"type": "FADE", "durationMs": 300}},
    ]
    canvas = {"width": 1920, "height": 1080, "fps": 30}
    proxy_map = {"art_a": Path("/tmp/art_a/video-proxy.mp4")}
    audio_map = {"art_a": True}

    _inputs, fc, _v, _a = _build_filter_graph(clips, canvas, proxy_map, audio_map)

    # First clip: no fade
    assert "[s0]" in fc
    # Second clip: fade filter in video chain
    assert "fade=t=in:d=0.300[s1]" in fc
    # Audio fade for second clip
    assert "afade=t=in:d=0.300[a1]" in fc
    # Connection is still concat (FADE doesn't use xfade)
    assert "concat=n=2:v=1:a=0" in fc


def test_build_filter_graph_with_cross_dissolve():
    clips = [
        {"sourceProxyArtifactId": "art_a", "sourceInMs": 0, "sourceOutMs": 3000,
         "timelineInMs": 0, "timelineOutMs": 3000,
         "transitionIn": {"type": "CUT", "durationMs": 0}},
        {"sourceProxyArtifactId": "art_a", "sourceInMs": 5000, "sourceOutMs": 8000,
         "timelineInMs": 3000, "timelineOutMs": 6000,
         "transitionIn": {"type": "CROSS_DISSOLVE", "durationMs": 500}},
    ]
    canvas = {"width": 1920, "height": 1080, "fps": 30}
    proxy_map = {"art_a": Path("/tmp/art_a/video-proxy.mp4")}
    audio_map = {"art_a": True}

    _inputs, fc, _v, _a = _build_filter_graph(clips, canvas, proxy_map, audio_map)

    # xfade for cross-dissolve
    assert "xfade=transition=fade:duration=0.500" in fc
    assert "offset=2.500" in fc  # 3.0 - 0.5 = 2.5
    # acrossfade for audio
    assert "acrossfade=d=0.500" in fc


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
