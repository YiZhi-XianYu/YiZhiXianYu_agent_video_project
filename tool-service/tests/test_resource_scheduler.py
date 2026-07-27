from __future__ import annotations

from threading import Event, Lock
from time import monotonic, sleep

import pytest

from app.core.models import ExecutionStatus, ToolExecutionRequest
from app.execution.resources import LIGHT, MEDIA, MODEL, RENDER
from app.execution.service import ExecutionService


class GatedTool:
    version = "1.0.0"

    def __init__(self, name: str, group: str, gate: Event) -> None:
        self.name = name
        self.group = group
        self.gate = gate
        self.started = Event()
        self._lock = Lock()
        self.calls = 0

    def manifest(self) -> dict:
        return {"name": self.name, "version": self.version, "resourceGroup": self.group}

    def execute(self, request, report_progress=None):
        with self._lock:
            self.calls += 1
        self.started.set()
        assert self.gate.wait(timeout=5)
        return []


class MultiRegistry:
    def __init__(self, *tools: GatedTool) -> None:
        self.tools = {(tool.name, tool.version): tool for tool in tools}

    def get(self, name: str, version: str) -> GatedTool:
        return self.tools[(name, version)]


def request(tool: GatedTool, key: str) -> ToolExecutionRequest:
    return ToolExecutionRequest(
        tool=tool.name,
        version=tool.version,
        idempotencyKey=key,
        inputs={},
    )


def wait_for_status(service: ExecutionService, execution_id: str, status: ExecutionStatus):
    deadline = monotonic() + 5
    while monotonic() < deadline:
        record = service.get(execution_id)
        if record is not None and record.status == status:
            return record
        sleep(0.01)
    pytest.fail(f"Execution {execution_id} did not reach {status}")


def test_model_tasks_are_serialized_while_light_task_runs_concurrently(tmp_path) -> None:
    first_gate = Event()
    second_gate = Event()
    light_gate = Event()
    model_one = GatedTool("test.model-one", MODEL, first_gate)
    model_two = GatedTool("test.model-two", MODEL, second_gate)
    light = GatedTool("test.light", LIGHT, light_gate)
    service = ExecutionService(
        tmp_path / "executions.sqlite3",
        tool_registry=MultiRegistry(model_one, model_two, light),
        max_workers=3,
        resource_limits={MODEL: 1, LIGHT: 2, RENDER: 1},
        heavy_limit=2,
    )
    service.start()

    first = service.submit(request(model_one, "model-one"))
    second = service.submit(request(model_two, "model-two"))
    light_record = service.submit(request(light, "light"))

    assert model_one.started.wait(timeout=2)
    assert light.started.wait(timeout=2)
    sleep(0.05)
    assert model_two.started.is_set() is False
    assert service.get(second.execution_id).status == ExecutionStatus.QUEUED

    first_gate.set()
    wait_for_status(service, first.execution_id, ExecutionStatus.SUCCEEDED)
    assert model_two.started.wait(timeout=2)

    second_gate.set()
    light_gate.set()
    wait_for_status(service, second.execution_id, ExecutionStatus.SUCCEEDED)
    wait_for_status(service, light_record.execution_id, ExecutionStatus.SUCCEEDED)
    service.shutdown()


def test_model_and_render_share_heavy_capacity(tmp_path) -> None:
    model_gate = Event()
    render_gate = Event()
    model = GatedTool("test.model", MODEL, model_gate)
    render = GatedTool("test.render", RENDER, render_gate)
    service = ExecutionService(
        tmp_path / "executions.sqlite3",
        tool_registry=MultiRegistry(model, render),
        max_workers=2,
        resource_limits={MODEL: 1, RENDER: 1},
        heavy_limit=2,
    )
    service.start()

    model_record = service.submit(request(model, "model"))
    render_record = service.submit(request(render, "render"))
    assert model.started.wait(timeout=2)
    sleep(0.05)
    assert render.started.is_set() is False
    assert service.get(render_record.execution_id).status == ExecutionStatus.QUEUED

    model_gate.set()
    wait_for_status(service, model_record.execution_id, ExecutionStatus.SUCCEEDED)
    assert render.started.wait(timeout=2)
    render_gate.set()
    wait_for_status(service, render_record.execution_id, ExecutionStatus.SUCCEEDED)
    service.shutdown()


def test_two_media_tasks_can_run_but_model_waits_for_capacity(tmp_path) -> None:
    media_one_gate = Event()
    media_two_gate = Event()
    model_gate = Event()
    media_one = GatedTool("test.media-one", MEDIA, media_one_gate)
    media_two = GatedTool("test.media-two", MEDIA, media_two_gate)
    model = GatedTool("test.model-after-media", MODEL, model_gate)
    service = ExecutionService(
        tmp_path / "executions.sqlite3",
        tool_registry=MultiRegistry(media_one, media_two, model),
        max_workers=3,
        resource_limits={MEDIA: 2, MODEL: 1},
        heavy_limit=2,
    )
    service.start()

    first = service.submit(request(media_one, "media-one"))
    second = service.submit(request(media_two, "media-two"))
    model_record = service.submit(request(model, "model-after-media"))
    assert media_one.started.wait(timeout=2)
    assert media_two.started.wait(timeout=2)
    sleep(0.05)
    assert model.started.is_set() is False

    media_one_gate.set()
    media_two_gate.set()
    wait_for_status(service, first.execution_id, ExecutionStatus.SUCCEEDED)
    wait_for_status(service, second.execution_id, ExecutionStatus.SUCCEEDED)
    assert model.started.wait(timeout=2)
    model_gate.set()
    wait_for_status(service, model_record.execution_id, ExecutionStatus.SUCCEEDED)
    service.shutdown()
