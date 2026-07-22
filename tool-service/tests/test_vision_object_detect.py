"""Unit tests for vision.object-detect tool contract and output shape."""

from __future__ import annotations

from app.tools.vision_object_detect import OBJECT_LABELS, OBJECT_LABEL_MAP, VisionObjectDetectTool


def test_manifest():
    tool = VisionObjectDetectTool()
    manifest = tool.manifest()
    assert manifest["name"] == "vision.object-detect"
    assert manifest["version"] == "1.0.0"
    assert manifest["deterministic"] is True
    assert "OBJECT_TAGS" in manifest["outputTypes"]


def test_object_labels_covered():
    for label in OBJECT_LABELS:
        assert label in OBJECT_LABEL_MAP, f"Missing mapping for: {label}"


def test_empty_inputs_raises():
    tool = VisionObjectDetectTool()
    from app.core.models import ToolExecutionRequest
    req = ToolExecutionRequest(
        tool="vision.object-detect",
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
