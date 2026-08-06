from fastapi import APIRouter, HTTPException, status
from pydantic import BaseModel, Field
from typing import Any
import re
import uuid

from app.llm.provider import LlmError, generate_json_with_fallback
from app.core.models import AcceptedExecution, ToolExecutionRecord, ToolExecutionRequest
from app.execution.service import execution_service
from app.registry.registry import registry
from app.llm.router import model_router, provider_health, ROUTE_CALLS, ROUTE_LATENCY, ROUTE_TOKENS
from prometheus_client import generate_latest, CONTENT_TYPE_LATEST
from fastapi.responses import Response

router = APIRouter(prefix="/api/v1")

@router.get("/metrics", include_in_schema=False)
def metrics() -> Response:
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)

class WorkflowIntentRequest(BaseModel):
    goal: str = ""
    targetDuration: str | None = None
    targetDurationMs: int | None = Field(default=None, ge=5000, le=300000)
    assetCount: int = Field(default=1, ge=1, le=20)
    availableCapabilities: list[str] = Field(default_factory=list)

class WorkflowIntentResponse(BaseModel):
    llmUsed: bool
    capabilities: dict[str, str]
    pacing: str
    explanation: str
    targetDurationMs: int
    modelRoute: dict[str, Any]

def _parse_duration_ms(text: str) -> int:
    minute = re.search(r"(\d+(?:\.\d+)?)\s*(?:分钟|分|minutes?|mins?)", text or "", re.I)
    second = re.search(r"(\d+(?:\.\d+)?)\s*(?:秒|秒钟|seconds?|secs?)", text or "", re.I)
    if minute:
        return max(5000, min(300000, int(float(minute.group(1)) * 60000)))
    if second:
        return max(5000, min(300000, int(float(second.group(1)) * 1000)))
    if re.search(r"半分钟|半分", text or ""):
        return 30000
    return 30000


def _resolve_duration_ms(request: WorkflowIntentRequest) -> int:
    """Resolve duration using the explicit contract before natural language.

    Agent callers commonly provide a structured ``targetDurationMs`` or
    ``targetDuration`` separately from the natural-language goal.  Keeping the
    precedence deterministic prevents the planner from silently changing a
    user's requested duration back to the 30-second default.
    """
    if request.targetDurationMs is not None:
        return request.targetDurationMs
    if request.targetDuration:
        parsed = _parse_duration_ms(request.targetDuration)
        # An unparseable value returns the default; let goal parsing have a
        # chance before falling back to the system default.
        if parsed != 30000 or re.search(r"30\s*(?:秒|秒钟|seconds?|secs?|分钟|分|minutes?|mins?)", request.targetDuration, re.I):
            return parsed
    return _parse_duration_ms(request.goal)

