"""LLM Provider abstraction layer.

Extensible design: add new providers by subclassing `LlmProvider`
and registering in `get_provider()`. Each Tool uses the shared
provider instance; switching models requires only a config change.

Supported providers:
  - deepseek:    DeepSeek V3 (OpenAI-compatible, json_object mode)
  - openai:      GPT-4o (strict structured output via json_schema + strict:true)
  - claude:      Claude Sonnet 4/4.6 (tool-use-based structured output)
  - noop:        No-op fallback when no LLM is configured
"""

from __future__ import annotations

import json
import logging
import re
import time
from abc import ABC, abstractmethod
from pathlib import Path
from typing import Any

import httpx

from app.core.config import settings
from app.llm.router import model_router, record_route_call, estimate_cost_usd

logger = logging.getLogger(__name__)

# ── Path to the LLM contract (shared JSON Schema for structured output) ──
_CONTRACTS_DIR = Path(__file__).resolve().parent.parent.parent.parent / "contracts" / "llm"


def _load_story_proposal_schema() -> dict[str, Any]:
    """Load the story-plan-proposal JSON Schema for strict structured output."""
    schema_path = _CONTRACTS_DIR / "story-plan-proposal.schema.json"
    if schema_path.exists():
        with open(schema_path, encoding="utf-8") as f:
            return json.load(f)
    logger.warning("Story proposal schema not found at %s, strict mode disabled", schema_path)
    return {}


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

    def supports_structured_output(self) -> bool:
        """Return True when this provider guarantees schema-conformant JSON output.

        Models with strict structured output (GPT-4o via json_schema strict:true,
        Claude via tool_use) can skip post-hoc JSON parsing validation.
        """
        return False

    def usage(self) -> dict[str, int]:
        return getattr(self, "_last_usage", {"promptTokens": 0, "completionTokens": 0})

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


def _parse_json_response(content_text: str, request_id: str, provider_name: str) -> dict[str, Any]:
    """Parse common model JSON variants while retaining useful diagnostics."""
    raw = content_text.strip()
    logger.debug("%s raw structured response [%s]: %s", provider_name, request_id, raw[:4000])
    candidates = [raw]
    if raw.startswith("```"):
        unfenced = re.sub(r"^```(?:json)?\s*|\s*```$", "", raw, flags=re.I | re.S).strip()
        candidates.append(unfenced)
    start, end = raw.find("{"), raw.rfind("}")
    if start >= 0 and end > start:
        candidates.append(raw[start:end + 1])
    last_error: Exception | None = None
    for candidate in dict.fromkeys(candidates):
        try:
            value = json.loads(candidate)
            if not isinstance(value, dict):
                raise ValueError("structured response root must be an object")
            return value
        except (json.JSONDecodeError, ValueError) as exc:
            last_error = exc
    logger.warning("%s JSON parse failed [%s]: %s; raw=%s", provider_name, request_id, last_error, raw[:1000])
    raise LlmError(f"{provider_name} returned invalid structured JSON: {last_error}") from last_error


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
        self._last_usage = {"promptTokens": int(body.get("usage", {}).get("prompt_tokens", 0) or 0),
                            "completionTokens": int(body.get("usage", {}).get("completion_tokens", 0) or 0)}

        if "choices" not in body or not body["choices"]:
            raise LlmError("DeepSeek returned no choices")

        content_text = body["choices"][0].get("message", {}).get("content", "")
        if not content_text:
            raise LlmError("DeepSeek returned empty content")

        try:
            result = _parse_json_response(content_text, request_id, "DeepSeek")
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


# ──────────────────────────────────────────────────────────────────────────────
#  OpenAIProvider — GPT-4o with strict structured output
# ──────────────────────────────────────────────────────────────────────────────

