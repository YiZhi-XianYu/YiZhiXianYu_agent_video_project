from __future__ import annotations

from typing import Any


AUTOMATION_POLICIES = {"AUTO", "REQUIRE_CONFIRMATION", "MANUAL_ONLY", "DISABLED"}
SIDE_EFFECT_LEVELS = {"NONE", "LOW", "HIGH"}
RESOURCE_GROUPS = {"LIGHT", "MEDIA", "MODEL", "RENDER"}


def _defaults(name: str, resource_group: str) -> dict[str, Any]:
    policy = "AUTO"
    side_effect = "NONE"
    confirmation = False
    max_attempts = 2
    allow_fallback = True
    if name == "video.render":
        policy, side_effect, confirmation, max_attempts, allow_fallback = "REQUIRE_CONFIRMATION", "HIGH", True, 2, False
    elif name == "audio.bgm-select":
        policy, side_effect, confirmation = "REQUIRE_CONFIRMATION", "LOW", True
    elif name in {"subtitle.compose", "timeline.compose"}:
        side_effect = "LOW"
    elif name in {"planning.story-template", "vision.vlm-analyze"}:
        allow_fallback = True
    if resource_group in {"MODEL", "RENDER"}:
        max_attempts = min(max_attempts, 2)
    return {
        "automationPolicy": policy,
        "requiresUserConfirmation": confirmation,
        "sideEffectLevel": side_effect,
        "maxAttempts": max_attempts,
        "allowFallback": allow_fallback,
        "estimatedCost": {"unit": "CPU_SECOND" if resource_group != "MODEL" else "MODEL_SECOND", "max": 0},
    }


def normalize_manifest(raw: dict[str, Any]) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise ValueError("Tool manifest must be an object")
    result = dict(raw)
    name = str(result.get("name", "")).strip()
    version = str(result.get("version", "")).strip()
    if not name or not version:
        raise ValueError("Tool manifest requires name and version")
    group = str(result.get("resourceGroup", "")).strip().upper()
    if not group:
        resource_class = str(result.get("resourceClass", "CPU_LIGHT")).upper()
        group = "LIGHT" if resource_class in {"CPU_LOW", "CPU_LIGHT"} else "MEDIA"
    result["resourceGroup"] = group
    defaults = _defaults(name, group)
    for key, value in defaults.items():
        result.setdefault(key, value)
    result.setdefault("inputSchema", {"type": "object"})
    result.setdefault("outputSchema", {"type": "array"})
    result.setdefault("supportsCancellation", False)
    result.setdefault("executionMode", "ASYNC")
    result.setdefault("inputTypes", [])
    result.setdefault("outputTypes", [])
    validate_manifest(result)
    return result


def validate_manifest(manifest: dict[str, Any]) -> None:
    if manifest.get("automationPolicy") not in AUTOMATION_POLICIES:
        raise ValueError(f"Invalid automationPolicy for {manifest.get('name')}")
    if manifest.get("sideEffectLevel") not in SIDE_EFFECT_LEVELS:
        raise ValueError(f"Invalid sideEffectLevel for {manifest.get('name')}")
    group = str(manifest.get("resourceGroup", "")).upper()
    if group not in RESOURCE_GROUPS:
        raise ValueError(f"Invalid resourceGroup for {manifest.get('name')}")
    timeout = manifest.get("timeoutSeconds")
    if not isinstance(timeout, (int, float)) or timeout <= 0 or timeout > 86400:
        raise ValueError(f"timeoutSeconds must be in (0, 86400] for {manifest.get('name')}")
    attempts = manifest.get("maxAttempts")
    if not isinstance(attempts, int) or not 1 <= attempts <= 10:
        raise ValueError(f"maxAttempts must be in [1, 10] for {manifest.get('name')}")
    if bool(manifest.get("requiresUserConfirmation")) and manifest.get("automationPolicy") == "AUTO":
        raise ValueError(f"AUTO tool cannot require user confirmation: {manifest.get('name')}")

