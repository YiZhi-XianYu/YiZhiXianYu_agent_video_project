from __future__ import annotations

import hashlib
import threading
import time
import uuid
from dataclasses import dataclass
from typing import Any

from app.core.config import settings
from prometheus_client import Counter, Histogram


ROUTE_CALLS = Counter("agentvideo_model_router_calls_total", "Model Router calls", ["capability", "provider", "status"])
ROUTE_LATENCY = Histogram("agentvideo_model_router_latency_seconds", "Model Router call latency", ["capability", "provider"])
ROUTE_TOKENS = Counter("agentvideo_model_router_tokens_total", "Model Router token usage", ["capability", "provider", "kind"])


class ProviderHealth:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._failures: dict[str, int] = {}
        self._cooldown_until: dict[str, float] = {}

    def available(self, provider: str) -> bool:
        with self._lock:
            return self._cooldown_until.get(provider, 0.0) <= time.monotonic()

    def success(self, provider: str) -> None:
        with self._lock:
            self._failures.pop(provider, None)
            self._cooldown_until.pop(provider, None)

    def failure(self, provider: str, reason: str = "") -> None:
        with self._lock:
            failures = self._failures.get(provider, 0) + 1
            self._failures[provider] = failures
            threshold = max(1, int(getattr(settings, "model_router_failure_threshold", 2)))
            if failures >= threshold:
                cooldown = max(1.0, float(getattr(settings, "model_router_cooldown_seconds", 30.0)))
                self._cooldown_until[provider] = time.monotonic() + cooldown

    def snapshot(self) -> dict[str, dict[str, float | int]]:
        now = time.monotonic()
        with self._lock:
            return {name: {"failures": self._failures.get(name, 0), "cooldownRemainingSeconds": max(0.0, until - now)}
                    for name, until in self._cooldown_until.items()}


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
            if provider in {"openai", "openai-compatible"} and key and provider_health.available(provider):
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
        if provider in {"openai", "claude", "deepseek"} and has_key and provider_health.available(provider):
            return RouteDecision(route_id, capability, provider, model, fallback, "CONFIG", "Structured text capability uses the configured provider", True)
        return RouteDecision(route_id, capability, "noop", "none", fallback or ("noop",), "CAPABILITY_CHECK", "No configured text provider", False, "LLM_UNAVAILABLE")


model_router = ModelRouter()
provider_health = ProviderHealth()


def record_route_call(capability: str, provider: str, *, latency_ms: int, prompt_tokens: int = 0,
                      completion_tokens: int = 0, success: bool, fallback_reason: str | None = None) -> None:
    status = "success" if success else "failure"
    ROUTE_CALLS.labels(capability, provider, status).inc()
    ROUTE_LATENCY.labels(capability, provider).observe(max(0, latency_ms) / 1000.0)
    if prompt_tokens:
        ROUTE_TOKENS.labels(capability, provider, "prompt").inc(prompt_tokens)
    if completion_tokens:
        ROUTE_TOKENS.labels(capability, provider, "completion").inc(completion_tokens)
    if success:
        provider_health.success(provider)
    else:
        provider_health.failure(provider, fallback_reason or "MODEL_CALL_FAILED")


def prompt_hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()
