from __future__ import annotations

import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from threading import Lock

from app.core.models import ExecutionStatus, ToolExecutionRecord, ToolExecutionRequest


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
