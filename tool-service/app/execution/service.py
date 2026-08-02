from __future__ import annotations

from collections import deque
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import logging
import time
from pathlib import Path
from threading import Lock
from uuid import uuid4

import httpx
from prometheus_client import Counter, Gauge, Histogram

from app.core.config import settings
from app.core.models import (
    ExecutionStatus,
    ToolError,
    ToolExecutionRecord,
    ToolExecutionRequest,
)
from app.execution.store import ExecutionStore
from app.execution.resources import LIGHT, MEDIA, MODEL, RENDER, ResourcePolicy, resource_group
from app.registry.registry import ToolRegistry, registry
from app.storage.oss_storage import artifact_storage

logger = logging.getLogger(__name__)

EXECUTIONS_TOTAL = Counter("agentvideo_tool_executions_total", "Tool executions by terminal status", ["status", "resource_group"])
EXECUTION_DURATION = Histogram("agentvideo_tool_execution_duration_seconds", "Tool execution duration", ["resource_group", "tool"])
QUEUE_DEPTH = Gauge("agentvideo_tool_queue_depth", "Executions waiting for a worker")
ACTIVE_EXECUTIONS = Gauge("agentvideo_tool_active_executions", "Currently running executions")
ACTIVE_GROUP = Gauge("agentvideo_tool_active_resource_group", "Active executions by resource group", ["resource_group"])
CALLBACK_TOTAL = Counter("agentvideo_tool_callbacks_total", "Callback attempts and failures", ["status"])


