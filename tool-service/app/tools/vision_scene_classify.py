from __future__ import annotations

from collections.abc import Callable
from pathlib import Path
from typing import Any

from app.core.config import settings
from app.core.models import ArtifactDescriptor, ToolExecutionRequest
from app.core.vision_models import classify_batch
from app.tools.artifact_json import matching_inputs, read_json_artifact, write_json_artifact


SCENE_LABELS = [
    "a photo of a snow mountain or alpine landscape",
    "a photo of an old town or historic building",
    "a photo of a modern city street",
    "a photo of countryside or farmland",
    "a photo of a lake river or ocean coast",
    "a photo of a forest with trees and vegetation",
    "a photo of an indoor room",
    "a photo of a market or bazaar",
    "a photo of a temple or religious building",
    "a photo of a highway or road",
    "a photo where the sky fills most of the frame with little else visible",
    "a photo of a night scene or dark environment",
    "a close-up photo of a person",
    "a photo of a hiking trail or mountain path",
    "a photo of grassland or open field",
]

SCENE_LABEL_MAP = {
    "a photo of a snow mountain or alpine landscape": "SNOW_MOUNTAIN",
    "a photo of an old town or historic building": "OLD_TOWN",
    "a photo of a modern city street": "MODERN_CITY",
    "a photo of countryside or farmland": "COUNTRYSIDE",
    "a photo of a lake river or ocean coast": "WATERSIDE",
    "a photo of a forest with trees and vegetation": "FOREST",
    "a photo of an indoor room": "INDOOR",
    "a photo of a market or bazaar": "MARKET",
    "a photo of a temple or religious building": "TEMPLE",
    "a photo of a highway or road": "ROAD",
    "a photo where the sky fills most of the frame with little else visible": "SKY_DOMINANT",
    "a photo of a night scene or dark environment": "NIGHT_SCENE",
    "a close-up photo of a person": "PERSON_CLOSEUP",
    "a photo of a hiking trail or mountain path": "HIKING_TRAIL",
    "a photo of grassland or open field": "OPEN_FIELD",
}


SCENE_LABEL_ZH = {
    "a photo of a snow mountain or alpine landscape": "雪山或高山",
    "a photo of an old town or historic building": "古城或历史建筑",
    "a photo of a modern city street": "现代城市街道",
    "a photo of countryside or farmland": "乡村田园",
    "a photo of a lake river or ocean coast": "湖泊河流海岸",
    "a photo of a forest with trees and vegetation": "森林树木植被",
    "a photo of an indoor room": "室内房间",
    "a photo of a market or bazaar": "市场或集市",
    "a photo of a temple or religious building": "寺庙或宗教建筑",
    "a photo of a highway or road": "公路或道路",
    "a photo where the sky fills most of the frame with little else visible": "天空为主",
    "a photo of a night scene or dark environment": "夜景或暗光",
    "a close-up photo of a person": "人物近景",
    "a photo of a hiking trail or mountain path": "徒步或山间小路",
    "a photo of grassland or open field": "草原或开阔地",
}


class VisionSceneClassifyTool:
    name = "vision.scene-classify"
    version = "1.0.0"

    def manifest(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "version": self.version,
            "description": "Classify shot keyframes into scene categories using CLIP zero-shot",
            "executionMode": "ASYNC",
            "resourceClass": "CPU_MEDIUM",
            "resourceGroup": "MODEL",
            "timeoutSeconds": 900,
            "supportsCancellation": False,
            "deterministic": True,
            "cacheable": True,
            "inputTypes": ["SHOT_LIST"],
            "outputTypes": ["SCENE_TAGS"],
        }

    def execute(
        self,
        request: ToolExecutionRequest,
        report_progress: Callable[[int], None] | None = None,
    ) -> list[ArtifactDescriptor]:
        shot_inputs = matching_inputs(request.inputs, "shots")
        if len(shot_inputs) != 1:
            raise ValueError("vision.scene-classify requires one SHOT_LIST")
        shot_payload = read_json_artifact(shot_inputs[0])
        shots = shot_payload.get("shots") or []
        if not shots:
            raise ValueError("Shot list is empty")

        keyframe_paths = _resolve_keyframes(shots)
        labels = list(SCENE_LABELS)
        results = classify_batch(keyframe_paths, labels)

        if report_progress is not None:
            report_progress(30)

        tags: list[dict[str, Any]] = []
        for index, (shot, scores) in enumerate(zip(shots, results)):
            top3 = sorted(scores.items(), key=lambda item: item[1], reverse=True)[:3]
            tags.append({
                "shotId": shot["shotId"],
                "sourceAssetId": shot["sourceAssetId"],
                "keyframeArtifactId": shot["keyframeArtifactId"],
                "index": shot["index"],
                "sceneTags": [
                    {"label": SCENE_LABEL_MAP[label], "labelZh": SCENE_LABEL_ZH[label], "confidence": conf}
                    for label, conf in top3 if conf > 0.10
                ],
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
            "SCENE_TAGS",
            "scene-tags.json",
            payload,
            {
                "sourceAssetId": payload["sourceAssetId"],
                "sourceShotListArtifactId": payload["sourceShotListArtifactId"],
                "shotCount": len(tags),
                "shots": tags,
            },
        )]


def _resolve_keyframes(shots: list[dict[str, Any]]) -> list[Path]:
    paths: list[Path] = []
    for shot in shots:
        artifact_id = shot.get("keyframeArtifactId")
        if not artifact_id:
            raise ValueError(f"Shot {shot.get('shotId', '?')} has no keyframeArtifactId")
        keyframe_path = settings.artifact_root / artifact_id / "keyframe.jpg"
        if not keyframe_path.is_file():
            raise ValueError(f"Keyframe not found: {keyframe_path}")
        paths.append(keyframe_path)
    return paths
