from __future__ import annotations

import hashlib
import uuid
from dataclasses import dataclass
from typing import Any

from app.core.config import settings


TEXT_CAPABILITIES = {"STRUCTURED_INTENT", "STORY_PLAN"}
VLM_CAPABILITIES = {"SHOT_SEMANTICS"}
ASR_CAPABILITIES = {"LONG_AUDIO_TRANSCRIPTION"}


@dataclass(frozen=True)
class RouteDecision:
    route_id: str
    capability: str
    provider: str
    model: str
    fallback_chain: tuple[str, ...]
    selected_by: str
    selection_reason: str
    available: bool
    fallback_reason: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "routeId": self.route_id,
            "capability": self.capability,
            "provider": self.provider,
            "model": self.model,
            "fallbackChain": list(self.fallback_chain),
            "selectedBy": self.selected_by,
            "selectionReason": self.selection_reason,
            "available": self.available,
            "fallbackReason": self.fallback_reason,
        }


class ModelRouter:
    """Deterministic capability router; it never lets a model choose a model."""

    def route(self, capability: str, *, request_id: str | None = None) -> RouteDecision:
        capability = capability.strip().upper()
        route_id = request_id or uuid.uuid4().hex[:12]
        if capability in ASR_CAPABILITIES:
            model = getattr(settings, "asr_model_size", "small") or "small"
            return RouteDecision(route_id, capability, "whisper-local", model, ("whisper-local",), "CONFIG", "Long audio is handled by the local Whisper runtime", True)
        if capability in VLM_CAPABILITIES:
            provider = (getattr(settings, "vlm_provider", "") or settings.llm_provider or "").lower()
            model = getattr(settings, "vlm_model", "") or settings.llm_model or "unknown"
            key = getattr(settings, "vlm_api_key", "") or settings.llm_api_key
            if provider in {"openai", "openai-compatible"} and key:
                return RouteDecision(route_id, capability, provider, model, (provider, "clip-local"), "CONFIG", "Vision capability requires an image-capable provider", True)
            return RouteDecision(route_id, capability, "clip-local", "openai/clip-vit-base-patch32", ("clip-local",), "CAPABILITY_CHECK", "No configured vision-capable provider", True, "VLM_UNAVAILABLE")
        provider = (settings.llm_provider or "").lower()
        if provider == "deepseek" and not settings.llm_api_key and settings.llm_openai_api_key:
            provider = "openai"
        if provider == "openai" and not (settings.llm_openai_api_key or settings.llm_api_key) and settings.llm_anthropic_api_key:
            provider = "claude"
        model = settings.llm_model or "unknown"
        if provider == "openai":
            model = settings.llm_openai_model
        elif provider == "claude":
            model = settings.llm_anthropic_model
        fallback = tuple(dict.fromkeys(filter(None, (provider, "openai", "claude", "deepseek", "noop"))))
        has_key = bool(
            settings.llm_api_key
            if provider == "deepseek"
            else settings.llm_openai_api_key or settings.llm_api_key
            if provider == "openai"
            else settings.llm_anthropic_api_key or settings.llm_api_key
            if provider == "claude"
            else False
        )
        if provider in {"openai", "claude", "deepseek"} and has_key:
            return RouteDecision(route_id, capability, provider, model, fallback, "CONFIG", "Structured text capability uses the configured provider", True)
        return RouteDecision(route_id, capability, "noop", "none", fallback or ("noop",), "CAPABILITY_CHECK", "No configured text provider", False, "LLM_UNAVAILABLE")


model_router = ModelRouter()


def prompt_hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()