class OpenAIProvider(LlmProvider):
    """OpenAI provider with strict structured output (GPT-4o and later).

    Uses ``response_format`` with ``json_schema`` + ``strict: true`` to
    guarantee that the model's output conforms to the supplied JSON Schema.
    This eliminates the most common LLM failure modes:

    * Missing required fields
    * Wrong types (string vs int)
    * Values outside ``minimum``/``maximum``
    * Strings not matching ``pattern``
    * Array lengths outside ``minItems``/``maxItems``
    * Values not in ``enum``
    * Extra properties not in the schema

    The schema is loaded from ``contracts/llm/story-plan-proposal.schema.json``
    at call time and injected into the ``response_format`` block.
    """

    def __init__(
        self,
        api_key: str,
        base_url: str = "https://api.openai.com",
        model: str = "gpt-4o",
    ) -> None:
        self._api_key = api_key
        self._base_url = base_url.rstrip("/")
        self._model = model
        self._client = httpx.Client(timeout=httpx.Timeout(90.0))

    @property
    def name(self) -> str:
        return "openai"

    @property
    def model(self) -> str:
        return self._model

    def supports_structured_output(self) -> bool:
        return True

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
        url = f"{self._base_url}/v1/chat/completions"
        headers = {
            "Authorization": f"Bearer {self._api_key}",
            "Content-Type": "application/json",
        }

        # Use strict structured output only when a schema is provided.
        # Otherwise fall back to json_object mode (for simple tasks like
        # duration parsing where we don't have a formal schema).
        effective_schema = json_schema if json_schema else {}
        if effective_schema:
            schema_name = effective_schema.get("title", "response")
            safe_name = "".join(c for c in schema_name if c.isalnum() or c in "_-")[:64] or "story_proposal"
            response_format: dict[str, Any] = {
                "type": "json_schema",
                "json_schema": {
                    "name": safe_name,
                    "strict": True,
                    "schema": effective_schema,
                },
            }
        else:
            response_format = {"type": "json_object"}

        payload: dict[str, Any] = {
            "model": self._model,
            "temperature": temperature,
            "max_tokens": max_tokens,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            "response_format": response_format,
        }

        start = time.monotonic()
        try:
            resp = self._client.post(url, headers=headers, json=payload)
            resp.raise_for_status()
        except httpx.HTTPError as exc:
            elapsed_ms = int((time.monotonic() - start) * 1000)
            logger.error("OpenAI HTTP error [%s] after %dms: %s", request_id, elapsed_ms, exc)
            raise LlmError(f"OpenAI API call failed: {exc}") from exc

        elapsed_ms = int((time.monotonic() - start) * 1000)
        body = resp.json()
        self._last_usage = {"promptTokens": int(body.get("usage", {}).get("prompt_tokens", 0) or 0),
                            "completionTokens": int(body.get("usage", {}).get("completion_tokens", 0) or 0)}

        if "choices" not in body or not body["choices"]:
            raise LlmError("OpenAI returned no choices")

        choice = body["choices"][0]
        finish_reason = choice.get("finish_reason", "")
        if finish_reason == "length":
            raise LlmError("OpenAI response truncated (max_tokens too low)")

        # With strict:true, the model may refuse to generate if the prompt
        # contradicts the schema.  Surface this as a clear error.
        if choice.get("message", {}).get("refusal"):
            refusal_text = choice["message"]["refusal"]
            logger.warning("OpenAI refusal [%s]: %s", request_id, refusal_text[:500])
            raise LlmError(f"OpenAI refused to generate: {refusal_text[:200]}")

        content_text = choice.get("message", {}).get("content", "")
        if not content_text:
            raise LlmError("OpenAI returned empty content")

        try:
            result = _parse_json_response(content_text, request_id, "OpenAI")
        except json.JSONDecodeError as exc:
            logger.warning(
                "OpenAI non-JSON response [%s] after %dms (should not happen with strict mode): %s",
                request_id, elapsed_ms, content_text[:500],
            )
            raise LlmError("LLM did not return valid JSON") from exc

        logger.info(
            "OpenAI strict success [%s] %dms, tokens: prompt=%s completion=%s finish=%s",
            request_id,
            elapsed_ms,
            body.get("usage", {}).get("prompt_tokens", "?"),
            body.get("usage", {}).get("completion_tokens", "?"),
            finish_reason,
        )
        return result


# ──────────────────────────────────────────────────────────────────────────────
#  ClaudeProvider — Anthropic Claude with tool-use structured output
# ──────────────────────────────────────────────────────────────────────────────

