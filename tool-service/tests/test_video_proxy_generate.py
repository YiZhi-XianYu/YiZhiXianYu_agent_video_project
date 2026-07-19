from pathlib import Path

from app.tools.video_proxy_generate import VideoProxyGenerateTool


def test_builds_browser_compatible_1080p_command() -> None:
    command = VideoProxyGenerateTool.build_command(Path("input.mp4"), Path("proxy.mp4"), "1080P")

    assert command[0]
    assert "libx264" in command
    assert "yuv420p" in command
    assert "+faststart" in command
    assert "0:a?" in command
    assert "pipe:1" in command
    assert "22" in command
    assert any("min(1920" in argument for argument in command)
    assert any("min(1080" in argument for argument in command)
    assert any("force_divisible_by=2" in argument for argument in command)
    assert any("fps=30" in argument for argument in command)


def test_builds_each_supported_quality_profile() -> None:
    expected = {
        "4K": (3840, 2160, "20"),
        "2K": (2560, 1440, "21"),
        "1080P": (1920, 1080, "22"),
        "720P": (1280, 720, "23"),
    }

    for quality, (width, height, crf) in expected.items():
        command = VideoProxyGenerateTool.build_command(Path("input.mp4"), Path("proxy.mp4"), quality)
        scale = next(argument for argument in command if argument.startswith("scale="))
        assert f"min({width}" in scale
        assert f"min({height}" in scale
        assert command[command.index("-crf") + 1] == crf


def test_rejects_an_unknown_quality_profile() -> None:
    try:
        VideoProxyGenerateTool.quality_profile("8K")
    except ValueError as exc:
        assert "Unsupported proxy quality" in str(exc)
    else:
        raise AssertionError("Unknown quality should be rejected")


def test_maps_ffmpeg_time_to_bounded_progress() -> None:
    assert VideoProxyGenerateTool._transcode_progress("00:00:12.000000", 24.0) == 50
    assert VideoProxyGenerateTool._transcode_progress("00:00:30.000000", 24.0) == 90
