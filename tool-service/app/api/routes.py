from fastapi import APIRouter, HTTPException, status
from pydantic import BaseModel, Field
from typing import Any
import re
import uuid
import logging
import json

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
    planningGoal: str | None = None
    targetDurationMs: int | None = None


def _chat_user_prompt(request: ChuxueChatRequest) -> str:
    """Serialize chat context as real JSON instead of Python repr.

    DeepSeek is much less likely to confuse an earlier turn with the latest
    request when roles, ordering, and the current message are explicit JSON.
    """
    history = []
    for item in (request.history or [])[-20:]:
        role = str(item.get("role", "user")).lower()
        if role not in {"user", "assistant"}:
            role = "user"
        history.append({"role": role, "content": str(item.get("content", ""))})
    payload = {
        "conversationHistory": history,
        "latestUserMessage": request.message.strip(),
        "projectContext": request.context or {},
    }
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


def _is_low_quality_chat_reply(reply: object) -> bool:
    if not isinstance(reply, str) or not reply.strip():
        return True
    normalized = re.sub(r"[\s。！!，,、]+", "", reply.strip())
    return normalized in {"我明白了", "好的", "收到", "明白了", "嗯", "哦"}


def _duplicates_assistant_history(reply: str, history: list[dict[str, str]]) -> bool:
    normalized = re.sub(r"\s+", "", reply.strip())
    if not normalized:
        return False
    return any(
        str(item.get("role", "")).lower() == "assistant"
        and re.sub(r"\s+", "", str(item.get("content", "")).strip()) == normalized
        for item in (history or [])[-20:]
    )


def _is_exploratory_video_request(message: str) -> bool:
    """Keep vague ideation in conversation instead of creating a Workflow."""
    text = re.sub(r"[\s，。！？!?,、]+", "", message or "")
    if any(marker in text for marker in ("没什么想法", "没有想法", "帮我想想计划", "先聊聊", "还没想好")):
        return True
    return bool(re.fullmatch(r"(?:那)?(?:我)?(?:想|要|想要)?(?:剪|做|制作)(?:一个|个)?视频(?:的想法)?", text))


