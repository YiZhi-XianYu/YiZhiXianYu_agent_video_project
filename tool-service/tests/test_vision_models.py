from app.core.vision_models import _MODEL_NAME, _resolve_clip_model_source


def test_empty_clip_local_model_path_uses_public_model_id() -> None:
    assert _resolve_clip_model_source("") == _MODEL_NAME
    assert _resolve_clip_model_source("   ") == _MODEL_NAME


def test_missing_clip_local_model_path_uses_public_model_id(tmp_path) -> None:
    assert _resolve_clip_model_source(str(tmp_path / "missing")) == _MODEL_NAME


def test_existing_clip_local_model_path_is_used(tmp_path) -> None:
    model_dir = tmp_path / "clip-model"
    model_dir.mkdir()

    assert _resolve_clip_model_source(str(model_dir)) == str(model_dir)