class ExecutionService:
    def __init__(
        self,
        store_path: Path | None = None,
        *,
        tool_registry: ToolRegistry | None = None,
        max_workers: int | None = None,
        resource_limits: dict[str, int] | None = None,
        heavy_limit: int | None = None,
    ) -> None:
        self._store = ExecutionStore(store_path or settings.execution_store_path)
        self._registry = tool_registry or registry
        worker_count = max_workers or settings.execution_max_workers
        self._policy = ResourcePolicy(
            max_workers=max(1, worker_count),
            limits=resource_limits or {
                LIGHT: settings.execution_light_limit,
                MEDIA: settings.execution_media_limit,
                MODEL: settings.execution_model_limit,
                RENDER: settings.execution_render_limit,
            },
            heavy_limit=max(1, heavy_limit or settings.execution_heavy_limit),
        )
        self._records: dict[str, ToolExecutionRecord] = {}
        self._requests: dict[str, ToolExecutionRequest] = {}
        self._scheduled: set[str] = set()
        self._pending: deque[str] = deque()
        self._execution_groups: dict[str, str] = {}
        self._active_by_group: dict[str, int] = {}
        self._active_total = 0
        self._active_heavy_weight = 0
        self._lock = Lock()
        self._pool: ThreadPoolExecutor | None = None
        self._started = False

    def metrics_snapshot(self) -> dict[str, int]:
        with self._lock:
            return {"queue_depth": len(self._pending), "active": self._active_total}

    def start(self) -> None:
        callbacks: list[tuple[ToolExecutionRequest, ToolExecutionRecord]] = []
        with self._lock:
            if self._started:
                return
            self._pool = ThreadPoolExecutor(
                max_workers=self._policy.max_workers,
                thread_name_prefix="tool-worker",
            )
            self._started = True

            for request, persisted in self._store.list_recoverable():
                recovery_count = persisted.recovery_count
                if persisted.status == ExecutionStatus.RUNNING:
                    recovery_count += 1
                if recovery_count > settings.execution_max_recoveries:
                    record = persisted.model_copy(update={
                        "status": ExecutionStatus.FAILED,
                        "progress": 100,
                        "outputs": [],
                        "error": ToolError(
                            code="EXECUTION_RECOVERY_EXHAUSTED",
                            message="Tool process stopped repeatedly while this execution was running",
                            retryable=True,
                        ),
                        "completed_at": datetime.now(timezone.utc),
                        "recovery_count": recovery_count,
                    })
                    self._requests[record.execution_id] = request
                    self._records[record.execution_id] = record
                    self._store.update(record)
                    callbacks.append((request, record))
                    continue
                record = persisted.model_copy(update={
                    "status": ExecutionStatus.QUEUED,
                    "progress": 0,
                    "outputs": [],
                    "error": None,
                    "started_at": None,
                    "completed_at": None,
                    "recovery_count": recovery_count,
                })
                self._requests[record.execution_id] = request
                self._records[record.execution_id] = record
                self._store.update(record)
                self._schedule_locked(record.execution_id)
        for request, record in callbacks:
            self._callback(request, record)

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
        self._pending.append(execution_id)
        QUEUE_DEPTH.set(len(self._pending))
        self._drain_pending_locked()

    def _drain_pending_locked(self) -> None:
        if self._pool is None:
            return
        while self._active_total < self._policy.max_workers:
            selected_index = self._next_runnable_index_locked()
            if selected_index is None:
                return
            execution_id = self._pending[selected_index]
            del self._pending[selected_index]
            group = self._group_for_execution_locked(execution_id)
            self._execution_groups[execution_id] = group
            self._active_by_group[group] = self._active_by_group.get(group, 0) + 1
            self._active_total += 1
            self._active_heavy_weight += self._policy.heavy_weight(group)
            QUEUE_DEPTH.set(len(self._pending))
            ACTIVE_EXECUTIONS.set(self._active_total)
            ACTIVE_GROUP.labels(group).set(self._active_by_group[group])
            self._pool.submit(self._run, execution_id)

    def _next_runnable_index_locked(self) -> int | None:
        for index, execution_id in enumerate(self._pending):
            group = self._group_for_execution_locked(execution_id)
            group_available = self._active_by_group.get(group, 0) < self._policy.limit_for(group)
            heavy_available = (
                self._active_heavy_weight + self._policy.heavy_weight(group)
                <= self._policy.heavy_limit
            )
            if group_available and heavy_available:
                return index
        return None

    def _group_for_execution_locked(self, execution_id: str) -> str:
        request = self._requests.get(execution_id)
        if request is None:
            persisted = self._store.get(execution_id)
            if persisted is None:
                return LIGHT
            request, record = persisted
            self._requests[execution_id] = request
            self._records[execution_id] = record
        tool = self._registry.get(request.tool, request.version)
        manifest = tool.manifest() if hasattr(tool, "manifest") else None
        return resource_group(manifest)

    def _run(self, execution_id: str) -> None:
        started_at = datetime.now(timezone.utc)
        group = LIGHT
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
                group = self._execution_groups.get(execution_id, LIGHT)
                local_request = artifact_storage.materialize_request(request)
                outputs = tool.execute(
                    local_request,
                    lambda progress: self._report_progress(execution_id, progress),
                )
                outputs = artifact_storage.publish_outputs(outputs)
                record = self._update(
                    execution_id,
                    status=ExecutionStatus.SUCCEEDED,
                    progress=100,
                    outputs=outputs,
                    error=None,
                    completed_at=datetime.now(timezone.utc),
                )
                self._release_models_if_needed(tool)
                EXECUTIONS_TOTAL.labels("SUCCEEDED", group).inc()
            except Exception as exc:  # Tool failures are normalized at the service boundary.
                if 'tool' in locals():
                    self._release_models_if_needed(tool)
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
                EXECUTIONS_TOTAL.labels("FAILED", group).inc()
            EXECUTION_DURATION.labels(group, request.tool).observe((datetime.now(timezone.utc) - started_at).total_seconds())
            self._callback(request, record)
        finally:
            with self._lock:
                self._scheduled.discard(execution_id)
                group = self._execution_groups.pop(execution_id, LIGHT)
                self._active_by_group[group] = max(0, self._active_by_group.get(group, 1) - 1)
                self._active_total = max(0, self._active_total - 1)
                self._active_heavy_weight = max(
                    0,
                    self._active_heavy_weight - self._policy.heavy_weight(group),
                )
                QUEUE_DEPTH.set(len(self._pending))
                ACTIVE_EXECUTIONS.set(self._active_total)
                ACTIVE_GROUP.labels(group).set(self._active_by_group.get(group, 0))
                self._drain_pending_locked()

    @staticmethod
    def _release_models_if_needed(tool) -> None:
        if not settings.release_models_after_execution:
            return
        manifest = tool.manifest() if hasattr(tool, "manifest") else None
        if resource_group(manifest) != MODEL:
            return
        from app.core.model_lifecycle import release_models
        try:
            release_models()
        except Exception:
            logger.exception("Failed to release process-local ML model references")

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
        attempts = max(1, settings.callback_retry_attempts)
        for attempt in range(attempts):
            try:
                with httpx.Client(timeout=settings.callback_timeout_seconds) as client:
                    client.post(str(request.callback_url), json=payload).raise_for_status()
                CALLBACK_TOTAL.labels("success").inc()
                return
            except httpx.HTTPError:
                if attempt + 1 < attempts:
                    delay = max(0.0, settings.callback_retry_backoff_seconds) * (2 ** attempt)
                    time.sleep(delay)
        # In RabbitMQ mode callbacks are the authoritative result path because
        # each worker owns a separate local execution store.
        CALLBACK_TOTAL.labels("failure").inc()
        return


execution_service = ExecutionService()