class ClaudeProvider(LlmProvider):
    """Anthropic Claude provider using tool-use for structured output.

    Claude does not have a ``response_format`` equivalent.  Instead we define a
    single tool named ``output_story_proposal`` whose ``input_schema`` is the
    JSON Schema from the contract, and force Claude to call it via
    ``tool_choice: {"type": "tool", "name": "output_story_proposal"}``.

    This achieves the same guarantee as OpenAI's strict mode: the model's
    output is the tool's input, which the API validates against the schema.
    """

    def __init__(
        self,
        api_key: str,
        base_url: str = "https://api.anthropic.com",
        model: str = "claude-sonnet-4-6",
    ) -> None:
        self._api_key = api_key
        self._base_url = base_url.rstrip("/")
        self._model = model
        self._client = httpx.Client(timeout=httpx.Timeout(90.0))

    @property
    def name(self) -> str:
        return "claude"

    @property
    def model(self) -> str:
        return self._model

    def supports_structured_output(self) -> bool:
        return True

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
        url = f"{self._base_url}/v1/messages"
        headers = {
            "x-api-key": self._api_key,
            "Content-Type": "application/json",
            "anthropic-version": "2023-06-01",
        }

        effective_schema = json_schema if json_schema else {}
        payload: dict[str, Any] = {
            "model": self._model,
            "max_tokens": max_tokens,
            "temperature": temperature,
            "system": system_prompt,
            "messages": [
                {"role": "user", "content": user_prompt},
            ],
        }

        # If a schema is provided, use tool-use for structured output.
        # Otherwise, fall back to prompting for JSON (simple tasks).
        if effective_schema:
            schema_name = effective_schema.get("title", "story_proposal")
            safe_name = "".join(c for c in schema_name if c.isalnum() or c in "_-")[:64] or "output_story_proposal"
            safe_name = re.sub(r'[^a-zA-Z0-9_-]', '_', safe_name)[:64]

            payload["tools"] = [{
                "name": safe_name,
                "description": "Output structured data conforming to the required schema.",
                "input_schema": effective_schema,
            }]
            payload["tool_choice"] = {"type": "tool", "name": safe_name}
        # else: no tools — Claude returns plain text, we parse JSON from it

        start = time.monotonic()
        try:
            resp = self._client.post(url, headers=headers, json=payload)
            resp.raise_for_status()
        except httpx.HTTPError as exc:
            elapsed_ms = int((time.monotonic() - start) * 1000)
            logger.error("Claude HTTP error [%s] after %dms: %s", request_id, elapsed_ms, exc)
            raise LlmError(f"Claude API call failed: {exc}") from exc

        elapsed_ms = int((time.monotonic() - start) * 1000)
        body = resp.json()
        self._last_usage = {"promptTokens": int(body.get("usage", {}).get("input_tokens", 0) or 0),
                            "completionTokens": int(body.get("usage", {}).get("output_tokens", 0) or 0)}

        # Extract the tool call input from the response.
        content_blocks = body.get("content", [])
        tool_input: dict[str, Any] | None = None

        for block in content_blocks:
            if block.get("type") == "tool_use":
                tool_input = block.get("input", {})
                break

        if tool_input is None:
            # The model might have returned text instead of using the tool.
            # This is unusual with tool_choice forced, but handle gracefully.
            text_parts = [
                block.get("text", "")
                for block in content_blocks
                if block.get("type") == "text"
            ]
            combined = "".join(text_parts).strip()
            if combined:
                logger.warning(
                    "Claude returned text instead of tool call [%s], attempting JSON parse",
                    request_id,
                )
                try:
                    tool_input = json.loads(combined)
                except json.JSONDecodeError:
                    raise LlmError("Claude did not return a tool call or valid JSON")
            else:
                raise LlmError("Claude returned no tool call and no text content")

        logger.info(
            "Claude structured success [%s] %dms, tokens: prompt=%s completion=%s stop=%s",
            request_id,
            elapsed_ms,
            body.get("usage", {}).get("input_tokens", "?"),
            body.get("usage", {}).get("output_tokens", "?"),
            body.get("stop_reason", "?"),
        )
        return tool_input


# ──────────────────────────────────────────────────────────────────────────────
#  Provider singleton
# ──────────────────────────────────────────────────────────────────────────────

_provider: LlmProvider | None = None