def _contradicts_active_workflow(reply: str, workflow_active: bool) -> bool:
    if not workflow_active:
        return False
    compact = re.sub(r"\s+", "", reply or "")
    promises = ("我来开启新的", "我会开启新的", "现在开启新的", "立即开启新的", "开始新的Workflow", "再创建一个Workflow")
    return any(phrase.lower() in compact.lower() for phrase in promises)

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
    user = _chat_user_prompt(request)
    try:
        result, used_route, _provider = generate_json_with_fallback("CHAT", system, str(user), schema,
                                                                       temperature=0.5, max_tokens=500,
                                                                       request_id=uuid.uuid4().hex[:12])
        reply = result.get("reply")
        if _is_low_quality_chat_reply(reply):
            raise LlmError("CHAT_REPLY_LOW_QUALITY")
        return ChuxueChatResponse(reply=str(reply).strip(), shouldPlan=bool(result.get("shouldPlan")), modelRoute=used_route, llmUsed=True)
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
        "必须优先理解 latestUserMessage；它代表用户此刻真正要回应的内容，不能被更早的历史话题覆盖。\n"
        "如果用户明确纠正或切换话题（例如‘聊编程吧’、‘那我要剪视频’、‘别聊视频了’），必须立即承认切换并围绕最新话题回答。不要继续复用旧话题的整段回复。\n"
        "projectContext 是后端事实，优先级高于你根据历史做出的猜测。不得声称自己能修改或启动尚未由后端确认的执行状态。\n"
        "capabilityContract 是后端注入的能力契约。介绍‘你是谁’或回答‘你会不会某事’时，只能依据该契约，不得扩大能力范围。你首先是视频创作 Agent：可以理解需求、读取项目事实、提出受控计划、在用户确认后请求后端启动、解释运行状态；不能把自己描述成通用编程 Agent、专业语言教师、母语者或可以直接操作系统的助手。\n"
        "对非视频话题可以礼貌地进行有限交流，但要诚实说明这不是核心能力；用户明确要求换回中文或其他语言时可以切换回复语言，但不要承诺专业语言能力。\n"
        "若 projectContext.workflowActive=true，当前会话在该 Workflow 完成或失败前禁止创建第二个 Workflow。此时 shouldPlan 必须为 false；用户要求‘再剪一个’或‘开启新的 Workflow’时，应明确说明当前 Workflow 仍在执行，并报告 workflowRunId、workflowStatus、progress、nextAction。\n"
        "用户询问‘做到哪一步’‘视频怎么样了’时，必须直接依据 projectContext 中的 workflowStatus、progress、currentTaskNode、nextAction 回答，不得编造、不得说自己看不到细节。\n"
        "如果 workflowStatus=SUCCEEDED，要明确已完成；如果 workflowStatus=FAILED，要明确已失败；不要继续声称仍在运行。\n"
        "reply 是主要输出：必须结合最新消息、必要的历史上下文和项目上下文自然回应，即使 shouldPlan=false 也必须有信息量。\n"
        "禁止只回复‘我明白了’、‘好的’、‘收到’、‘明白了’等空泛确认语，也禁止复制之前已经说过的整段回复。\n"
        "普通问题（包括编程、MySQL等）可以正常回答，但不要假装自己已经创建计划。只有用户明确表示要制作/剪辑/修改视频，或明确要求你帮忙制定视频制作计划时，shouldPlan=true；单纯说‘我有想法’或讨论概念时仍为 false。\n"
        "shouldPlan=true 表示信息已经足够交给后端立即生成一个可确认的 Workflow，而不只是用户有剪视频的意愿。\n"
        "同时输出 missingInformation：列出生成可靠 Workflow 仍缺少的关键信息。只要 missingInformation 非空，shouldPlan 必须为 false。\n"
        "planningGoal 是交给后端的完整、独立、可执行创作需求，不能只写‘可以’‘开始吧’‘和原来一样’。如果用户用简短确认承接前文，你必须从历史中重述素材范围、主题、时长、风格和明确约束。shouldPlan=false 时 planningGoal=null。\n"
        "targetDurationMs 是当前对话中用户最新确认的目标时长。用户明确说‘20秒’时输出20000，即使此轮仍需继续澄清；未知时为null。绝不能把已经确认的20秒恢复成默认30秒。\n"
        "‘我想剪视频’、‘我有剪视频的想法’、‘我没什么想法，你帮我想想计划’都属于探索阶段：先提供少量方向或提出最少量澄清问题，missingInformation 非空，shouldPlan=false。\n"
        "只有目标基本可执行（例如明确了素材范围，并具备主题/目标时长/关键偏好中的必要信息）或用户确认了此前已讨论清楚的方案，missingInformation 才能为空且 shouldPlan=true。\n"
        "不得执行工具、生成命令或编造执行状态。严格只输出一个 JSON 对象，不要 Markdown、代码围栏或额外解释。"
    )
    schema = {"title": "chuxue_chat", "type": "object", "additionalProperties": False,
              "properties": {
                  "reply": {"type": "string", "minLength": 2},
                  "shouldPlan": {"type": "boolean"},
                  "missingInformation": {"type": "array", "items": {"type": "string"}, "maxItems": 8},
                  "planningGoal": {"type": ["string", "null"]},
                  "targetDurationMs": {"type": ["integer", "null"], "minimum": 5000, "maximum": 300000},
              },
              "required": ["reply", "shouldPlan", "missingInformation", "planningGoal", "targetDurationMs"]}
    user = _chat_user_prompt(request)
    last_route = route
    for attempt in range(2):
        request_id = uuid.uuid4().hex[:12]
        prompt = system if attempt == 0 else system + (
            "\n上次输出不合格。这次必须重新理解 latestUserMessage，返回新的、有具体信息的 reply、"
            "布尔 shouldPlan 和 missingInformation；不要复制历史中的助手回复。")
        try:
            result, last_route, _provider = generate_json_with_fallback(
                "CHAT", prompt, str(user), schema, temperature=0.5 if attempt == 0 else 0.7,
                max_tokens=500, request_id=request_id)
            reply = result.get("reply")
            should_plan = result.get("shouldPlan")
            missing_information = result.get("missingInformation")
            planning_goal = result.get("planningGoal")
            target_duration_ms = result.get("targetDurationMs")
            if not isinstance(reply, str) or not reply.strip():
                logger.warning("Chuxue chat validation failed [%s]: missing/empty reply, keys=%s", request_id, list(result.keys()))
                continue
            reply = reply.strip()
            if _is_low_quality_chat_reply(reply):
                logger.warning("Chuxue chat quality failed [%s]: generic reply=%r", request_id, reply)
                continue
            if _duplicates_assistant_history(reply, request.history):
                logger.warning("Chuxue chat quality failed [%s]: duplicated historical assistant reply", request_id)
                continue
            workflow_active = bool((request.context or {}).get("workflowActive"))
            if _contradicts_active_workflow(reply, workflow_active):
                logger.warning("Chuxue chat quality failed [%s]: reply contradicts active Workflow", request_id)
                continue
            if not isinstance(should_plan, bool):
                logger.warning("Chuxue chat validation failed [%s]: shouldPlan type=%s", request_id, type(should_plan).__name__)
                continue
            if not isinstance(missing_information, list) or not all(isinstance(item, str) for item in missing_information):
                logger.warning("Chuxue chat validation failed [%s]: invalid missingInformation", request_id)
                continue
            if missing_information and should_plan:
                logger.warning("Chuxue chat corrected [%s]: planning suppressed because information is missing: %s",
                               request_id, missing_information)
                should_plan = False
            if should_plan and _is_exploratory_video_request(request.message):
                logger.warning("Chuxue chat corrected [%s]: exploratory request is not ready for planning", request_id)
                should_plan = False
            if workflow_active and should_plan:
                logger.warning("Chuxue chat corrected [%s]: active Workflow suppresses new planning", request_id)
                should_plan = False
            if should_plan and (not isinstance(planning_goal, str) or not planning_goal.strip()):
                logger.warning("Chuxue chat validation failed [%s]: planningGoal is required for planning", request_id)
                continue
            if target_duration_ms is not None and not isinstance(target_duration_ms, int):
                logger.warning("Chuxue chat validation failed [%s]: targetDurationMs type=%s", request_id,
                               type(target_duration_ms).__name__)
                continue
            return ChuxueChatResponse(reply=reply, shouldPlan=should_plan,
                                      planningGoal=planning_goal.strip() if should_plan else None,
                                      targetDurationMs=target_duration_ms,
                                      modelRoute=last_route, llmUsed=True)
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
