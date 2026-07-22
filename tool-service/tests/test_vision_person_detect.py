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
        "画面中有人的存在": 0.88,
        "只有一个人": 0.75,
        "一小群人两到五人": 0.12,
        "一大群人": 0.03,
        "人物特写面部清晰": 0.82,
        "人物全身可见": 0.10,
        "人物在行走": 0.55,
        "人物在站立": 0.20,
        "人物就坐": 0.08,
        "人物在交谈或互动": 0.15,
        "画面中没有人物": 0.03,
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
