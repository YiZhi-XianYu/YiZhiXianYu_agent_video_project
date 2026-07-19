from app.tools.vision_quality_score import FRAME_HEIGHT, FRAME_WIDTH, VisionQualityScoreTool


def test_scores_balanced_detailed_frames_higher_than_flat_dark_frames() -> None:
    detailed = bytes((x * 17 + y * 31) % 256 for y in range(FRAME_HEIGHT) for x in range(FRAME_WIDTH))
    flat_dark = bytes([5] * (FRAME_WIDTH * FRAME_HEIGHT))

    detailed_score = VisionQualityScoreTool.score_frames([detailed, detailed, detailed])
    flat_score = VisionQualityScoreTool.score_frames([flat_dark, flat_dark, flat_dark])

    assert detailed_score["qualityScore"] > flat_score["qualityScore"]
    assert detailed_score["clarity"] > flat_score["clarity"]


def test_marks_low_quality_frames_with_an_explainable_reason() -> None:
    frame = bytes([0] * (FRAME_WIDTH * FRAME_HEIGHT))
    score = VisionQualityScoreTool.score_frames([frame, frame])

    assert "LOW_VISUAL_QUALITY" in VisionQualityScoreTool.reason_codes(score)


def test_visual_fingerprint_is_stable_and_sensitive_to_layout() -> None:
    left_bright = bytes(
        230 if x < FRAME_WIDTH // 2 else 20
        for y in range(FRAME_HEIGHT)
        for x in range(FRAME_WIDTH)
    )
    right_bright = bytes(
        20 if x < FRAME_WIDTH // 2 else 230
        for y in range(FRAME_HEIGHT)
        for x in range(FRAME_WIDTH)
    )

    assert VisionQualityScoreTool.visual_fingerprint(left_bright) == VisionQualityScoreTool.visual_fingerprint(left_bright)
    assert VisionQualityScoreTool.visual_fingerprint(left_bright) != VisionQualityScoreTool.visual_fingerprint(right_bright)
