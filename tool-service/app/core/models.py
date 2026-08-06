from __future__ import annotations

from datetime import datetime, timezone
from enum import StrEnum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, HttpUrl


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


class ExecutionStatus(StrEnum):
    CLAIM_PENDING = "CLAIM_PENDING"
    QUEUED = "QUEUED"
    RUNNING = "RUNNING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"


class ArtifactInput(BaseModel):
    artifact_id: str = Field(alias="artifactId")
    uri: str
    file_name: str | None = Field(default=None, alias="fileName")

    model_config = ConfigDict(populate_by_name=True)


class TraceContext(BaseModel):
    trace_id: str | None = Field(default=None, alias="traceId")
    session_id: str | None = Field(default=None, alias="sessionId")
    turn_id: str | None = Field(default=None, alias="turnId")
    plan_id: str | None = Field(default=None, alias="planId")
    workflow_run_id: str | None = Field(default=None, alias="workflowRunId")
    task_run_id: str | None = Field(default=None, alias="taskRunId")

    model_config = ConfigDict(populate_by_name=True)


class ToolExecutionRequest(BaseModel):
    tool: str
    version: str
    idempotency_key: str = Field(alias="idempotencyKey")
    inputs: dict[str, ArtifactInput]
    parameters: dict[str, Any] = Field(default_factory=dict)
    callback_url: HttpUrl | None = Field(default=None, alias="callbackUrl")
    trace_context: TraceContext = Field(default_factory=TraceContext, alias="traceContext")

    model_config = ConfigDict(populate_by_name=True)


class ArtifactDescriptor(BaseModel):
    artifact_id: str = Field(alias="artifactId")
    type: str
    uri: str
    media_type: str = Field(alias="mediaType")
    size: int
    content_hash: str = Field(alias="contentHash")
    metadata: dict[str, Any] = Field(default_factory=dict)

    model_config = ConfigDict(populate_by_name=True)


class ToolError(BaseModel):
    code: str
    message: str
    retryable: bool = False
    details: dict[str, Any] = Field(default_factory=dict)


class ToolExecutionRecord(BaseModel):
    execution_id: str = Field(alias="executionId")
    idempotency_key: str = Field(alias="idempotencyKey")
    tool: str
    version: str
    status: ExecutionStatus
    progress: int = 0
    outputs: list[ArtifactDescriptor] = Field(default_factory=list)
    error: ToolError | None = None
    created_at: datetime = Field(default_factory=utc_now, alias="createdAt")
    started_at: datetime | None = Field(default=None, alias="startedAt")
    completed_at: datetime | None = Field(default=None, alias="completedAt")
    recovery_count: int = Field(default=0, alias="recoveryCount")

    model_config = ConfigDict(populate_by_name=True)


class AcceptedExecution(BaseModel):
    execution_id: str = Field(alias="executionId")
    status: ExecutionStatus
    status_url: str = Field(alias="statusUrl")

    model_config = ConfigDict(populate_by_name=True)
