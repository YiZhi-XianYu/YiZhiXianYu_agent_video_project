from __future__ import annotations

from datetime import datetime, timezone
from enum import StrEnum
from typing import Annotated, Any

from pydantic import BaseModel, ConfigDict, Field, HttpUrl


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


class ExecutionStatus(StrEnum):
    QUEUED = "QUEUED"
    RUNNING = "RUNNING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"


class ArtifactInput(BaseModel):
    artifact_id: Annotated[str, Field(alias="artifactId")]
    uri: str
    file_name: Annotated[str | None, Field(alias="fileName")] = None

    model_config = ConfigDict(populate_by_name=True)


class TraceContext(BaseModel):
    trace_id: Annotated[str | None, Field(alias="traceId")] = None
    workflow_run_id: Annotated[str | None, Field(alias="workflowRunId")] = None
    task_run_id: Annotated[str | None, Field(alias="taskRunId")] = None

    model_config = ConfigDict(populate_by_name=True)


class ToolExecutionRequest(BaseModel):
    tool: str
    version: str
    idempotency_key: Annotated[str, Field(alias="idempotencyKey")]
    inputs: dict[str, ArtifactInput]
    parameters: dict[str, Any] = Field(default_factory=dict)
    callback_url: Annotated[HttpUrl | None, Field(alias="callbackUrl")] = None
    trace_context: Annotated[TraceContext, Field(alias="traceContext")] = Field(default_factory=TraceContext)

    model_config = ConfigDict(populate_by_name=True)


class ArtifactDescriptor(BaseModel):
    artifact_id: Annotated[str, Field(alias="artifactId")]
    type: str
    uri: str
    media_type: Annotated[str, Field(alias="mediaType")]
    size: int
    content_hash: Annotated[str, Field(alias="contentHash")]
    metadata: dict[str, Any] = Field(default_factory=dict)

    model_config = ConfigDict(populate_by_name=True)


class ToolError(BaseModel):
    code: str
    message: str
    retryable: bool = False
    details: dict[str, Any] = Field(default_factory=dict)


class ToolExecutionRecord(BaseModel):
    execution_id: Annotated[str, Field(alias="executionId")]
    idempotency_key: Annotated[str, Field(alias="idempotencyKey")]
    tool: str
    version: str
    status: ExecutionStatus
    progress: int = 0
    outputs: list[ArtifactDescriptor] = Field(default_factory=list)
    error: ToolError | None = None
    created_at: Annotated[datetime, Field(alias="createdAt")] = Field(default_factory=utc_now)
    started_at: Annotated[datetime | None, Field(alias="startedAt")] = None
    completed_at: Annotated[datetime | None, Field(alias="completedAt")] = None

    model_config = ConfigDict(populate_by_name=True)


class AcceptedExecution(BaseModel):
    execution_id: Annotated[str, Field(alias="executionId")]
    status: ExecutionStatus
    status_url: Annotated[str, Field(alias="statusUrl")]

    model_config = ConfigDict(populate_by_name=True)
