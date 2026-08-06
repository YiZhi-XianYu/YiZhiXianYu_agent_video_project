from app.api.routes import WorkflowIntentRequest, _resolve_duration_ms


def test_explicit_duration_ms_takes_precedence():
    request = WorkflowIntentRequest(goal="制作 30 秒短片", targetDuration="45 seconds", targetDurationMs=60000)
    assert _resolve_duration_ms(request) == 60000


def test_explicit_duration_text_takes_precedence_over_goal():
    request = WorkflowIntentRequest(goal="制作旅行短片", targetDuration="45 seconds")
    assert _resolve_duration_ms(request) == 45000


def test_duration_text_supports_chinese_and_goal_fallback():
    assert _resolve_duration_ms(WorkflowIntentRequest(goal="制作旅行短片", targetDuration="1 分钟")) == 60000
    assert _resolve_duration_ms(WorkflowIntentRequest(goal="制作 20 秒旅行短片")) == 20000
