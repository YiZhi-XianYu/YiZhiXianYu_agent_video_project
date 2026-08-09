from fastapi import APIRouter, HTTPException, status
from pydantic import BaseModel, Field
from typing import Any
import re
import uuid
import logging

from app.llm.provider import LlmError, generate_json_with_fallback
from app.core.models import AcceptedExecution, ToolExecutionRecord, ToolExecutionRequest
from app.execution.service import execution_service
from app.registry.registry import registry
from app.llm.router import model_router, provider_health, ROUTE_CALLS, ROUTE_LATENCY, ROUTE_TOKENS
from prometheus_client import generate_latest, CONTENT_TYPE_LATEST
from fastapi.responses import Response

router = APIRouter(prefix="/api/v1")
logger = logging.getLogger(__name__)

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

class ChuxueChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=4000)
    history: list[dict[str, str]] = Field(default_factory=list, max_length=30)
    context: dict[str, Any] = Field(default_factory=dict)

class ChuxueChatResponse(BaseModel):
    reply: str
    shouldPlan: bool
    modelRoute: dict[str, Any]
    llmUsed: bool

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

@router.post("/chuxue/chat-legacy", response_model=ChuxueChatResponse, include_in_schema=False)
def chuxue_chat(request: ChuxueChatRequest) -> ChuxueChatResponse:
    route = model_router.route("CHAT", request_id=uuid.uuid4().hex[:12]).to_dict()
    if not route["available"]:
        return ChuxueChatResponse(reply="", shouldPlan=False, modelRoute=route, llmUsed=False)
    system = (
        "你是初雪，一个温和、简洁、懂视频制作的创作助手。\n"
        "你要和用户自然对话，但不能执行工具、生成命令或编造执行状态。\n"
        "如果用户明确提出制作或修改视频方案，shouldPlan=true；普通寒暄、追问或解释时为false。\n"
        "回复使用中文，先回应用户，再给出必要的下一步。只输出JSON。"
    )
    schema = {"title": "chuxue_chat", "type": "object", "additionalProperties": False,
              "properties": {"reply": {"type": "string", "minLength": 1}, "shouldPlan": {"type": "boolean"}},
              "required": ["reply", "shouldPlan"]}
    user = {"message": request.message.strip(), "history": request.history[-20:], "context": request.context}
    try:
        result, used_route, _provider = generate_json_with_fallback("CHAT", system, str(user), schema,
                                                                       temperature=0.5, max_tokens=500,
                                                                       request_id=uuid.uuid4().hex[:12])
        return ChuxueChatResponse(reply=str(result.get("reply") or "我明白了。"), shouldPlan=bool(result.get("shouldPlan")), modelRoute=used_route, llmUsed=True)
    except Exception:
        failed = dict(route); failed["fallbackReason"] = "MODEL_CALL_FAILED"
        return ChuxueChatResponse(reply="", shouldPlan=False, modelRoute=failed, llmUsed=False)

@router.post("/chuxue/chat", response_model=ChuxueChatResponse)
def chuxue_chat_v2(request: ChuxueChatRequest) -> ChuxueChatResponse:
    route = model_router.route("CHAT", request_id=uuid.uuid4().hex[:12]).to_dict()
    if not route["available"]:
        route["fallbackReason"] = "LLM_UNAVAILABLE"
        return ChuxueChatResponse(reply="", shouldPlan=False, modelRoute=route, llmUsed=False)
    system = (
        "你是初雪，一个温和、聪明、懂视频创作的中文助手。你必须同时完成自然回复和是否进入工作流的判断。\n"
        "reply 是主要输出：必须结合用户当前消息、历史对话和项目上下文自然回应，即使 shouldPlan=false 也必须有信息量。\n"
        "禁止只回复‘我明白了’、‘好的’、‘收到’、‘明白了’等空泛确认语。用户说你好时应自我介绍；用户问你是谁时应说明能力；用户说暂时没有想法时应自然陪伴并提供轻量启发。\n"
        "用户明确提出制作或修改视频方案时 shouldPlan=true；寒暄、闲聊、解释、追问或尚未形成制作意图时为 false。\n"
        "不得执行工具、生成命令或编造执行状态。严格只输出一个 JSON 对象，不要 Markdown、代码围栏或额外解释。"
    )
    schema = {"title": "chuxue_chat", "type": "object", "additionalProperties": False,
              "properties": {"reply": {"type": "string", "minLength": 2}, "shouldPlan": {"type": "boolean"}},
              "required": ["reply", "shouldPlan"]}
    user = {"message": request.message.strip(), "history": request.history[-20:], "context": request.context}
    last_route = route
    for attempt in range(2):
        request_id = uuid.uuid4().hex[:12]
        prompt = system if attempt == 0 else system + "\n上次输出不合格。这次必须返回非空、有具体信息的 reply 和布尔 shouldPlan。"
        try:
            result, last_route, _provider = generate_json_with_fallback(
                "CHAT", prompt, str(user), schema, temperature=0.5 if attempt == 0 else 0.7,
                max_tokens=500, request_id=request_id)
            reply = result.get("reply")
            should_plan = result.get("shouldPlan")
            if not isinstance(reply, str) or not reply.strip():
                logger.warning("Chuxue chat validation failed [%s]: missing/empty reply, keys=%s", request_id, list(result.keys()))
                continue
            reply = reply.strip()
            if reply in {"我明白了", "好的", "收到", "明白了"}:
                logger.warning("Chuxue chat quality failed [%s]: generic reply=%r", request_id, reply)
                continue
            if not isinstance(should_plan, bool):
                logger.warning("Chuxue chat validation failed [%s]: shouldPlan type=%s", request_id, type(should_plan).__name__)
                continue
            return ChuxueChatResponse(reply=reply, shouldPlan=should_plan, modelRoute=last_route, llmUsed=True)
        except Exception as exc:
            logger.exception("Chuxue chat model attempt %d failed [%s]: %s", attempt + 1, request_id, exc)
    failed = dict(last_route)
    failed["fallbackReason"] = "CHAT_RESPONSE_INVALID_AFTER_RETRY"
    return ChuxueChatResponse(reply="", shouldPlan=False, modelRoute=failed, llmUsed=False)

@router.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP"}

@router.get("/tools")
def list_tools() -> list[dict]:
    return registry.manifests()

@router.get("/model-routes")
def list_model_routes() -> list[dict]:
    return [model_router.route(capability).to_dict() for capability in (
        "CHAT", "STRUCTURED_INTENT", "STORY_PLAN", "SHOT_SEMANTICS", "LONG_AUDIO_TRANSCRIPTION"
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
