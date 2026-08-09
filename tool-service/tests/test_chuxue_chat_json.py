import pytest

import json

from app.api.routes import (
    ChuxueChatRequest,
    _chat_user_prompt,
    _duplicates_assistant_history,
    _contradicts_active_workflow,
    _is_exploratory_video_request,
    _is_low_quality_chat_reply,
)
from app.llm.provider import LlmError, _parse_json_response


def test_repairs_markdown_json_fence():
    result = _parse_json_response('```json\n{"reply":"你好！","shouldPlan":false}\n```', "test", "DeepSeek")
    assert result == {"reply": "你好！", "shouldPlan": False}


def test_extracts_json_from_extra_text():
    result = _parse_json_response('输出如下：\n{"reply":"可以聊聊风格。","shouldPlan":false}\n谢谢', "test", "DeepSeek")
    assert result["reply"] == "可以聊聊风格。"
    assert result["shouldPlan"] is False


def test_rejects_non_object_or_invalid_json():
    with pytest.raises(LlmError):
        _parse_json_response('not json', "test", "DeepSeek")
    with pytest.raises(LlmError):
        _parse_json_response('["reply"]', "test", "DeepSeek")


def test_chat_prompt_uses_explicit_json_and_latest_message():
    request = ChuxueChatRequest(
        message="那我要剪视频",
        history=[
            {"role": "user", "content": "编程吧"},
            {"role": "assistant", "content": "好，我们聊编程。"},
        ],
        context={"workflowStatus": "IDLE"},
    )
    payload = json.loads(_chat_user_prompt(request))
    assert payload["latestUserMessage"] == "那我要剪视频"
    assert payload["conversationHistory"][-1] == {"role": "assistant", "content": "好，我们聊编程。"}


def test_rejects_empty_generic_and_repeated_assistant_replies():
    history = [{"role": "assistant", "content": "我们先聊聊视频类型和时长。"}]
    assert _is_low_quality_chat_reply("好的。") is True
    assert _is_low_quality_chat_reply("可以，把表结构发给我。") is False
    assert _duplicates_assistant_history("我们先聊聊视频类型和时长。", history) is True
    assert _duplicates_assistant_history("这次我们先确定主题。", history) is False


@pytest.mark.parametrize("message", ["我想剪视频", "那我要剪视频", "我没什么想法，你帮我想想计划"])
def test_vague_video_ideation_does_not_start_planning(message):
    assert _is_exploratory_video_request(message) is True


@pytest.mark.parametrize("message", ["把项目里的两个视频剪成30秒旅行短片", "不要字幕，做成45秒"])
def test_concrete_video_request_can_enter_planning(message):
    assert _is_exploratory_video_request(message) is False


def test_active_workflow_rejects_false_new_workflow_promises():
    assert _contradicts_active_workflow("好的，我来开启新的 Workflow。", True) is True
    assert _contradicts_active_workflow("当前 Workflow 仍在运行，完成或失败后才能新建。", True) is False
    assert _contradicts_active_workflow("好的，我来开启新的 Workflow。", False) is False


def test_capability_contract_is_present_in_chat_context():
    request = ChuxueChatRequest(
        message="你是谁？",
        context={"capabilityContract": {
            "identity": "视频创作项目的智能入口与执行协同 Agent",
            "cannot": ["不能直接执行任意命令"]
        }},
    )
    payload = json.loads(_chat_user_prompt(request))
    assert "capabilityContract" in payload["projectContext"]
    assert "不能直接执行任意命令" in payload["projectContext"]["capabilityContract"]["cannot"]
