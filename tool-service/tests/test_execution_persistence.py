from __future__ import annotations

from threading import Event, Lock
from time import monotonic, sleep

import pytest

from app.core.models import (
    ArtifactDescriptor,
    ExecutionStatus,
    ToolExecutionRecord,
    ToolExecutionRequest,
)
from app.execution.service import ExecutionService
from app.execution.store import ExecutionStore


class FakeTool:
    name = "test.persist"
    version = "1.0.0"

    def __init__(self, *, error: Exception | None = None, gate: Event | None = None) -> None:
        self._error = error
        self._gate = gate
        self._lock = Lock()
        self.calls = 0

    def execute(self, request, report_progress=None):
        with self._lock:
            self.calls += 1
        if report_progress is not None:
            report_progress(50)
        if self._gate is not None:
            assert self._gate.wait(timeout=5)
        if self._error is not None:
            raise self._error
        return [
            ArtifactDescriptor(
                artifactId="artifact-output",
                type="TEST_OUTPUT",
                uri="artifact://artifact-output/result.json",
                mediaType="application/json",
                size=2,
                contentHash="0" * 64,
            )
        ]


class FakeRegistry:
    def __init__(self, tool: FakeTool) -> None:
        self._tool = tool

    def get(self, name: str, version: str) -> FakeTool:
        if (name, version) != (self._tool.name, self._tool.version):
            raise ValueError(f"Tool not found or disabled: {name}@{version}")
        return self._tool


def make_request(idempotency_key: str = "persist:test") -> ToolExecutionRequest:
    return ToolExecutionRequest(
        tool=FakeTool.name,
        version=FakeTool.version,
        idempotencyKey=idempotency_key,
        inputs={},
        parameters={"quality": "test"},
    )


def wait_for_status(
    service: ExecutionService,
    execution_id: str,
    expected: ExecutionStatus,
) -> ToolExecutionRecord:
    deadline = monotonic() + 5
    while monotonic() < deadline:
        record = service.get(execution_id)
        if record is not None and record.status == expected:
            return record
        sleep(0.01)
    record = service.get(execution_id)
    pytest.fail(f"Execution did not reach {expected}; last record was {record}")


def test_terminal_record_and_output_survive_service_restart(tmp_path) -> None:
    store_path = tmp_path / "executions.sqlite3"
    tool = FakeTool()
    first = ExecutionService(store_path, tool_registry=FakeRegistry(tool), max_workers=1)
    first.start()
    record = first.submit(make_request())
    succeeded = wait_for_status(first, record.execution_id, ExecutionStatus.SUCCEEDED)
    first.shutdown()

    second = ExecutionService(store_path, tool_registry=FakeRegistry(tool), max_workers=1)
    restored = second.get(record.execution_id)

    assert restored == succeeded
    assert restored is not None
    assert restored.outputs[0].artifact_id == "artifact-output"
    assert tool.calls == 1


def test_idempotency_key_returns_same_execution_after_restart(tmp_path) -> None:
    store_path = tmp_path / "executions.sqlite3"
    tool = FakeTool()
    first = ExecutionService(store_path, tool_registry=FakeRegistry(tool), max_workers=1)
    first.start()
    original = first.submit(make_request())
    wait_for_status(first, original.execution_id, ExecutionStatus.SUCCEEDED)
    first.shutdown()

    second = ExecutionService(store_path, tool_registry=FakeRegistry(tool), max_workers=1)
    duplicate = second.submit(make_request())

    assert duplicate.execution_id == original.execution_id
    assert duplicate.status == ExecutionStatus.SUCCEEDED
    assert tool.calls == 1


def test_unclaimed_rabbit_execution_does_not_run_after_restart(tmp_path) -> None:
    store_path = tmp_path / "executions.sqlite3"
    tool = FakeTool()
    first = ExecutionService(store_path, tool_registry=FakeRegistry(tool), max_workers=1)
    first.start()
    pending = first.submit(make_request("claim:pending"), schedule=False)
    first.shutdown()

    assert pending.status == ExecutionStatus.CLAIM_PENDING

    second = ExecutionService(store_path, tool_registry=FakeRegistry(tool), max_workers=1)
    second.start()
    sleep(0.05)
    restored = second.get(pending.execution_id)
    second.shutdown()

    assert restored is not None
    assert restored.status == ExecutionStatus.CLAIM_PENDING
    assert tool.calls == 0


