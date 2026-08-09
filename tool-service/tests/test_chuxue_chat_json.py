import pytest

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
