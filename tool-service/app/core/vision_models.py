"""Shared vision model loader for semantic classification tools.

Lazy-loads CLIP once and reuses across vision.scene-classify,
vision.object-detect, and vision.person-detect.
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Any

import os
from threading import RLock
from PIL import Image

logger = logging.getLogger(__name__)

_CLIP_MODEL: Any = None
_CLIP_PROCESSOR: Any = None
_MODEL_NAME = "openai/clip-vit-base-patch32"
_CLIP_LOCK = RLock()


def release_clip_model() -> bool:
    global _CLIP_MODEL, _CLIP_PROCESSOR
    with _CLIP_LOCK:
        released = _CLIP_MODEL is not None or _CLIP_PROCESSOR is not None
        _CLIP_MODEL = None
        _CLIP_PROCESSOR = None
        return released


def _resolve_clip_model_source(local_path: str | None = None) -> str:
    """Use a configured local model directory, otherwise the public model ID."""
    configured_path = local_path if local_path is not None else os.environ.get("CLIP_LOCAL_MODEL_PATH", "")
    configured_path = configured_path.strip()
    if configured_path and Path(configured_path).is_dir():
        return configured_path
    return _MODEL_NAME


def _load_clip() -> tuple[Any, Any]:
    global _CLIP_MODEL, _CLIP_PROCESSOR
    with _CLIP_LOCK:
        if _CLIP_MODEL is None or _CLIP_PROCESSOR is None:
            logger.info("Loading CLIP model %s (this may take a moment on first run)...", _MODEL_NAME)
            from transformers import CLIPModel, CLIPProcessor

            _model_dir = _resolve_clip_model_source()
            model = CLIPModel.from_pretrained(_model_dir)
            processor = CLIPProcessor.from_pretrained(_model_dir)
            # Publish both references atomically after the complete load.
            _CLIP_MODEL, _CLIP_PROCESSOR = model, processor
            logger.info("CLIP model loaded.")
        return _CLIP_MODEL, _CLIP_PROCESSOR


def classify_image(image_path: Path, labels: list[str]) -> dict[str, float]:
    """Run zero-shot classification on a single image.

    Returns a dict mapping each label to its confidence score [0, 1].
    """
    with _CLIP_LOCK:
        model, processor = _load_clip()
        image = Image.open(image_path).convert("RGB")
        inputs = processor(text=labels, images=image, return_tensors="pt", padding=True)
        outputs = model(**inputs)
        logits_per_image = outputs.logits_per_image[0]
        probs = logits_per_image.softmax(dim=0)
        return {label: round(float(probs[i].item()), 4) for i, label in enumerate(labels)}


def classify_batch(image_paths: list[Path], labels: list[str]) -> list[dict[str, float]]:
    """Run zero-shot classification on multiple images using a single forward pass."""
    with _CLIP_LOCK:
        model, processor = _load_clip()
        images = [Image.open(p).convert("RGB") for p in image_paths]
        inputs = processor(text=labels, images=images, return_tensors="pt", padding=True)
        outputs = model(**inputs)
        probs = outputs.logits_per_image.softmax(dim=1)
        results: list[dict[str, float]] = []
        for i in range(probs.shape[0]):
            results.append({label: round(float(probs[i][j].item()), 4) for j, label in enumerate(labels)})
        return results
