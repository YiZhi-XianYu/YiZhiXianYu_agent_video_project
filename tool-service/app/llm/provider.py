"""LLM Provider abstraction layer.

Extensible design: add new providers by subclassing `LlmProvider`
and registering in `get_provider()`. Each Tool uses the shared
provider instance; switching models requires only a config change.
"""

from __future__ import annotations

import json
import logging
import time
from abc import ABC, abstractmethod
from typing import Any

import httpx

from app.core.config import settings

logger = logging.getLogger(__name__)


class LlmProvider(ABC):
    """Abstract LLM provider. Subclass to add new model backends."""

    @property
    @abstractmethod
    def name(self) -> str:
        """Human-readable provider identifier (e.g. 'deepseek', 'openai')."""

    @abstractmethod
    def generate_json(
        self,
        system_prompt: str,
        user_prompt: str,
        json_schema: dict[str, Any],
        *,
        temperature: float = 0.3,
        max_tokens: int = 4096,
        request_id: str = "",
    ) -> dict[str, Any]:
        """Send a prompt and return a JSON object validated against `json_schema`.

        Raises `LlmError` on transport failure or non-conforming responses.
        """

    def supports_tool_calling(self) -> bool:
        """Override to True when this provider implements function-calling.

        Reserved for Phase 6+ when LLM may call Tools directly.
        """
        return False

    def generate_tool_call(
        self,
        system_prompt: str,
        user_prompt: str,
        tools: list[dict[str, Any]],
        *,
        temperature: float = 0.3,
        request_id: str = "",
    ) -> dict[str, Any]:
        """Reserved for Phase 6+: LLM-initiated Tool calls."""
        raise NotImplementedError("Tool calling is not implemented for this provider")


class LlmError(Exception):
    """Raised when an LLM call fails for any reason (transport, schema, timeout)."""


class NoopProvider(LlmProvider):
    """No-op provider used when no LLM is configured. Always raises LlmError."""

    @property
    def name(self) -> str:
        return "noop"

    def generate_json(
        self,
        system_prompt: str,
        user_prompt: str,
        json_schema: dict[str, Any],
        *,
        temperature: float = 0.3,
        max_tokens: int = 4096,
        request_id: str = "",
    ) -> dict[str, Any]:
        raise LlmError("No LLM provider configured (set LLM_API_KEY and LLM_PROVIDER)")


class DeepSeekProvider(LlmProvider):
    """DeepSeek API provider (OpenAI-compatible /chat/completions endpoint)."""

    def __init__(
        self,
        api_key: str,
        base_url: str = "https://api.deepseek.com",
        model: str = "deepseek-chat",
    ) -> None:
        self._api_key = api_key
        self._base_url = base_url.rstrip("/")
        self._model = model
        self._client = httpx.Client(timeout=httpx.Timeout(60.0))

    @property
    def name(self) -> str:
        return "deepseek"

    @property
    def model(self) -> str:
        return self._model

    def generate_json(
        self,
        system_prompt: str,
        user_prompt: str,
        json_schema: dict[str, Any],
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
        payload: dict[str, Any] = {
            "model": self._model,
            "temperature": temperature,
            "max_tokens": max_tokens,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            "response_format": {"type": "json_object"},
        }

        start = time.monotonic()
        try:
            resp = self._client.post(url, headers=headers, json=payload)
            resp.raise_for_status()
        except httpx.HTTPError as exc:
            elapsed_ms = int((time.monotonic() - start) * 1000)
            logger.error("DeepSeek HTTP error [%s] after %dms: %s", request_id, elapsed_ms, exc)
            raise LlmError(f"DeepSeek API call failed: {exc}") from exc

        elapsed_ms = int((time.monotonic() - start) * 1000)
        body = resp.json()

        if "choices" not in body or not body["choices"]:
            raise LlmError("DeepSeek returned no choices")

        content_text = body["choices"][0].get("message", {}).get("content", "")
        if not content_text:
            raise LlmError("DeepSeek returned empty content")

        try:
            result = json.loads(content_text)
        except json.JSONDecodeError as exc:
            logger.warning(
                "DeepSeek non-JSON response [%s] after %dms: %s",
                request_id, elapsed_ms, content_text[:500],
            )
            raise LlmError("LLM did not return valid JSON") from exc

        logger.info(
            "DeepSeek success [%s] %dms, tokens: prompt=%s completion=%s",
            request_id,
            elapsed_ms,
            body.get("usage", {}).get("prompt_tokens", "?"),
            body.get("usage", {}).get("completion_tokens", "?"),
        )
        return result


_provider: LlmProvider | None = None


def get_provider() -> LlmProvider:
    """Return the configured LLM provider, or a no-op if none is configured.

    When no API key is set the function returns a `NoopProvider` so callers
    can safely fall through to deterministic logic.
    """
    global _provider
    if _provider is not None:
        return _provider

    if not settings.llm_api_key:
        _provider = NoopProvider()
        return _provider

    provider_name = settings.llm_provider.lower()
    if provider_name == "deepseek":
        _provider = DeepSeekProvider(
            api_key=settings.llm_api_key,
            base_url=settings.llm_base_url,
            model=settings.llm_model,
        )
    else:
        logger.warning("Unknown LLM provider '%s', using noop", provider_name)
        _provider = NoopProvider()

    return _provider
