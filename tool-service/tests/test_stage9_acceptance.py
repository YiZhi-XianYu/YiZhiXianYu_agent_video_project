from app.api.routes import WorkflowIntentRequest, _resolve_duration_ms


def test_forty_five_second_request_is_preserved():
    assert _resolve_duration_ms(
        WorkflowIntentRequest(goal="make a travel short", targetDuration="45 seconds")
    ) == 45000
