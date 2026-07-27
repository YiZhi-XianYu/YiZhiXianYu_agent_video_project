from __future__ import annotations

from collections.abc import Callable
from pathlib import Path
from typing import Any

from app.core.config import settings
from app.core.models import ArtifactDescriptor, ToolExecutionRequest
from app.core.vision_models import classify_batch
from app.tools.artifact_json import matching_inputs, read_json_artifact, write_json_artifact


OBJECT_LABELS = [
    "a building or house",
    "a vehicle or car",
    "food or a meal",
    "text or a signboard",
    "an animal",
    "plants or flowers",
    "a road sign or signpost",
    "a bridge",
    "a sculpture or monument",
    "a boat or canoe",
    "a tent or campsite",
    "a market stall or shelf",
    "a flag or banner",
    "a street lamp or lighting",
    "stairs or steps",
]

OBJECT_LABEL_MAP = {
    "a building or house": "BUILDING",
    "a vehicle or car": "VEHICLE",
    "food or a meal": "FOOD",
    "text or a signboard": "TEXT_SIGN",
    "an animal": "ANIMAL",
    "plants or flowers": "PLANT",
    "a road sign or signpost": "SIGNPOST",
    "a bridge": "BRIDGE",
    "a sculpture or monument": "STATUE",
    "a boat or canoe": "BOAT",
    "a tent or campsite": "TENT",
    "a market stall or shelf": "STALL",
    "a flag or banner": "BANNER",
    "a street lamp or lighting": "LAMP",
    "stairs or steps": "STAIRS",
}


OBJECT_LABEL_ZH = {
    "a building or house": "建筑或房屋",
    "a vehicle or car": "车辆或汽车",
    "food or a meal": "食物或餐点",
    "text or a signboard": "文字或招牌",
    "an animal": "动物",
    "plants or flowers": "植物或花卉",
    "a road sign or signpost": "路标或指示牌",
    "a bridge": "桥梁",
    "a sculpture or monument": "雕塑或纪念碑",
    "a boat or canoe": "船或小舟",
    "a tent or campsite": "帐篷或营地",
    "a market stall or shelf": "摊位或货架",
    "a flag or banner": "旗帜或横幅",
    "a street lamp or lighting": "路灯或照明",
    "stairs or steps": "台阶或楼梯",
}


class VisionObjectDetectTool:
    name = "vision.object-detect"
    version = "1.0.0"

    def manifest(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "version": self.version,
            "description": "Detect objects in shot keyframes using CLIP zero-shot",
            "executionMode": "ASYNC",
            "resourceClass": "CPU_MEDIUM",
            "resourceGroup": "MODEL",
            "timeoutSeconds": 900,
            "supportsCancellation": False,
            "deterministic": True,
            "cacheable": True,
            "inputTypes": ["SHOT_LIST"],
            "outputTypes": ["OBJECT_TAGS"],
        }

    def execute(
        self,
        request: ToolExecutionRequest,
        report_progress: Callable[[int], None] | None = None,
    ) -> list[ArtifactDescriptor]:
        shot_inputs = matching_inputs(request.inputs, "shots")
        if len(shot_inputs) != 1:
            raise ValueError("vision.object-detect requires one SHOT_LIST")
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

        labels = list(OBJECT_LABELS)
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
                "objectTags": [
                    {"label": OBJECT_LABEL_MAP[label], "labelZh": OBJECT_LABEL_ZH[label], "confidence": conf}
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
            "OBJECT_TAGS",
            "object-tags.json",
            payload,
            {
                "sourceAssetId": payload["sourceAssetId"],
                "sourceShotListArtifactId": payload["sourceShotListArtifactId"],
                "shotCount": len(tags),
                "shots": tags,
            },
        )]