def get_provider() -> LlmProvider:
    """Return the configured LLM provider, or a no-op if none is configured.

    When no API key is set the function returns a `NoopProvider` so callers
    can safely fall through to deterministic logic.
    """
    global _provider
    if _provider is not None:
        return _provider

    if not (settings.llm_api_key or settings.llm_openai_api_key or settings.llm_anthropic_api_key):
        _provider = NoopProvider()
        return _provider

    provider_name = settings.llm_provider.lower()
    if provider_name == "deepseek":
        _provider = DeepSeekProvider(
            api_key=settings.llm_api_key,
            base_url=settings.llm_base_url,
            model=settings.llm_model,
        )
    elif provider_name == "openai":
        api_key = settings.llm_openai_api_key or settings.llm_api_key
        if not api_key:
            logger.warning("OpenAI provider selected but no API key, using noop")
            _provider = NoopProvider()
        else:
            _provider = OpenAIProvider(
                api_key=api_key,
                model=settings.llm_openai_model,
            )
    elif provider_name == "claude":
        api_key = settings.llm_anthropic_api_key or settings.llm_api_key
        if not api_key:
            logger.warning("Claude provider selected but no API key, using noop")
            _provider = NoopProvider()
        else:
            _provider = ClaudeProvider(
                api_key=api_key,
                model=settings.llm_anthropic_model,
            )
    else:
        logger.warning("Unknown LLM provider '%s', using noop", provider_name)
        _provider = NoopProvider()

    return _provider


def get_provider_for_capability(capability: str) -> tuple[LlmProvider, dict[str, Any]]:
    decision = model_router.route(capability)
    provider = get_provider()
    if decision.provider == "noop" and provider.name != "noop":
        return NoopProvider(), decision.to_dict()
    return provider, decision.to_dict()


def generate_json_with_fallback(
    capability: str, system_prompt: str, user_prompt: str, json_schema: dict[str, Any], *,
    temperature: float = 0.3, max_tokens: int = 4096, request_id: str = "",
) -> tuple[dict[str, Any], dict[str, Any], LlmProvider]:
    decision = model_router.route(capability, request_id=request_id)
    route = decision.to_dict()
    last_error: Exception | None = None
    for provider_name in route["fallbackChain"]:
        if provider_name in {"noop", "clip-local", "whisper-local"}:
            continue
        provider = get_provider() if provider_name == decision.provider else _build_provider(provider_name)
        if provider.name == "noop":
            continue
        started = time.monotonic()
        try:
            result = provider.generate_json(system_prompt, user_prompt, json_schema,
                temperature=temperature, max_tokens=max_tokens, request_id=request_id)
            usage = provider.usage()
            record_route_call(capability, provider_name, latency_ms=int((time.monotonic() - started) * 1000),
                prompt_tokens=usage["promptTokens"], completion_tokens=usage["completionTokens"], success=True)
            route["provider"] = provider_name
            route["model"] = getattr(provider, "model", provider_name)
            route["selectedBy"] = "FALLBACK" if provider_name != decision.provider else decision.selected_by
            route["fallbackReason"] = None if provider_name == decision.provider else "PRIMARY_CALL_FAILED"
            route["promptTokens"] = usage["promptTokens"]
            route["completionTokens"] = usage["completionTokens"]
            route["estimatedCostUsd"] = estimate_cost_usd(provider_name, usage["promptTokens"], usage["completionTokens"])
            return result, route, provider
        except Exception as exc:
            last_error = exc
            record_route_call(capability, provider_name, latency_ms=int((time.monotonic() - started) * 1000), success=False, fallback_reason="MODEL_CALL_FAILED")
    raise LlmError(f"All Model Router providers failed for {capability}: {last_error}") from last_error


def _build_provider(name: str) -> LlmProvider:
    if name == "openai" and (settings.llm_openai_api_key or settings.llm_api_key):
        return OpenAIProvider(settings.llm_openai_api_key or settings.llm_api_key, model=settings.llm_openai_model)
    if name == "claude" and (settings.llm_anthropic_api_key or settings.llm_api_key):
        return ClaudeProvider(settings.llm_anthropic_api_key or settings.llm_api_key, model=settings.llm_anthropic_model)
    if name == "deepseek" and settings.llm_api_key:
        return DeepSeekProvider(settings.llm_api_key, base_url=settings.llm_base_url, model=settings.llm_model)
    return NoopProvider()
