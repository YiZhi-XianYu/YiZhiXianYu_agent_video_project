from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from threading import Lock
from uuid import uuid4

import httpx

from app.core.config import settings
from app.core.models import (
    ExecutionStatus,
    ToolError,
    ToolExecutionRecord,
    ToolExecutionRequest,
)
from app.registry.registry import registry


class ExecutionService:
    def __init__(self) -> None:
        self._records: dict[str, ToolExecutionRecord] = {}
        self._requests: dict[str, ToolExecutionRequest] = {}
        self._idempotency: dict[str, str] = {}
        self._lock = Lock()
        self._pool = ThreadPoolExecutor(max_workers=4, thread_name_prefix="tool-worker")

    def submit(self, request: ToolExecutionRequest) -> ToolExecutionRecord:
        registry.get(request.tool, request.version)
        with self._lock:
            existing_id = self._idempotency.get(request.idempotency_key)
            if existing_id:
                return self._records[existing_id]

            execution_id = f"tex_{uuid4().hex}"
            record = ToolExecutionRecord(
                executionId=execution_id,
                idempotencyKey=request.idempotency_key,
                tool=request.tool,
                version=request.version,
                status=ExecutionStatus.QUEUED,
            )
            self._records[execution_id] = record
            self._requests[execution_id] = request
            self._idempotency[request.idempotency_key] = execution_id
            self._pool.submit(self._run, execution_id)
            return record

    def get(self, execution_id: str) -> ToolExecutionRecord | None:
        with self._lock:
            return self._records.get(execution_id)

    def shutdown(self) -> None:
        self._pool.shutdown(wait=False, cancel_futures=True)

    def _run(self, execution_id: str) -> None:
        request = self._requests[execution_id]
        self._update(
            execution_id,
            status=ExecutionStatus.RUNNING,
            progress=10,
            started_at=datetime.now(timezone.utc),
        )
        try:
            tool = registry.get(request.tool, request.version)
            outputs = tool.execute(request, lambda progress: self._report_progress(execution_id, progress))
            record = self._update(
                execution_id,
                status=ExecutionStatus.SUCCEEDED,
                progress=100,
                outputs=outputs,
                completed_at=datetime.now(timezone.utc),
            )
        except Exception as exc:  # Tool failures are normalized at the service boundary.
            record = self._update(
                execution_id,
                status=ExecutionStatus.FAILED,
                progress=100,
                error=ToolError(
                    code="TOOL_EXECUTION_FAILED",
                    message=str(exc),
                    retryable=False,
                ),
                completed_at=datetime.now(timezone.utc),
            )
        self._callback(request, record)

    def _report_progress(self, execution_id: str, progress: int) -> None:
        self._update(execution_id, progress=max(10, min(progress, 99)))

    def _update(self, execution_id: str, **changes) -> ToolExecutionRecord:
        with self._lock:
            record = self._records[execution_id]
            updated = record.model_copy(update=changes)
            self._records[execution_id] = updated
            return updated

    def _callback(self, request: ToolExecutionRequest, record: ToolExecutionRecord) -> None:
        if request.callback_url is None:
            return
        payload = record.model_dump(mode="json", by_alias=True)
        try:
            with httpx.Client(timeout=settings.callback_timeout_seconds) as client:
                client.post(str(request.callback_url), json=payload).raise_for_status()
        except httpx.HTTPError:
            # Java also polls execution status, so a lost callback is recoverable.
            return


execution_service = ExecutionService()
