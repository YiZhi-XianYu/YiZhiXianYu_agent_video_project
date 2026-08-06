from __future__ import annotations

import sqlite3
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from threading import Lock

from app.core.models import ExecutionStatus, ToolExecutionRecord, ToolExecutionRequest


@dataclass(frozen=True)
class CallbackOutboxEntry:
    execution_id: str
    callback_url: str
    payload_json: str
    attempts: int


class ExecutionStore:
    """Durable Tool Execution request/record store backed by SQLite."""

    def __init__(self, path: Path) -> None:
        self._path = path
        self._init_lock = Lock()
        self._initialized = False

    def create(self, request: ToolExecutionRequest, record: ToolExecutionRecord) -> None:
        self._ensure_initialized()
        with self._connect() as connection:
            connection.execute(
                """
                INSERT INTO tool_executions (
                    execution_id, idempotency_key, status, request_json, record_json, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                (
                    record.execution_id,
                    record.idempotency_key,
                    record.status.value,
                    request.model_dump_json(by_alias=True),
                    record.model_dump_json(by_alias=True),
                    _utc_timestamp(),
                ),
            )

    def update(self, record: ToolExecutionRecord) -> None:
        self._ensure_initialized()
        with self._connect() as connection:
            cursor = connection.execute(
                """
                UPDATE tool_executions
                SET status = ?, record_json = ?, updated_at = ?
                WHERE execution_id = ?
                """,
                (
                    record.status.value,
                    record.model_dump_json(by_alias=True),
                    _utc_timestamp(),
                    record.execution_id,
                ),
            )
            if cursor.rowcount != 1:
                raise KeyError(f"Tool execution not found: {record.execution_id}")

    def update_terminal_and_enqueue_callback(
        self,
        request: ToolExecutionRequest,
        record: ToolExecutionRecord,
    ) -> None:
        """Atomically persist a terminal execution and its callback delivery."""
        self._ensure_initialized()
        now = _utc_timestamp()
        with self._connect() as connection:
            cursor = connection.execute(
                """
                UPDATE tool_executions
                SET status = ?, record_json = ?, updated_at = ?
                WHERE execution_id = ?
                """,
                (record.status.value, record.model_dump_json(by_alias=True), now, record.execution_id),
            )
            if cursor.rowcount != 1:
                raise KeyError(f"Tool execution not found: {record.execution_id}")
            if request.callback_url is not None:
                connection.execute(
                    """
                    INSERT INTO callback_outbox (
                        execution_id, callback_url, payload_json, status, attempts,
                        next_attempt_at, last_error, delivered_at, created_at, updated_at
                    ) VALUES (?, ?, ?, 'PENDING', 0, ?, NULL, NULL, ?, ?)
                    ON CONFLICT(execution_id) DO UPDATE SET
                        callback_url = excluded.callback_url,
                        payload_json = excluded.payload_json,
                        status = CASE WHEN callback_outbox.status = 'DELIVERED' THEN 'DELIVERED' ELSE 'PENDING' END,
                        next_attempt_at = CASE WHEN callback_outbox.status = 'DELIVERED' THEN callback_outbox.next_attempt_at ELSE excluded.next_attempt_at END,
                        last_error = CASE WHEN callback_outbox.status = 'DELIVERED' THEN callback_outbox.last_error ELSE NULL END,
                        updated_at = excluded.updated_at
                    """,
                    (
                        record.execution_id,
                        str(request.callback_url),
                        record.model_dump_json(by_alias=True),
                        now,
                        now,
                        now,
                    ),
                )

    def list_due_callbacks(self, limit: int = 20) -> list[CallbackOutboxEntry]:
        self._ensure_initialized()
        with self._connect() as connection:
            rows = connection.execute(
                """
                SELECT execution_id, callback_url, payload_json, attempts
                FROM callback_outbox
                WHERE status = 'PENDING' AND next_attempt_at <= ?
                ORDER BY next_attempt_at ASC, created_at ASC
                LIMIT ?
                """,
                (_utc_timestamp(), max(1, limit)),
            ).fetchall()
        return [CallbackOutboxEntry(str(row[0]), str(row[1]), str(row[2]), int(row[3])) for row in rows]

    def mark_callback_delivered(self, execution_id: str) -> None:
        now = _utc_timestamp()
        with self._connect() as connection:
            connection.execute(
                """
                UPDATE callback_outbox
                SET status = 'DELIVERED', delivered_at = ?, last_error = NULL, updated_at = ?
                WHERE execution_id = ?
                """,
                (now, now, execution_id),
            )

    def mark_callback_failed(self, execution_id: str, error: str, retry_at: datetime) -> None:
        with self._connect() as connection:
            connection.execute(
                """
                UPDATE callback_outbox
                SET attempts = attempts + 1, next_attempt_at = ?, last_error = ?, updated_at = ?
                WHERE execution_id = ? AND status = 'PENDING'
                """,
                (retry_at.astimezone(timezone.utc).isoformat(), error[:2000], _utc_timestamp(), execution_id),
            )

    def count_pending_callbacks(self) -> int:
        self._ensure_initialized()
        with self._connect() as connection:
            row = connection.execute("SELECT COUNT(*) FROM callback_outbox WHERE status = 'PENDING'").fetchone()
        return int(row[0])

    def get(self, execution_id: str) -> tuple[ToolExecutionRequest, ToolExecutionRecord] | None:
        return self._get_one("execution_id = ?", (execution_id,))

    def get_by_idempotency_key(
        self, idempotency_key: str,
    ) -> tuple[ToolExecutionRequest, ToolExecutionRecord] | None:
        return self._get_one("idempotency_key = ?", (idempotency_key,))

    def list_recoverable(self) -> list[tuple[ToolExecutionRequest, ToolExecutionRecord]]:
        self._ensure_initialized()
        with self._connect() as connection:
            rows = connection.execute(
                """
                SELECT request_json, record_json
                FROM tool_executions
                WHERE status IN (?, ?)
                ORDER BY updated_at ASC
                """,
                (ExecutionStatus.QUEUED.value, ExecutionStatus.RUNNING.value),
            ).fetchall()
        return [_deserialize(row) for row in rows]

    def count(self) -> int:
        self._ensure_initialized()
        with self._connect() as connection:
            row = connection.execute("SELECT COUNT(*) FROM tool_executions").fetchone()
        return int(row[0])

    def _get_one(
        self, where_clause: str, parameters: tuple[str, ...],
    ) -> tuple[ToolExecutionRequest, ToolExecutionRecord] | None:
        self._ensure_initialized()
        with self._connect() as connection:
            row = connection.execute(
                f"SELECT request_json, record_json FROM tool_executions WHERE {where_clause}",
                parameters,
            ).fetchone()
        return None if row is None else _deserialize(row)

    def _ensure_initialized(self) -> None:
        if self._initialized:
            return
        with self._init_lock:
            if self._initialized:
                return
            self._path.parent.mkdir(parents=True, exist_ok=True)
            with self._connect(initialize=False) as connection:
                connection.execute("PRAGMA journal_mode=WAL")
                connection.execute("PRAGMA synchronous=FULL")
                connection.execute(
                    """
                    CREATE TABLE IF NOT EXISTS tool_executions (
                        execution_id TEXT PRIMARY KEY,
                        idempotency_key TEXT NOT NULL UNIQUE,
                        status TEXT NOT NULL,
                        request_json TEXT NOT NULL,
                        record_json TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """
                )
                connection.execute(
                    "CREATE INDEX IF NOT EXISTS idx_tool_executions_status ON tool_executions(status)"
                )
                connection.execute(
                    """
                    CREATE TABLE IF NOT EXISTS callback_outbox (
                        execution_id TEXT PRIMARY KEY,
                        callback_url TEXT NOT NULL,
                        payload_json TEXT NOT NULL,
                        status TEXT NOT NULL,
                        attempts INTEGER NOT NULL DEFAULT 0,
                        next_attempt_at TEXT NOT NULL,
                        last_error TEXT NULL,
                        delivered_at TEXT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        FOREIGN KEY(execution_id) REFERENCES tool_executions(execution_id)
                    )
                    """
                )
                connection.execute(
                    "CREATE INDEX IF NOT EXISTS idx_callback_outbox_due ON callback_outbox(status, next_attempt_at)"
                )
            self._initialized = True

    def _connect(self, *, initialize: bool = True) -> sqlite3.Connection:
        if initialize:
            self._ensure_initialized()
        connection = sqlite3.connect(self._path, timeout=30)
        connection.execute("PRAGMA busy_timeout=30000")
        connection.execute("PRAGMA synchronous=FULL")
        return connection


def _deserialize(row: sqlite3.Row | tuple[str, str]) -> tuple[ToolExecutionRequest, ToolExecutionRecord]:
    return (
        ToolExecutionRequest.model_validate_json(row[0]),
        ToolExecutionRecord.model_validate_json(row[1]),
    )


def _utc_timestamp() -> str:
    return datetime.now(timezone.utc).isoformat()
