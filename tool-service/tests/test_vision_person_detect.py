"""Unit tests for vision.person-detect tool contract and output shape."""

from __future__ import annotations

from app.tools.vision_person_detect import (
    PERSON_LABEL_MAP,
    VisionPersonDetectTool,
    _summarize_person,
)


def test_manifest():
    tool = VisionPersonDetectTool()
    manifest = tool.manifest()
    assert manifest["name"] == "vision.person-detect"
    assert manifest["version"] == "1.0.0"
    assert "PERSON_TAGS" in manifest["outputTypes"]


def test_summarize_no_person():
    scores = {"画面中没有人物": 0.92, "画面中有人的存在": 0.05}
    tags = _summarize_person(scores)
    assert len(tags) == 1
    assert tags[0]["label"] == "NO_PERSON"


def test_summarize_has_person():
    scores = {
        "a photo with a person in it": 0.88,
        "a photo of a single person": 0.75,
        "a photo of a small group of two to five people": 0.12,
        "a photo of a large crowd of people": 0.03,
        "a close-up photo of a person's face": 0.82,
        "a photo of a person with their full body visible": 0.10,
        "a photo of a person walking": 0.55,
        "a photo of a person standing": 0.20,
        "a photo of a person sitting": 0.08,
        "a photo of people talking or interacting": 0.15,
        "a photo with no people in it": 0.03,
    }
    tags = _summarize_person(scores)
    labels = {tag["label"] for tag in tags}
    assert "HAS_PERSON" in labels
    assert "SINGLE_PERSON" in labels
    assert "CLOSE_UP" in labels
    assert "WALKING" in labels


def test_empty_inputs_raises():
    tool = VisionPersonDetectTool()
    from app.core.models import ToolExecutionRequest
    req = ToolExecutionRequest(
        tool="vision.person-detect",
        version="1.0.0",
        idempotencyKey="test-1",
        inputs={},
        parameters={},
    )
    try:
        tool.execute(req)
        assert False, "Should have raised ValueError"
    except ValueError as exc:
        assert "SHOT_LIST" in str(exc)