def test_claimed_rabbit_execution_runs_only_after_dispatch(tmp_path) -> None:
    store_path = tmp_path / "executions.sqlite3"
    tool = FakeTool()
    service = ExecutionService(store_path, tool_registry=FakeRegistry(tool), max_workers=1)
    service.start()
    pending = service.submit(make_request("claim:accepted"), schedule=False)

    sleep(0.05)
    assert tool.calls == 0

    service.dispatch(pending.execution_id)
    succeeded = wait_for_status(service, pending.execution_id, ExecutionStatus.SUCCEEDED)
    service.shutdown()

    assert succeeded.status == ExecutionStatus.SUCCEEDED
    assert tool.calls == 1


@pytest.mark.parametrize("persisted_status", [ExecutionStatus.QUEUED, ExecutionStatus.RUNNING])
def test_incomplete_execution_resumes_with_same_id_after_restart(
    tmp_path,
    persisted_status: ExecutionStatus,
) -> None:
    store_path = tmp_path / "executions.sqlite3"
    request = make_request(f"recover:{persisted_status.value}")
    record = ToolExecutionRecord(
        executionId=f"tex-recover-{persisted_status.value.lower()}",
        idempotencyKey=request.idempotency_key,
        tool=request.tool,
        version=request.version,
        status=persisted_status,
        progress=73,
    )
    ExecutionStore(store_path).create(request, record)
    tool = FakeTool()
    service = ExecutionService(store_path, tool_registry=FakeRegistry(tool), max_workers=1)

    service.start()
    recovered = wait_for_status(service, record.execution_id, ExecutionStatus.SUCCEEDED)
    service.shutdown()

    assert recovered.execution_id == record.execution_id
    assert recovered.progress == 100
    assert tool.calls == 1


def test_failed_record_and_error_survive_service_restart(tmp_path) -> None:
    store_path = tmp_path / "executions.sqlite3"
    tool = FakeTool(error=RuntimeError("temporary test failure"))
    first = ExecutionService(store_path, tool_registry=FakeRegistry(tool), max_workers=1)
    first.start()
    submitted = first.submit(make_request("failure:test"))
    failed = wait_for_status(first, submitted.execution_id, ExecutionStatus.FAILED)
    first.shutdown()

    second = ExecutionService(store_path, tool_registry=FakeRegistry(tool), max_workers=1)
    restored = second.get(submitted.execution_id)

    assert restored == failed
    assert restored is not None
    assert restored.error is not None
    assert restored.error.message == "temporary test failure"
    assert restored.error.retryable is True
    assert tool.calls == 1


def test_repeated_submit_does_not_schedule_duplicate_execution(tmp_path) -> None:
    store_path = tmp_path / "executions.sqlite3"
    gate = Event()
    tool = FakeTool(gate=gate)
    service = ExecutionService(store_path, tool_registry=FakeRegistry(tool), max_workers=2)
    service.start()

    try:
        first = service.submit(make_request("duplicate:test"))
        wait_for_status(service, first.execution_id, ExecutionStatus.RUNNING)
        second = service.submit(make_request("duplicate:test"))
        assert second.execution_id == first.execution_id
        sleep(0.05)
        assert tool.calls == 1
    finally:
        gate.set()

    wait_for_status(service, first.execution_id, ExecutionStatus.SUCCEEDED)
    service.shutdown()


def test_repeated_process_loss_marks_execution_retryable_failed(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr("app.execution.service.settings.execution_max_recoveries", 1)
    store_path = tmp_path / "executions.sqlite3"
    request = make_request("recover:exhausted")
    record = ToolExecutionRecord(
        executionId="tex-recover-exhausted",
        idempotencyKey=request.idempotency_key,
        tool=request.tool,
        version=request.version,
        status=ExecutionStatus.RUNNING,
        progress=40,
        recoveryCount=1,
    )
    ExecutionStore(store_path).create(request, record)
    tool = FakeTool()
    service = ExecutionService(store_path, tool_registry=FakeRegistry(tool), max_workers=1)

    service.start()
    failed = wait_for_status(service, record.execution_id, ExecutionStatus.FAILED)
    service.shutdown()

    assert failed.error is not None
    assert failed.error.code == "EXECUTION_RECOVERY_EXHAUSTED"
    assert failed.error.retryable is True
    assert failed.recovery_count == 2
    assert tool.calls == 0
