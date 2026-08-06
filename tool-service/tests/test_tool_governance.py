import pytest

from app.registry.governance import normalize_manifest
from app.registry.registry import registry


def test_all_registered_tools_expose_governance_fields():
    manifests = registry.manifests()
    assert len(manifests) >= 17
    for manifest in manifests:
        assert manifest["automationPolicy"] in {"AUTO", "REQUIRE_CONFIRMATION", "MANUAL_ONLY", "DISABLED"}
        assert manifest["sideEffectLevel"] in {"NONE", "LOW", "HIGH"}
        assert manifest["maxAttempts"] >= 1
        assert manifest["timeoutSeconds"] > 0
        assert "estimatedCost" in manifest


def test_render_and_bgm_require_confirmation():
    manifests = {m["name"]: m for m in registry.manifests()}
    assert manifests["video.render"]["automationPolicy"] == "REQUIRE_CONFIRMATION"
    assert manifests["video.render"]["sideEffectLevel"] == "HIGH"
    assert manifests["audio.bgm-select"]["requiresUserConfirmation"] is True


def test_invalid_governance_is_rejected():
    with pytest.raises(ValueError):
        normalize_manifest({"name": "x", "version": "1.0.0", "timeoutSeconds": 0})
