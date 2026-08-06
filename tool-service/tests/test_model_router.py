from app.core.config import settings
from app.llm.router import model_router


def test_story_plan_route_is_deterministic(monkeypatch):
    monkeypatch.setattr(settings, "llm_provider", "openai")
    monkeypatch.setattr(settings, "llm_api_key", "")
    monkeypatch.setattr(settings, "llm_openai_api_key", "test-key")
    first = model_router.route("STORY_PLAN", request_id="route-test")
    second = model_router.route("STORY_PLAN", request_id="route-test")
    assert first == second
    assert first.provider == "openai"
    assert first.model == settings.llm_openai_model
    assert first.selected_by == "CONFIG"


def test_vlm_without_vision_provider_falls_back_to_clip(monkeypatch):
    monkeypatch.setattr(settings, "vlm_provider", "deepseek")
    monkeypatch.setattr(settings, "vlm_api_key", "key")
    route = model_router.route("SHOT_SEMANTICS", request_id="vlm-test")
    assert route.provider == "clip-local"
    assert route.fallback_reason == "VLM_UNAVAILABLE"


def test_asr_route_uses_local_whisper(monkeypatch):
    monkeypatch.setattr(settings, "asr_model_size", "tiny")
    route = model_router.route("LONG_AUDIO_TRANSCRIPTION", request_id="asr-test")
    assert route.provider == "whisper-local"
    assert route.model == "tiny"
