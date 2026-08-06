from __future__ import annotations

from collections import deque
from concurrent.futures import ThreadPoolExecutor
from concurrent.futures import TimeoutError as FutureTimeoutError
from datetime import datetime, timedelta, timezone
import logging
import threading
from pathlib import Path
from threading import Event, Lock, Thread
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
from app.registry.governance import normalize_manifest
from app.registry.registry import ToolRegistry, registry
from app.storage.oss_storage import artifact_storage

logger = logging.getLogger(__name__)

EXECUTIONS_TOTAL = Counter("agentvideo_tool_executions_total", "Tool executions by terminal status", ["status", "resource_group"])
EXECUTION_DURATION = Histogram("agentvideo_tool_execution_duration_seconds", "Tool execution duration", ["resource_group", "tool"])
QUEUE_DEPTH = Gauge("agentvideo_tool_queue_depth", "Executions waiting for a worker")
ACTIVE_EXECUTIONS = Gauge("agentvideo_tool_active_executions", "Currently running executions")
ACTIVE_GROUP = Gauge("agentvideo_tool_active_resource_group", "Active executions by resource group", ["resource_group"])
CALLBACK_TOTAL = Counter("agentvideo_tool_callbacks_total", "Callback attempts and failures", ["status"])
CALLBACK_PENDING = Gauge("agentvideo_tool_callback_outbox_pending", "Persisted callback results waiting for delivery")


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
        self._callback_stop = Event()
        self._callback_thread: Thread | None = None

    def metrics_snapshot(self) -> dict[str, int]:
        with self._lock:
            return {"queue_depth": len(self._pending), "active": self._active_total}

    def start(self) -> None:
        with self._lock:
            if self._started:
                return
            self._pool = ThreadPoolExecutor(
                max_workers=self._policy.max_workers,
                thread_name_prefix="tool-worker",
            )
            self._started = True
            self._callback_stop.clear()
            self._callback_thread = threading.Thread(target=self._callback_loop, name="callback-outbox", daemon=True)
            self._callback_thread.start()

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
                    self._store.update_terminal_and_enqueue_callback(request, record)
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

    def submit(self, request: ToolExecutionRequest, *, schedule: bool = True) -> ToolExecutionRecord:
        self._registry.get(request.tool, request.version)
        with self._lock:
            existing = self._store.get_by_idempotency_key(request.idempotency_key)
            if existing is not None:
                persisted_request, record = existing
                self._requests[record.execution_id] = persisted_request
                self._records[record.execution_id] = record
                if schedule and self._started and record.status in (ExecutionStatus.QUEUED, ExecutionStatus.RUNNING):
                    self._schedule_locked(record.execution_id)
                return record

            execution_id = f"tex_{uuid4().hex}"
            record = ToolExecutionRecord(
                executionId=execution_id,
                idempotencyKey=request.idempotency_key,
                tool=request.tool,
                version=request.version,
                status=ExecutionStatus.QUEUED if schedule else ExecutionStatus.CLAIM_PENDING,
            )
            self._store.create(request, record)
            self._records[execution_id] = record
            self._requests[execution_id] = request
            if schedule and self._started:
                self._schedule_locked(execution_id)
            return record

    def dispatch(self, execution_id: str) -> ToolExecutionRecord:
        """Schedule a persisted execution after its external claim succeeds.

        Rabbit workers use this two-phase hand-off so a very short execution
        cannot callback before Control Plane has recorded the acceptance.
        """
        with self._lock:
            record = self._records.get(execution_id)
            if record is None:
                persisted = self._store.get(execution_id)
                if persisted is None:
                    raise KeyError(f"Tool execution not found: {execution_id}")
                request, record = persisted
                self._requests[execution_id] = request
                self._records[execution_id] = record
            if record.status == ExecutionStatus.CLAIM_PENDING:
                record = record.model_copy(update={"status": ExecutionStatus.QUEUED})
                self._store.update(record)
                self._records[execution_id] = record
            if self._started and record.status in (ExecutionStatus.QUEUED, ExecutionStatus.RUNNING):
                self._schedule_locked(execution_id)
            return record

    def reject_claim(self, execution_id: str) -> ToolExecutionRecord:
        """Make a stale, unclaimed Rabbit delivery terminal without executing it."""
        return self._update(
            execution_id,
            status=ExecutionStatus.CANCELLED,
            progress=100,
            completed_at=datetime.now(timezone.utc),
        )

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
        self._callback_stop.set()
        if self._callback_thread is not None:
            self._callback_thread.join(timeout=5)
            self._callback_thread = None

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

    def _governance(self, name: str, version: str) -> dict:
        tool = self._registry.get(name, version)
        governance = getattr(self._registry, "governance", None)
        return governance(name, version) if callable(governance) else normalize_manifest(tool.manifest())

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
                manifest = self._governance(request.tool, request.version)
                group = self._execution_groups.get(execution_id, LIGHT)
                local_request = artifact_storage.materialize_request(request)
                # Run the tool behind a bounded future. Python cannot forcibly
                # interrupt a native FFmpeg/model call, but the execution is
                # still terminalized at the governance deadline and no result
                # can be published after that point.
                call_pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="tool-call")
                future = call_pool.submit(
                    tool.execute,
                    local_request,
                    lambda progress: self._report_progress(execution_id, progress),
                )
                try:
                    outputs = future.result(timeout=float(manifest["timeoutSeconds"]))
                except FutureTimeoutError as exc:
                    future.cancel()
                    call_pool.shutdown(wait=False, cancel_futures=True)
                    raise ToolTimeoutError(
                        f"Tool exceeded governance timeout ({manifest['timeoutSeconds']}s): {request.tool}"
                    ) from exc
                else:
                    call_pool.shutdown(wait=True)
                outputs = artifact_storage.publish_outputs(outputs)
                record = self._update_terminal(
                    request,
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
                record = self._update_terminal(
                    request,
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

    def _update_terminal(self, request: ToolExecutionRequest, execution_id: str, **changes) -> ToolExecutionRecord:
        with self._lock:
            record = self._records.get(execution_id)
            if record is None:
                persisted = self._store.get(execution_id)
                if persisted is None:
                    raise KeyError(f"Tool execution not found: {execution_id}")
                _, record = persisted
            updated = record.model_copy(update=changes)
            self._store.update_terminal_and_enqueue_callback(request, updated)
            self._records[execution_id] = updated
            return updated

    def _callback_loop(self) -> None:
        while not self._callback_stop.is_set():
            try:
                self._publish_callbacks_once()
            except Exception:
                logger.exception("Callback outbox publisher failed")
            self._callback_stop.wait(max(0.2, settings.callback_publisher_interval_seconds))

    def _publish_callbacks_once(self) -> None:
        entries = self._store.list_due_callbacks()
        CALLBACK_PENDING.set(self._store.count_pending_callbacks())
        for entry in entries:
            try:
                with httpx.Client(timeout=settings.callback_timeout_seconds) as client:
                    client.post(entry.callback_url, content=entry.payload_json,
                                headers={"Content-Type": "application/json"}).raise_for_status()
                self._store.mark_callback_delivered(entry.execution_id)
                CALLBACK_TOTAL.labels("success").inc()
            except httpx.HTTPError as exc:
                delay = max(0.2, settings.callback_retry_backoff_seconds) * (2 ** min(entry.attempts, 8))
                self._store.mark_callback_failed(entry.execution_id, str(exc), datetime.now(timezone.utc) + timedelta(seconds=delay))
                CALLBACK_TOTAL.labels("failure").inc()


class ToolTimeoutError(TimeoutError):
    retryable = True


execution_service = ExecutionService()
