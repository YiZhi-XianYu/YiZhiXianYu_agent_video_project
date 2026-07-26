"""Shared vision model loader for semantic classification tools.

Lazy-loads CLIP once and reuses across vision.scene-classify,
vision.object-detect, and vision.person-detect.
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Any

import os
from PIL import Image

logger = logging.getLogger(__name__)

_CLIP_MODEL: Any = None
_CLIP_PROCESSOR: Any = None
_MODEL_NAME = "openai/clip-vit-base-patch32"


def _load_clip() -> tuple[Any, Any]:
    global _CLIP_MODEL, _CLIP_PROCESSOR
    if _CLIP_MODEL is None:
        logger.info("Loading CLIP model %s (this may take a moment on first run)...", _MODEL_NAME)
        from transformers import CLIPModel, CLIPProcessor

        _loc = os.environ.get('CLIP_LOCAL_MODEL_PATH', '') or                '/home/lantwn/.cache/modelscope/models/openai-mirror--clip-vit-base-patch32/snapshots/master'
        _model_dir = _loc if Path(_loc).is_dir() else _MODEL_NAME
        _CLIP_MODEL = CLIPModel.from_pretrained(_model_dir)
        _CLIP_PROCESSOR = CLIPProcessor.from_pretrained(_model_dir)
        logger.info("CLIP model loaded.")
    return _CLIP_MODEL, _CLIP_PROCESSOR


def classify_image(image_path: Path, labels: list[str]) -> dict[str, float]:
    """Run zero-shot classification on a single image.

    Returns a dict mapping each label to its confidence score [0, 1].
    """
    model, processor = _load_clip()
    image = Image.open(image_path).convert("RGB")
    inputs = processor(text=labels, images=image, return_tensors="pt", padding=True)
    outputs = model(**inputs)
    logits_per_image = outputs.logits_per_image[0]
    probs = logits_per_image.softmax(dim=0)
    return {label: round(float(probs[i].item()), 4) for i, label in enumerate(labels)}


def classify_batch(image_paths: list[Path], labels: list[str]) -> list[dict[str, float]]:
    """Run zero-shot classification on multiple images using a single forward pass."""
    model, processor = _load_clip()
    images = [Image.open(p).convert("RGB") for p in image_paths]
    inputs = processor(text=labels, images=images, return_tensors="pt", padding=True)
    outputs = model(**inputs)
    probs = outputs.logits_per_image.softmax(dim=1)
    results: list[dict[str, float]] = []
    for i in range(probs.shape[0]):
        results.append({label: round(float(probs[i][j].item()), 4) for j, label in enumerate(labels)})
    return results
