from app.tools.video_shot_detect import VideoShotDetectTool


def test_parses_unique_sorted_scene_times() -> None:
    stderr = "pts_time:3.200 pts_time:1.500 pts_time:3.200"

    assert VideoShotDetectTool.parse_cut_times(stderr) == [1500, 3200]


def test_builds_a_single_shot_when_no_cut_is_detected() -> None:
    shots = VideoShotDetectTool.build_shots(5000, [], 600)

    assert shots == [{
        "startMs": 0,
        "endMs": 5000,
        "durationMs": 5000,
        "keyframeMs": 2500,
        "boundaryConfidence": 1.0,
    }]


def test_filters_cuts_that_create_tiny_shots() -> None:
    shots = VideoShotDetectTool.build_shots(5000, [200, 1000, 4700], 600)

    assert [(shot["startMs"], shot["endMs"]) for shot in shots] == [(0, 1000), (1000, 5000)]


def test_builds_ordered_non_overlapping_shots() -> None:
    shots = VideoShotDetectTool.build_shots(10000, [2000, 5500, 8000], 500)

    assert [shot["index"] if "index" in shot else i for i, shot in enumerate(shots)] == [0, 1, 2, 3]
    assert all(shots[index]["endMs"] == shots[index + 1]["startMs"] for index in range(len(shots) - 1))
    assert shots[-1]["endMs"] == 10000
