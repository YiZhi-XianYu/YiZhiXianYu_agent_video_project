from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Any


@dataclass
class LlmAuditRecord:
    """Immutable-ish record of one LLM interaction.

    Serialized into the Story Plan artifact so the front-end can display
    whether the plan came from LLM or deterministic fallback.
    """

    provider: str
    model: str
    temperature: float
    request_id: str
    system_prompt_hash: str = ""
    user_prompt_hash: str = ""
    input_candidate_count: int = 0
    raw_response: dict[str, Any] | None = None
    validation_errors: list[str] = field(default_factory=list)
    final_source: str = "DETERMINISTIC_FALLBACK"
    duration_ms: int = 0
    timestamp: str = ""
    route_id: str = ""
    capability: str = ""
    selection_reason: str = ""
    fallback_reason: str = ""
    fallback_chain: list[str] = field(default_factory=list)

    def start(self) -> str:
        """Record the start time and return a request_id."""
        self.timestamp = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        return self.request_id

    def mark_llm_success(self, provider_name: str, model_name: str, raw: dict[str, Any]) -> None:
        self.provider = provider_name
        self.model = model_name
        self.raw_response = raw
        self.final_source = "LLM"
        self.validation_errors = []

    def mark_validation_failed(self, errors: list[str]) -> None:
        self.validation_errors = errors
        self.final_source = "DETERMINISTIC_FALLBACK"

    def mark_llm_error(self, provider_name: str, model_name: str) -> None:
        self.provider = provider_name
        self.model = model_name
        self.final_source = "DETERMINISTIC_FALLBACK"

    def to_dict(self) -> dict[str, Any]:
        """Serialize for inclusion in artifact metadata."""
        result = {
            "provider": self.provider,
            "model": self.model,
            "temperature": self.temperature,
            "requestId": self.request_id,
            "inputCandidateCount": self.input_candidate_count,
            "validationErrors": self.validation_errors,
            "finalSource": self.final_source,
            "durationMs": self.duration_ms,
            "timestamp": self.timestamp,
            "routeId": self.route_id,
            "capability": self.capability,
            "selectionReason": self.selection_reason,
            "fallbackReason": self.fallback_reason,
            "fallbackChain": self.fallback_chain,
        }
        if self.raw_response is not None:
            result["rawResponse"] = self.raw_response
        return result
