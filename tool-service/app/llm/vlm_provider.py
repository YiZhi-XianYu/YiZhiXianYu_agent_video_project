"""VLM Provider abstraction for vision-language model calls.

OpenAI-compatible vision API format. Supports DeepSeek, GPT-4V,
or any OpenAI-compatible vision endpoint.
"""

from __future__ import annotations

import base64
import json
import logging
import time
from abc import ABC, abstractmethod
from typing import Any

import httpx

from app.core.config import settings
from app.llm.router import record_route_call

logger = logging.getLogger(__name__)


class VlmError(Exception):
    """Raised when a VLM call fails for any reason."""


class VlmProvider(ABC):
    """Abstract VLM provider for image analysis."""

    @property
    @abstractmethod
    def name(self) -> str:
        """Human-readable provider identifier."""

    @abstractmethod
    def analyze_images(
        self,
        images_b64: list[str],
        system_prompt: str,
        user_prompt: str,
        *,
        temperature: float = 0.3,
        max_tokens: int = 4096,
        request_id: str = "",
    ) -> dict[str, Any]:
        """Send images with a prompt and return a JSON object.

        Raises VlmError on failure.
        """


def encode_image_b64(image_path: str) -> str:
    """Read an image file and return a base64 data URI string."""
    with open(image_path, "rb") as f:
        return base64.b64encode(f.read()).decode("utf-8")


class NoopVlmProvider(VlmProvider):
    """No-op provider used when no VLM is configured."""

    @property
    def name(self) -> str:
        return "noop"

    def analyze_images(
        self,
        images_b64: list[str],
        system_prompt: str,
        user_prompt: str,
        *,
        temperature: float = 0.3,
        max_tokens: int = 4096,
        request_id: str = "",
    ) -> dict[str, Any]:
        raise VlmError("No VLM provider configured")


class OpenAICompatibleVisionProvider(VlmProvider):
    """OpenAI-compatible vision API (GPT-4V, DeepSeek Vision, etc.)."""

    def __init__(
        self,
        api_key: str,
        base_url: str = "https://api.deepseek.com",
        model: str = "deepseek-chat",
    ) -> None:
        self._api_key = api_key
        self._base_url = base_url.rstrip("/")
        self._model = model
        self._client = httpx.Client(timeout=httpx.Timeout(120.0))

    @property
    def name(self) -> str:
        return "openai-compatible-vision"

    @property
    def model(self) -> str:
        return self._model

    def analyze_images(
        self,
        images_b64: list[str],
        system_prompt: str,
        user_prompt: str,
        *,
        temperature: float = 0.3,
        max_tokens: int = 4096,
        request_id: str = "",
    ) -> dict[str, Any]:
        url = f"{self._base_url}/chat/completions"
        headers = {
            "Authorization": f"Bearer {self._api_key}",
            "Content-Type": "application/json",
        }

        image_contents: list[dict[str, Any]] = []
        for img in images_b64:
            image_contents.append({
                "type": "image_url",
                "image_url": {"url": f"data:image/jpeg;base64,{img}"},
            })

        messages: list[dict[str, Any]] = [
            {"role": "system", "content": system_prompt},
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": user_prompt},
                    *image_contents,
                ],
            },
        ]

        payload: dict[str, Any] = {
            "model": self._model,
            "temperature": temperature,
            "max_tokens": max_tokens,
            "messages": messages,
            "response_format": {"type": "json_object"},
        }

        start = time.monotonic()
        try:
            resp = self._client.post(url, headers=headers, json=payload)
            resp.raise_for_status()
        except httpx.HTTPError as exc:
            elapsed_ms = int((time.monotonic() - start) * 1000)
            record_route_call("SHOT_SEMANTICS", "openai-compatible-vision", latency_ms=elapsed_ms, success=False, fallback_reason="VLM_CALL_FAILED")
            logger.error("VLM HTTP error [%s] after %dms: %s", request_id, elapsed_ms, exc)
            raise VlmError(f"VLM API call failed: {exc}") from exc

        elapsed_ms = int((time.monotonic() - start) * 1000)
        body = resp.json()

        if "choices" not in body or not body["choices"]:
            raise VlmError("VLM returned no choices")

        content_text = body["choices"][0].get("message", {}).get("content", "")
        if not content_text:
            raise VlmError("VLM returned empty content")

        try:
            result = json.loads(content_text)
        except (json.JSONDecodeError, TypeError, ValueError) as exc:
            logger.warning(
                "VLM non-JSON response [%s] after %dms: %.500s",
                request_id, elapsed_ms, content_text,
            )
            raise VlmError("VLM did not return valid JSON") from exc

        logger.info(
            "VLM success [%s] %dms, tokens: prompt=%s completion=%s",
            request_id,
            elapsed_ms,
            body.get("usage", {}).get("prompt_tokens", "?"),
            body.get("usage", {}).get("completion_tokens", "?"),
        )
        record_route_call(
            "SHOT_SEMANTICS", "openai-compatible-vision", latency_ms=elapsed_ms,
            prompt_tokens=int(body.get("usage", {}).get("prompt_tokens", 0) or 0),
            completion_tokens=int(body.get("usage", {}).get("completion_tokens", 0) or 0),
            success=True,
        )
        return result


_vlm_provider: VlmProvider | None = None


def get_vlm_provider() -> VlmProvider:
    """Return the configured VLM provider, or a no-op if none is configured.

    Note: DeepSeek Chat does NOT support vision/image inputs.
    VLM is only available with vision-capable models (GPT-4V, Claude, etc.).
    When VLM is unavailable the vision.vlm-analyze tool falls back to CLIP.
    """
    global _vlm_provider
    if _vlm_provider is not None:
        return _vlm_provider

    api_key = getattr(settings, 'vlm_api_key', '') or settings.llm_api_key
    if not api_key:
        _vlm_provider = NoopVlmProvider()
        return _vlm_provider

    provider_name = getattr(settings, 'vlm_provider', '') or settings.llm_provider

    # DeepSeek models do not support vision — skip to CLIP fallback
    if provider_name.lower() == "deepseek":
        logger.info("VLM provider is DeepSeek (no vision support), using CLIP fallback")
        _vlm_provider = NoopVlmProvider()
        return _vlm_provider

    base_url = getattr(settings, 'vlm_base_url', '') or settings.llm_base_url
    model = getattr(settings, 'vlm_model', '') or settings.llm_model

    if provider_name.lower() in ("openai", "openai-compatible"):
        _vlm_provider = OpenAICompatibleVisionProvider(
            api_key=api_key,
            base_url=base_url,
            model=model,
        )
    else:
        logger.warning("Unknown VLM provider '%s', using noop", provider_name)
        _vlm_provider = NoopVlmProvider()

    return _vlm_provider
