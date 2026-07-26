from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
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
from app.execution.store import ExecutionStore
from app.registry.registry import ToolRegistry, registry


class ExecutionService:
    def __init__(
        self,
        store_path: Path | None = None,
        *,
        tool_registry: ToolRegistry | None = None,
        max_workers: int = 4,
    ) -> None:
        self._store = ExecutionStore(store_path or settings.execution_store_path)
        self._registry = tool_registry or registry
        self._max_workers = max_workers
        self._records: dict[str, ToolExecutionRecord] = {}
        self._requests: dict[str, ToolExecutionRequest] = {}
        self._scheduled: set[str] = set()
        self._lock = Lock()
        self._pool: ThreadPoolExecutor | None = None
        self._started = False

    def start(self) -> None:
        with self._lock:
            if self._started:
                return
            self._pool = ThreadPoolExecutor(
                max_workers=self._max_workers,
                thread_name_prefix="tool-worker",
            )
            self._started = True

            for request, persisted in self._store.list_recoverable():
                record = persisted.model_copy(update={
                    "status": ExecutionStatus.QUEUED,
                    "progress": 0,
                    "outputs": [],
                    "error": None,
                    "started_at": None,
                    "completed_at": None,
                })
                self._requests[record.execution_id] = request
                self._records[record.execution_id] = record
                self._store.update(record)
                self._schedule_locked(record.execution_id)

    def submit(self, request: ToolExecutionRequest) -> ToolExecutionRecord:
        self._registry.get(request.tool, request.version)
        with self._lock:
            existing = self._store.get_by_idempotency_key(request.idempotency_key)
            if existing is not None:
                persisted_request, record = existing
                self._requests[record.execution_id] = persisted_request
                self._records[record.execution_id] = record
                if self._started and record.status in (ExecutionStatus.QUEUED, ExecutionStatus.RUNNING):
                    self._schedule_locked(record.execution_id)
                return record

            execution_id = f"tex_{uuid4().hex}"
            record = ToolExecutionRecord(
                executionId=execution_id,
                idempotencyKey=request.idempotency_key,
                tool=request.tool,
                version=request.version,
                status=ExecutionStatus.QUEUED,
            )
            self._store.create(request, record)
            self._records[execution_id] = record
            self._requests[execution_id] = request
            if self._started:
                self._schedule_locked(execution_id)
            return record

    def get(self, execution_id: str) -> ToolExecutionRecord | None:
        with self._lock:
            record = self._records.get(execution_id)
            if record is not None:
                return record
            persisted = self._store.get(execution_id)
            if persisted is None:
                return None
            request, record = persisted
            self._requests[execution_id] = request
            self._records[execution_id] = record
            return record

    def shutdown(self) -> None:
        with self._lock:
            self._started = False
            pool = self._pool
            self._pool = None
        if pool is not None:
            pool.shutdown(wait=False, cancel_futures=True)

    def _schedule_locked(self, execution_id: str) -> None:
        if execution_id in self._scheduled:
            return
        if self._pool is None:
            return
        self._scheduled.add(execution_id)
        self._pool.submit(self._run, execution_id)

    def _run(self, execution_id: str) -> None:
        try:
            with self._lock:
                request = self._requests.get(execution_id)
                if request is None:
                    persisted = self._store.get(execution_id)
                    if persisted is None:
                        return
                    request, record = persisted
                    self._requests[execution_id] = request
                    self._records[execution_id] = record

            self._update(
                execution_id,
                status=ExecutionStatus.RUNNING,
                progress=10,
                outputs=[],
                error=None,
                started_at=datetime.now(timezone.utc),
                completed_at=None,
            )
            try:
                tool = self._registry.get(request.tool, request.version)
                outputs = tool.execute(
                    request,
                    lambda progress: self._report_progress(execution_id, progress),
                )
                record = self._update(
                    execution_id,
                    status=ExecutionStatus.SUCCEEDED,
                    progress=100,
                    outputs=outputs,
                    error=None,
                    completed_at=datetime.now(timezone.utc),
                )
            except Exception as exc:  # Tool failures are normalized at the service boundary.
                record = self._update(
                    execution_id,
                    status=ExecutionStatus.FAILED,
                    progress=100,
                    outputs=[],
                    error=ToolError(
                        code="TOOL_EXECUTION_FAILED",
                        message=str(exc),
                        retryable=self._is_retryable(exc),
                    ),
                    completed_at=datetime.now(timezone.utc),
                )
            self._callback(request, record)
        finally:
            with self._lock:
                self._scheduled.discard(execution_id)

    @staticmethod
    def _is_retryable(exc: Exception) -> bool:
        explicit_retryable = getattr(exc, "retryable", None)
        if isinstance(explicit_retryable, bool):
            return explicit_retryable
        # Contract and parameter failures are deterministic; runtime and I/O failures may recover.
        return not isinstance(exc, ValueError)

    def _report_progress(self, execution_id: str, progress: int) -> None:
        self._update(execution_id, progress=max(10, min(progress, 99)))

    def _update(self, execution_id: str, **changes) -> ToolExecutionRecord:
        with self._lock:
            record = self._records.get(execution_id)
            if record is None:
                persisted = self._store.get(execution_id)
                if persisted is None:
                    raise KeyError(f"Tool execution not found: {execution_id}")
                request, record = persisted
                self._requests[execution_id] = request
            updated = record.model_copy(update=changes)
            self._store.update(updated)
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