@router.post("/workflow-planning/intent", response_model=WorkflowIntentResponse)
def workflow_intent(request: WorkflowIntentRequest) -> WorkflowIntentResponse:
    allowed = {"sourceTranscription", "subtitles", "bgm", "vlmAnalysis"}
    defaults = {"vlmAnalysis": "REQUIRED", "sourceTranscription": "OPTIONAL", "subtitles": "OPTIONAL", "bgm": "OPTIONAL"}
    duration_ms = _resolve_duration_ms(request)
    model_route = model_router.route("STRUCTURED_INTENT", request_id=uuid.uuid4().hex[:12]).to_dict()
    if not model_route["available"]:
        return WorkflowIntentResponse(llmUsed=False, capabilities=defaults, pacing="BALANCED", explanation="未配置 LLM，已使用系统默认流程图", targetDurationMs=duration_ms, modelRoute=model_route)
    system = (
        "你是视频工作流规划助手。根据用户自然语言需求，只能返回受控能力意图和时长。"
        "不得生成工具名、版本、命令、路径或任意连线。vlmAnalysis 必须为 REQUIRED。"
        "字幕编排必须依赖源音频转写；如果 sourceTranscription 为 DISABLED，subtitles 必须为 DISABLED。"
        "输出 JSON：capabilities 每项只能是 REQUIRED、OPTIONAL、DISABLED；pacing 只能 FAST、BALANCED、SLOW；"
        "targetDurationMs 必须是 5000 到 300000 之间的整数。"
    )
    user = {"goal": request.goal.strip(), "targetDurationMs": duration_ms, "assetCount": request.assetCount, "availableCapabilities": sorted(allowed.intersection(request.availableCapabilities or allowed))}
    schema: dict[str, Any] = {"title": "workflow_intent", "type": "object", "additionalProperties": False, "properties": {
        "capabilities": {"type": "object", "additionalProperties": {"type": "string", "enum": ["REQUIRED", "OPTIONAL", "DISABLED"]}},
        "pacing": {"type": "string", "enum": ["FAST", "BALANCED", "SLOW"]}, "explanation": {"type": "string"},
        "targetDurationMs": {"type": "integer", "minimum": 5000, "maximum": 300000}},
        "required": ["capabilities", "pacing", "explanation", "targetDurationMs"]}
    try:
        result, model_route, _provider = generate_json_with_fallback("STRUCTURED_INTENT", system, str(user), schema, temperature=0.2, max_tokens=512, request_id=uuid.uuid4().hex[:12])
        caps = dict(defaults)
        for key, value in (result.get("capabilities") or {}).items():
            if key in allowed and value in {"REQUIRED", "OPTIONAL", "DISABLED"}:
                caps[key] = value
        caps["vlmAnalysis"] = "REQUIRED"
        if caps.get("sourceTranscription") == "DISABLED":
            caps["subtitles"] = "DISABLED"
        pacing = result.get("pacing") if result.get("pacing") in {"FAST", "BALANCED", "SLOW"} else "BALANCED"
        model_duration = result.get("targetDurationMs")
        if not isinstance(model_duration, int) or not 5000 <= model_duration <= 300000:
            model_duration = duration_ms
        return WorkflowIntentResponse(llmUsed=True, capabilities=caps, pacing=pacing, explanation=str(result.get("explanation") or "已根据自然语言需求生成候选流程图"), targetDurationMs=model_duration, modelRoute=model_route)
    except (LlmError, Exception):
        failed_route = dict(model_route)
        failed_route["fallbackReason"] = "MODEL_CALL_FAILED"
        return WorkflowIntentResponse(llmUsed=False, capabilities=defaults, pacing="BALANCED", explanation="LLM 暂不可用，已回退到系统默认流程图", targetDurationMs=duration_ms, modelRoute=failed_route)

@router.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP"}

@router.get("/tools")
def list_tools() -> list[dict]:
    return registry.manifests()

@router.get("/model-routes")
def list_model_routes() -> list[dict]:
    return [model_router.route(capability).to_dict() for capability in (
        "STRUCTURED_INTENT", "STORY_PLAN", "SHOT_SEMANTICS", "LONG_AUDIO_TRANSCRIPTION"
    )]

@router.get("/model-provider-health")
def model_provider_health() -> dict:
    return provider_health.snapshot()

@router.post("/tool-executions", response_model=AcceptedExecution, response_model_by_alias=True, status_code=status.HTTP_202_ACCEPTED)
def create_execution(request: ToolExecutionRequest) -> AcceptedExecution:
    try:
        record = execution_service.submit(request)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return AcceptedExecution(executionId=record.execution_id, status=record.status, statusUrl=f"/api/v1/tool-executions/{record.execution_id}")

@router.get("/tool-executions/{execution_id}", response_model=ToolExecutionRecord, response_model_by_alias=True)
def get_execution(execution_id: str) -> ToolExecutionRecord:
    record = execution_service.get(execution_id)
    if record is None:
        raise HTTPException(status_code=404, detail="Tool execution not found")
    return record
