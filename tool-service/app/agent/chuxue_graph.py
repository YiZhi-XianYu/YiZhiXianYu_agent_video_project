from __future__ import annotations

import re
from typing import Any, Callable, TypedDict


class ChuxueGraphState(TypedDict, total=False):
    request: Any
    decide: Callable[[Any], Any]
    response: Any
    context: dict[str, Any]
    constraints: dict[str, Any]
    decision_class: str
    stage: str
    graph_stages: list[str]
    error: str | None


_VIDEO_WORDS = ("视频", "剪辑", "剪片", "成片", "素材", "镜头", "工作流", "workflow", "vlog")
_PLAN_WORDS = ("帮我", "制作", "剪", "生成", "做一个", "再做", "重新", "开启", "开始工作", "执行", "计划")
_CLARIFY_WORDS = ("怎么", "如何", "想法", "规划", "计划", "需要什么", "帮我想")


def _latest_message(request: Any) -> str:
    return str(getattr(request, "message", "") or "").strip()


def _extract_constraints(message: str, context: dict[str, Any]) -> dict[str, Any]:
    """Extract only deterministic, high-confidence constraints.

    This is deliberately advisory metadata for the LLM/control plane; it never
    creates a tool, workflow, or execution side effect.
    """
    constraints: dict[str, Any] = {
        key: context[key]
        for key in ("targetDurationMs", "subtitles", "bgm")
        if key in context and context[key] is not None
    }
    duration = re.search(r"(\d+(?:\.\d+)?)\s*(秒|分钟|minutes?|mins?|seconds?|secs?)", message, re.I)
    if duration:
        value = float(duration.group(1))
        unit = duration.group(2).lower()
        millis = int(value * (60000 if unit in {"分钟", "minutes", "mins"} else 1000))
        constraints["targetDurationMs"] = max(5000, min(300000, millis))
    subtitle_off = any(word in message.lower() for word in ("不要字幕", "不加字幕", "无字幕", "without subtitles", "no subtitles"))
    subtitle_on = any(word in message.lower() for word in ("加字幕", "需要字幕", "带字幕", "with subtitles", "subtitles"))
    if subtitle_off:
        constraints["subtitles"] = False
    elif subtitle_on:
        constraints["subtitles"] = True
    bgm_off = any(word in message.lower() for word in ("不要音乐", "不要bgm", "不需要bgm", "拒绝bgm", "no bgm", "without music"))
    bgm_on = any(word in message.lower() for word in ("配乐", "背景音乐", "bgm", "音乐", "music"))
    if bgm_off:
        constraints["bgm"] = False
    elif bgm_on:
        constraints["bgm"] = True
    # Java supplies the current baseline, while an explicit constraint in the
    # latest user turn is a correction that must win over an older snapshot.
    return constraints


def _classify_request(message: str, context: dict[str, Any], constraints: dict[str, Any]) -> str:
    if bool(context.get("workflowActive")):
        return "ACTIVE_WORKFLOW_STATUS"
    text = message.lower()
    is_video = any(word in text for word in _VIDEO_WORDS)
    is_plan = any(word in text for word in _PLAN_WORDS)
    is_clarification = any(word in text for word in _CLARIFY_WORDS)
    # A concrete duration or explicit production action is enough to enter the
    # proposal path; vague ideation remains a clarification conversation.
    if is_video and (constraints.get("targetDurationMs") or (is_plan and not is_clarification)):
        return "PLAN"
    if is_video and (is_clarification or is_plan):
        return "CLARIFICATION"
    return "CHAT"


