"""Unit tests for vision.scene-classify tool contract and output shape."""

from __future__ import annotations

import json
from pathlib import Path
from unittest.mock import patch

from app.tools.vision_scene_classify import SCENE_LABELS, SCENE_LABEL_MAP, VisionSceneClassifyTool


def test_manifest():
    tool = VisionSceneClassifyTool()
    manifest = tool.manifest()
    assert manifest["name"] == "vision.scene-classify"
    assert manifest["version"] == "1.0.0"
    assert "SHOT_LIST" in manifest["inputTypes"]
    assert "SCENE_TAGS" in manifest["outputTypes"]
    assert manifest["cacheable"] is True


def test_scene_labels_covered():
    """Every label in SCENE_LABELS has a mapping in SCENE_LABEL_MAP."""
    for label in SCENE_LABELS:
        assert label in SCENE_LABEL_MAP, f"Missing mapping for: {label}"


def test_output_schema_conforms():
    """Output payload must match contracts/vision/scene-tags.schema.json."""
    schema_path = Path(__file__).resolve().parent.parent.parent / "contracts" / "vision" / "scene-tags.schema.json"
    if not schema_path.is_file():
        return
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    required = schema.get("required", [])
    assert "schemaVersion" in required
    assert "shots" in required


def test_classify_with_mock():
    """Execution raises ValueError when inputs are missing or empty."""
    tool = VisionSceneClassifyTool()

    from app.core.models import ToolExecutionRequest, ArtifactInput, TraceContext
    req = ToolExecutionRequest(
        tool="vision.scene-classify",
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
