from app.tools.video_probe import VideoProbeTool


def test_normalizes_ffprobe_result() -> None:
    raw = {
        "streams": [
            {
                "codec_type": "video",
                "codec_name": "h264",
                "width": 1920,
                "height": 1080,
                "avg_frame_rate": "30000/1001",
            },
            {
                "codec_type": "audio",
                "codec_name": "aac",
                "sample_rate": "48000",
                "channels": 2,
            },
        ],
        "format": {
            "duration": "30.240",
            "format_name": "mov,mp4,m4a,3gp,3g2,mj2",
            "size": "1024",
            "bit_rate": "2048000",
        },
    }

    result = VideoProbeTool._normalize(raw)

    assert result["durationMs"] == 30240
    assert result["fps"] == 29.97
    assert result["videoCodec"] == "h264"
    assert result["audioCodec"] == "aac"
    assert result["hasAudio"] is True

