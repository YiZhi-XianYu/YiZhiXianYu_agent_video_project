from types import SimpleNamespace
import importlib.util
import pytest

from app.agent import chuxue_graph
from app.api import routes
from app.api.routes import ChuxueChatRequest, ChuxueChatResponse


class FakeGraph:
    def invoke(self, state):
        state.update({"stage": "PREPARED", "error": None})
        state["response"] = state["decide"](state["request"])
        state["stage"] = "FINALIZED"
        return state


def test_graph_runs_the_existing_controlled_decision_contract(monkeypatch):
    monkeypatch.setattr(chuxue_graph, "_build_graph", lambda: FakeGraph())
    expected = ChuxueChatResponse(reply="ready", shouldPlan=False, modelRoute={}, llmUsed=True)

    response = chuxue_graph.run(ChuxueChatRequest(message="hello"), lambda request: expected)

    assert response is expected


def test_real_graph_marks_runtime_and_stage_trace():
    if importlib.util.find_spec("langgraph") is None:
        pytest.skip("LangGraph is provided by the Docker runtime; local conda env keeps it optional")
    expected = ChuxueChatResponse(reply="ready", shouldPlan=False, modelRoute={"provider": "test"}, llmUsed=True)
    response = chuxue_graph.run(ChuxueChatRequest(message="hello"), lambda request: expected)
    assert response.modelRoute["agentRuntime"] == "LANGGRAPH"
    assert response.modelRoute["graphStages"] == [
        "prepare_context", "merge_constraints", "classify_request", "decide",
        "validate_proposal", "finalize",
    ]
    assert response.modelRoute["decisionClass"] == "CHAT"


def test_request_classification_is_deterministic():
    assert chuxue_graph._classify_request("你好", {}, {}) == "CHAT"
    assert chuxue_graph._classify_request("我想剪一个20秒旅行视频", {}, {"targetDurationMs": 20000}) == "PLAN"
    assert chuxue_graph._classify_request("我想剪视频，帮我想想计划", {}, {}) == "CLARIFICATION"
    assert chuxue_graph._classify_request("现在做到哪一步了？", {"workflowActive": True}, {}) == "ACTIVE_WORKFLOW_STATUS"


def test_constraints_extract_duration_and_preferences():
    result = chuxue_graph._extract_constraints("做一个20秒视频，不要字幕，加背景音乐", {})
    assert result == {"targetDurationMs": 20000, "subtitles": False, "bgm": True}


def test_latest_explicit_constraints_override_context_snapshot():
    result = chuxue_graph._extract_constraints(
        "改成20秒，不要字幕",
        {"targetDurationMs": 30000, "subtitles": True, "bgm": True},
    )
    assert result == {"targetDurationMs": 20000, "subtitles": False, "bgm": True}


def test_graph_rejects_invalid_plan_proposal():
    if importlib.util.find_spec("langgraph") is None:
        pytest.skip("LangGraph is provided by the Docker runtime; local conda env keeps it optional")
    invalid = ChuxueChatResponse(reply="plan", shouldPlan=True, planningGoal=None, modelRoute={}, llmUsed=True)
    with pytest.raises(RuntimeError, match="planningGoal"):
        chuxue_graph.run(ChuxueChatRequest(message="剪视频"), lambda request: invalid)


def test_chat_feature_flag_keeps_legacy_path(monkeypatch):
    expected = ChuxueChatResponse(reply="legacy", shouldPlan=False, modelRoute={}, llmUsed=True)
    monkeypatch.setattr(routes.settings, "chuxue_graph_enabled", False)
    monkeypatch.setattr(routes, "_chuxue_chat_legacy_decision", lambda request: expected)

    assert routes.chuxue_chat_v2(ChuxueChatRequest(message="hello")) is expected


def test_graph_failure_falls_back_to_legacy_path(monkeypatch):
    expected = ChuxueChatResponse(reply="legacy", shouldPlan=False, modelRoute={}, llmUsed=True)
    monkeypatch.setattr(routes.settings, "chuxue_graph_enabled", True)
    monkeypatch.setattr(routes, "_chuxue_chat_legacy_decision", lambda request: expected)
    monkeypatch.setitem(__import__("sys").modules, "app.agent.chuxue_graph", SimpleNamespace(
        run=lambda request, decide: (_ for _ in ()).throw(RuntimeError("graph down"))
    ))

    assert routes.chuxue_chat_v2(ChuxueChatRequest(message="hello")) is expected
