from __future__ import annotations

from collections.abc import Callable
from pathlib import Path
from typing import Any

from app.core.config import settings
from app.core.models import ArtifactDescriptor, ToolExecutionRequest
from app.core.vision_models import classify_batch
from app.tools.artifact_json import matching_inputs, read_json_artifact, write_json_artifact


PERSON_LABELS = [
    "a photo with a person in it",
    "a photo of a single person",
    "a photo of a small group of two to five people",
    "a photo of a large crowd of people",
    "a close-up photo of a person's face",
    "a photo of a person with their full body visible",
    "a photo of a person walking",
    "a photo of a person standing",
    "a photo of a person sitting",
    "a photo of people talking or interacting",
    "a photo with no people in it",
]

PERSON_LABEL_MAP = {
    "a photo with a person in it": "HAS_PERSON",
    "a photo of a single person": "SINGLE_PERSON",
    "a photo of a small group of two to five people": "SMALL_GROUP",
    "a photo of a large crowd of people": "CROWD",
    "a close-up photo of a person's face": "CLOSE_UP",
    "a photo of a person with their full body visible": "FULL_BODY",
    "a photo of a person walking": "WALKING",
    "a photo of a person standing": "STANDING",
    "a photo of a person sitting": "SITTING",
    "a photo of people talking or interacting": "INTERACTING",
    "a photo with no people in it": "NO_PERSON",
}

_PERSON_PRESENT_LABELS = {"HAS_PERSON", "SINGLE_PERSON", "SMALL_GROUP", "CROWD", "CLOSE_UP", "FULL_BODY", "WALKING", "STANDING", "SITTING", "INTERACTING"}
_ACTIVITY_LABELS = {"WALKING", "STANDING", "SITTING", "INTERACTING"}

PERSON_LABEL_ZH = {
    "a photo with a person in it": "有人",
    "a photo of a single person": "一个人",
    "a photo of a small group of two to five people": "两到五人",
    "a photo of a large crowd of people": "一大群人",
    "a close-up photo of a person's face": "特写",
    "a photo of a person with their full body visible": "全身",
    "a photo of a person walking": "行走中",
    "a photo of a person standing": "站立",
    "a photo of a person sitting": "就坐",
    "a photo of people talking or interacting": "交谈互动",
    "a photo with no people in it": "无人",
}


class VisionPersonDetectTool:
    name = "vision.person-detect"
    version = "1.0.0"

    def manifest(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "version": self.version,
            "description": "Detect person presence, count, and activity in shot keyframes using CLIP zero-shot",
            "executionMode": "ASYNC",
            "resourceClass": "CPU_MEDIUM",
            "resourceGroup": "MODEL",
            "timeoutSeconds": 900,
            "supportsCancellation": False,
            "deterministic": True,
            "cacheable": True,
            "inputTypes": ["SHOT_LIST"],
            "outputTypes": ["PERSON_TAGS"],
        }

    def execute(
        self,
        request: ToolExecutionRequest,
        report_progress: Callable[[int], None] | None = None,
    ) -> list[ArtifactDescriptor]:
        shot_inputs = matching_inputs(request.inputs, "shots")
        if len(shot_inputs) != 1:
            raise ValueError("vision.person-detect requires one SHOT_LIST")
        shot_payload = read_json_artifact(shot_inputs[0])
        shots = shot_payload.get("shots") or []
        if not shots:
            raise ValueError("Shot list is empty")

        keyframe_paths: list[Path] = []
        for shot in shots:
            artifact_id = shot.get("keyframeArtifactId")
            if not artifact_id:
                raise ValueError(f"Shot {shot.get('shotId', '?')} has no keyframeArtifactId")
            kp = settings.artifact_root / artifact_id / "keyframe.jpg"
            if not kp.is_file():
                raise ValueError(f"Keyframe not found: {kp}")
            keyframe_paths.append(kp)

        labels = list(PERSON_LABELS)
        results = classify_batch(keyframe_paths, labels)

        if report_progress is not None:
            report_progress(30)

        tags: list[dict[str, Any]] = []
        for index, (shot, scores) in enumerate(zip(shots, results)):
            person_tags = _summarize_person(scores)
            tags.append({
                "shotId": shot["shotId"],
                "sourceAssetId": shot["sourceAssetId"],
                "keyframeArtifactId": shot["keyframeArtifactId"],
                "index": shot["index"],
                "personTags": person_tags,
            })
            if report_progress is not None:
                report_progress(30 + int((index + 1) / len(shots) * 60))

        payload = {
            "schemaVersion": "1.0",
            "modelName": "openai/clip-vit-base-patch32",
            "sourceAssetId": shot_payload["sourceAssetId"],
            "sourceShotListArtifactId": shot_inputs[0].artifact_id,
            "shotCount": len(tags),
            "shots": tags,
        }
        if report_progress is not None:
            report_progress(95)

        return [write_json_artifact(
            "PERSON_TAGS",
            "person-tags.json",
            payload,
            {
                "sourceAssetId": payload["sourceAssetId"],
                "sourceShotListArtifactId": payload["sourceShotListArtifactId"],
                "shotCount": len(tags),
                "shots": tags,
            },
        )]


def _summarize_person(scores: dict[str, float]) -> list[dict[str, Any]]:
    """Convert raw CLIP scores into structured person tags."""
    tags: list[dict[str, Any]] = []
    no_person_conf = scores.get("a photo with no people in it", 0)

    if no_person_conf > 0.7:
        tags.append({"label": "NO_PERSON", "labelZh": "无人", "confidence": round(no_person_conf, 4)})
        return tags

    has_person_conf = scores.get("a photo with a person in it", 0)
    if has_person_conf < 0.3:
        tags.append({"label": "NO_PERSON", "labelZh": "无人", "confidence": round(1.0 - has_person_conf, 4)})
        return tags

    tags.append({"label": "HAS_PERSON", "labelZh": "有人", "confidence": round(has_person_conf, 4)})

    # Person count
    count_scores = {
        k: scores.get(k, 0)
        for k in [
            "a photo of a single person",
            "a photo of a small group of two to five people",
            "a photo of a large crowd of people",
        ]
    }
    best_count_label, best_count_conf = max(count_scores.items(), key=lambda item: item[1])
    if best_count_conf > 0.3:
        tags.append({
            "label": PERSON_LABEL_MAP[best_count_label],
            "labelZh": PERSON_LABEL_ZH[best_count_label],
            "confidence": round(best_count_conf, 4),
        })

    # Close-up vs full body
    closeup = scores.get("a close-up photo of a person's face", 0)
    full_body = scores.get("a photo of a person with their full body visible", 0)
    if closeup > 0.4 and closeup > full_body:
        tags.append({"label": "CLOSE_UP", "labelZh": "特写", "confidence": round(closeup, 4)})
    elif full_body > 0.4:
        tags.append({"label": "FULL_BODY", "labelZh": "全身", "confidence": round(full_body, 4)})

    # Activity
    activity_scores = {
        k: scores.get(k, 0)
        for k in [
            "a photo of a person walking",
            "a photo of a person standing",
            "a photo of a person sitting",
            "a photo of people talking or interacting",
        ]
    }
    best_act_label, best_act_conf = max(activity_scores.items(), key=lambda item: item[1])
    if best_act_conf > 0.3:
        tags.append({
            "label": PERSON_LABEL_MAP[best_act_label],
            "labelZh": PERSON_LABEL_ZH[best_act_label],
            "confidence": round(best_act_conf, 4),
        })

    return tags