def _build_graph():
    from langgraph.graph import END, START, StateGraph

    graph = StateGraph(ChuxueGraphState)

    def prepare_context(state: ChuxueGraphState) -> ChuxueGraphState:
        request = state["request"]
        context = dict(getattr(request, "context", None) or {})
        return {"context": context, "stage": "CONTEXT_PREPARED", "graph_stages": ["prepare_context"], "error": None}

    def merge_constraints(state: ChuxueGraphState) -> ChuxueGraphState:
        constraints = _extract_constraints(_latest_message(state["request"]), state.get("context", {}))
        return {"constraints": constraints, "stage": "CONSTRAINTS_MERGED", "graph_stages": [*state.get("graph_stages", []), "merge_constraints"]}

    def classify_request(state: ChuxueGraphState) -> ChuxueGraphState:
        decision_class = _classify_request(_latest_message(state["request"]), state.get("context", {}), state.get("constraints", {}))
        return {"decision_class": decision_class, "stage": "REQUEST_CLASSIFIED", "graph_stages": [*state.get("graph_stages", []), "classify_request"]}

    def decide(state: ChuxueGraphState) -> ChuxueGraphState:
        try:
            request = state["request"]
            context = dict(state.get("context", {}))
            context["graphDecisionClass"] = state.get("decision_class", "CHAT")
            context["graphConstraints"] = state.get("constraints", {})
            if hasattr(request, "model_copy"):
                request = request.model_copy(update={"context": context})
            response = state["decide"](request)
            return {"response": response, "stage": "DECIDED", "graph_stages": [*state.get("graph_stages", []), "decide"]}
        except Exception as exc:
            return {"response": None, "stage": "FAILED", "error": str(exc), "graph_stages": [*state.get("graph_stages", []), "decide"]}

    def validate_proposal(state: ChuxueGraphState) -> ChuxueGraphState:
        response = state.get("response")
        if response is None:
            return {"stage": "FAILED", "error": state.get("error") or "empty response", "graph_stages": [*state.get("graph_stages", []), "validate_proposal"]}
        should_plan = bool(getattr(response, "shouldPlan", False))
        if should_plan and bool(state.get("context", {}).get("workflowActive")):
            raise ValueError("active Workflow cannot create a new proposal")
        if should_plan and not str(getattr(response, "planningGoal", "") or "").strip():
            raise ValueError("planningGoal is required when shouldPlan=true")
        duration = getattr(response, "targetDurationMs", None)
        if duration is not None and (not isinstance(duration, int) or not 5000 <= duration <= 300000):
            raise ValueError("targetDurationMs is outside the supported range")
        return {"stage": "PROPOSAL_VALIDATED", "graph_stages": [*state.get("graph_stages", []), "validate_proposal"]}

    def finalize(state: ChuxueGraphState) -> ChuxueGraphState:
        response = state.get("response")
        stages = [*state.get("graph_stages", []), "finalize"]
        if response is not None and hasattr(response, "model_copy") and hasattr(response, "modelRoute"):
            route = dict(response.modelRoute or {})
            route["agentRuntime"] = "LANGGRAPH"
            route["graphStages"] = stages
            route["decisionClass"] = state.get("decision_class", "CHAT")
            response = response.model_copy(update={"modelRoute": route})
        return {"response": response, "stage": "FINALIZED", "graph_stages": stages}

    for name, fn in (("prepare_context", prepare_context), ("merge_constraints", merge_constraints),
                     ("classify_request", classify_request), ("decide", decide),
                     ("validate_proposal", validate_proposal), ("finalize", finalize)):
        graph.add_node(name, fn)
    graph.add_edge(START, "prepare_context")
    graph.add_edge("prepare_context", "merge_constraints")
    graph.add_edge("merge_constraints", "classify_request")
    graph.add_edge("classify_request", "decide")
    graph.add_edge("decide", "validate_proposal")
    graph.add_edge("validate_proposal", "finalize")
    graph.add_edge("finalize", END)
    return graph.compile()


def run(request: Any, decide: Callable[[Any], Any]) -> Any:
    """Run the bounded Chuxue reasoning graph; Java remains authoritative."""
    state = _build_graph().invoke({"request": request, "decide": decide})
    if state.get("response") is None:
        raise RuntimeError(state.get("error") or "Chuxue decision graph failed")
    return state["response"]
