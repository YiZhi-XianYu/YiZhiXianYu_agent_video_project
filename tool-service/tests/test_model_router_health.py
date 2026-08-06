from app.core.config import settings
from app.llm.router import ProviderHealth, model_router


def test_provider_health_enters_cooldown_after_threshold(monkeypatch):
    health = ProviderHealth()
    monkeypatch.setattr(settings, "model_router_failure_threshold", 2)
    monkeypatch.setattr(settings, "model_router_cooldown_seconds", 60)
    assert health.available("openai")
    health.failure("openai")
    assert health.available("openai")
    health.failure("openai")
    assert not health.available("openai")
    health.success("openai")
    assert health.available("openai")


def test_route_contains_explicit_fallback_chain():
    route = model_router.route("STORY_PLAN", request_id="health-test")
    assert route.fallback_chain
    assert route.to_dict()["fallbackChain"] == list(route.fallback_chain)
