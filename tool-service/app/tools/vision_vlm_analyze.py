"""Unified VLM visual analysis tool — replaces 3 CLIP tools with one VLM call.

Falls back to CLIP when VLM is not configured or fails.
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from pathlib import Path
from typing import Any

from app.core.config import settings
from app.core.models import ArtifactDescriptor, ToolExecutionRequest
from app.llm.vlm_provider import VlmError, encode_image_b64, get_vlm_provider
from app.tools.artifact_json import matching_inputs, read_json_artifact, write_json_artifact
from app.tools.vision_scene_classify import SCENE_LABEL_MAP, SCENE_LABEL_ZH
from app.tools.vision_object_detect import OBJECT_LABEL_MAP, OBJECT_LABEL_ZH
from app.tools.vision_person_detect import PERSON_LABEL_ZH

logger = logging.getLogger(__name__)

BATCH_SIZE = 5

VLM_SYSTEM_PROMPT = """You are a professional video analyst. Analyze each keyframe image and output JSON with scene, object, and person information.

Scene categories (pick ONE primary + up to 2 alternatives):
- WATERSIDE: lake, river, ocean, coast
- FOREST: trees, vegetation, forest
- SNOW_MOUNTAIN: snow mountain, alpine
- OLD_TOWN: historic town, old buildings
- MODERN_CITY: modern city street, skyscrapers
- COUNTRYSIDE: farmland, countryside
- INDOOR: indoor room
- MARKET: market, bazaar
- TEMPLE: temple, religious building
- ROAD: highway, road
- SKY_DOMINANT: sky fills most of frame
- NIGHT_SCENE: night, dark environment
- OPEN_FIELD: grassland, open field
- HIKING_TRAIL: hiking trail, mountain path
- PERSON_CLOSEUP: close-up of a person

Object categories (present/absent, multiple allowed):
BUILDING, VEHICLE, FOOD, TEXT_SIGN, ANIMAL, PLANT, SIGNPOST, BRIDGE, STATUE, BOAT, TENT, STALL, BANNER, LAMP, STAIRS

Person information:
- hasPerson: true/false
- count: NO_PERSON, SINGLE_PERSON, SMALL_GROUP (2-5), CROWD (6+)
- composition: CLOSE_UP, FULL_BODY (if visible)
- activity: WALKING, STANDING, SITTING, INTERACTING (if visible)

For each keyframe, output:
{
  "shotId": "<copy from input>",
  "scene": {"primary": "WATERSIDE", "zhName": "湖泊河流海岸", "confidence": 0.90, "alternatives": ["FOREST"]},
  "objects": [{"label": "BOAT", "zhName": "船或小舟", "confidence": 0.85}],
  "person": {"hasPerson": true, "count": "SMALL_GROUP", "countZh": "两到五人", "composition": "FULL_BODY", "compositionZh": "全身", "activity": "WALKING", "activityZh": "行走中"}
}

Return ONLY valid JSON with structure: {"shots": [...]}"""


class VisionVlmAnalyzeTool:
    name = "vision.vlm-analyze"
    version = "1.0.0"

    def manifest(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "version": self.version,
            "description": "Analyze shot keyframes for scene, object, and person tags using VLM (falls back to CLIP)",
            "executionMode": "ASYNC",
            "resourceClass": "CPU_LIGHT",
            "resourceGroup": "MODEL",
            "timeoutSeconds": 900,
            "supportsCancellation": False,
            "deterministic": False,
            "cacheable": False,
            "inputTypes": ["SHOT_LIST"],
            "outputTypes": ["SCENE_TAGS", "OBJECT_TAGS", "PERSON_TAGS"],
        }

    def execute(
        self,
        request: ToolExecutionRequest,
        report_progress: Callable[[int], None] | None = None,
    ) -> list[ArtifactDescriptor]:
        shot_inputs = matching_inputs(request.inputs, "shots")
        if len(shot_inputs) != 1:
            raise ValueError("vision.vlm-analyze requires one SHOT_LIST")
        shot_payload = read_json_artifact(shot_inputs[0])
        shots = shot_payload.get("shots") or []
        if not shots:
            raise ValueError("Shot list is empty")

        keyframe_map: dict[str, Path] = {}
        for shot in shots:
            artifact_id = shot.get("keyframeArtifactId")
            if not artifact_id:
                raise ValueError(f"Shot {shot.get('shotId', '?')} has no keyframeArtifactId")
            kp = settings.artifact_root / artifact_id / "keyframe.jpg"
            if not kp.is_file():
                raise ValueError(f"Keyframe not found: {kp}")
            keyframe_map[shot["shotId"]] = kp

        if report_progress is not None:
            report_progress(10)

        vlm = get_vlm_provider()
        if vlm.name == "noop":
            logger.info("VLM not configured, falling back to CLIP tools")
            return self._clip_fallback(shots, keyframe_map, shot_payload, shot_inputs[0], report_progress)

        try:
            return self._vlm_analyze(shots, keyframe_map, shot_payload, shot_inputs[0], vlm, report_progress)
        except Exception as exc:
            logger.warning("VLM analysis failed, falling back to CLIP: %s", exc)
            return self._clip_fallback(shots, keyframe_map, shot_payload, shot_inputs[0], report_progress)

    def _vlm_analyze(
        self,
        shots: list[dict[str, Any]],
        keyframe_map: dict[str, Path],
        shot_payload: dict[str, Any],
        shot_input: Any,
        vlm: Any,
        report_progress: Callable[[int], None] | None,
    ) -> list[ArtifactDescriptor]:
        shot_ids = [s["shotId"] for s in shots]
        all_results: list[dict[str, Any]] = []

        for batch_start in range(0, len(shot_ids), BATCH_SIZE):
            batch_ids = shot_ids[batch_start:batch_start + BATCH_SIZE]
            images_b64 = [encode_image_b64(str(keyframe_map[sid])) for sid in batch_ids]

            user_prompt = f"Analyze these {len(batch_ids)} keyframe(s). Shot IDs in order: {', '.join(batch_ids)}"

            result = vlm.analyze_images(
                images_b64,
                VLM_SYSTEM_PROMPT,
                user_prompt,
                temperature=0.3,
                max_tokens=4096,
                request_id=f"vlm-batch-{batch_start // BATCH_SIZE}",
            )

            batch_shots = result.get("shots", [])
            if not isinstance(batch_shots, list):
                raise VlmError(f"VLM returned invalid shots format: {type(batch_shots)}")
            all_results.extend(batch_shots)

            if report_progress is not None:
                progress = 10 + int((batch_start + len(batch_ids)) / len(shot_ids) * 80)
                report_progress(progress)

        return self._compile_artifacts(shots, all_results, shot_payload, shot_input, report_progress)

    def _compile_artifacts(
        self,
        shots: list[dict[str, Any]],
        vlm_results: list[dict[str, Any]],
        shot_payload: dict[str, Any],
        shot_input: Any,
        report_progress: Callable[[int], None] | None,
    ) -> list[ArtifactDescriptor]:
        result_by_id = {r.get("shotId", ""): r for r in vlm_results}
        source_asset_id = shot_payload["sourceAssetId"]

        scene_tags: list[dict[str, Any]] = []
        object_tags: list[dict[str, Any]] = []
        person_tags: list[dict[str, Any]] = []

        for shot in shots:
            sid = shot["shotId"]
            vlm_shot = result_by_id.get(sid, {})

            # Scene
            scene = vlm_shot.get("scene", {})
            scene_items: list[dict[str, Any]] = []
            primary = scene.get("primary", "")
            if primary and primary in SCENE_LABEL_MAP:
                scene_items.append({
                    "label": primary,
                    "labelZh": SCENE_LABEL_ZH.get(
                        _reverse_lookup_label(primary, SCENE_LABEL_MAP), primary
                    ),
                    "confidence": round(scene.get("confidence", 0.8), 4),
                })
            for alt in scene.get("alternatives", [])[:2]:
                if alt in SCENE_LABEL_MAP:
                    scene_items.append({
                        "label": alt,
                        "labelZh": SCENE_LABEL_ZH.get(
                            _reverse_lookup_label(alt, SCENE_LABEL_MAP), alt
                        ),
                        "confidence": round(scene.get("confidence", 0.8) * 0.7, 4),
                    })
            if not scene_items:
                scene_items.append({"label": "SKY_DOMINANT", "labelZh": "天空为主", "confidence": 0.3})
            scene_tags.append({
                "shotId": sid,
                "sourceAssetId": source_asset_id,
                "keyframeArtifactId": shot["keyframeArtifactId"],
                "index": shot["index"],
                "sceneTags": scene_items,
            })

            # Objects
            obj_items: list[dict[str, Any]] = []
            for obj in vlm_shot.get("objects", []):
                label = obj.get("label", "")
                if label in OBJECT_LABEL_MAP:
                    obj_items.append({
                        "label": label,
                        "labelZh": OBJECT_LABEL_ZH.get(
                            _reverse_lookup_label(label, OBJECT_LABEL_MAP), label
                        ),
                        "confidence": round(obj.get("confidence", 0.8), 4),
                    })
            object_tags.append({
                "shotId": sid,
                "sourceAssetId": source_asset_id,
                "keyframeArtifactId": shot["keyframeArtifactId"],
                "index": shot["index"],
                "objectTags": obj_items,
            })

            # Person
            person = vlm_shot.get("person", {})
            person_items: list[dict[str, Any]] = []
            has_person = person.get("hasPerson", False)
            if has_person:
                person_items.append({"label": "HAS_PERSON", "labelZh": "有人", "confidence": 0.9})
                count = person.get("count", "")
                person_items.append({
                    "label": count,
                    "labelZh": PERSON_LABEL_ZH.get(f"a photo of {_count_to_label(count)}", person.get("countZh", count)),
                    "confidence": 0.85,
                })
                comp = person.get("composition", "")
                if comp:
                    person_items.append({
                        "label": comp,
                        "labelZh": PERSON_LABEL_ZH.get(f"a photo of {_comp_to_label(comp)}", person.get("compositionZh", comp)),
                        "confidence": 0.85,
                    })
                activity = person.get("activity", "")
                if activity:
                    person_items.append({
                        "label": activity,
                        "labelZh": PERSON_LABEL_ZH.get(f"a photo of {_activity_to_label(activity)}", person.get("activityZh", activity)),
                        "confidence": 0.85,
                    })
            else:
                person_items.append({"label": "NO_PERSON", "labelZh": "无人", "confidence": 0.9})
            person_tags.append({
                "shotId": sid,
                "sourceAssetId": source_asset_id,
                "keyframeArtifactId": shot["keyframeArtifactId"],
                "index": shot["index"],
                "personTags": person_items,
            })

        if report_progress is not None:
            report_progress(92)

        artifacts: list[ArtifactDescriptor] = []

        scene_payload = {
            "schemaVersion": "1.0",
            "modelName": f"vlm/{get_vlm_provider().name}",
            "sourceAssetId": source_asset_id,
            "sourceShotListArtifactId": shot_input.artifact_id,
            "shotCount": len(scene_tags),
            "shots": scene_tags,
        }
        artifacts.append(write_json_artifact("SCENE_TAGS", "scene-tags.json", scene_payload, scene_payload))

        object_payload = {
            "schemaVersion": "1.0",
            "modelName": f"vlm/{get_vlm_provider().name}",
            "sourceAssetId": source_asset_id,
            "sourceShotListArtifactId": shot_input.artifact_id,
            "shotCount": len(object_tags),
            "shots": object_tags,
        }
        artifacts.append(write_json_artifact("OBJECT_TAGS", "object-tags.json", object_payload, object_payload))

        person_payload = {
            "schemaVersion": "1.0",
            "modelName": f"vlm/{get_vlm_provider().name}",
            "sourceAssetId": source_asset_id,
            "sourceShotListArtifactId": shot_input.artifact_id,
            "shotCount": len(person_tags),
            "shots": person_tags,
        }
        artifacts.append(write_json_artifact("PERSON_TAGS", "person-tags.json", person_payload, person_payload))

        if report_progress is not None:
            report_progress(99)

        return artifacts

    def _clip_fallback(
        self,
        shots: list[dict[str, Any]],
        keyframe_map: dict[str, Path],
        shot_payload: dict[str, Any],
        shot_input: Any,
        report_progress: Callable[[int], None] | None,
    ) -> list[ArtifactDescriptor]:
        """Fall back to CLIP-based tools when VLM is unavailable."""
        from app.core.vision_models import classify_batch

        # Scene classify
        from app.tools.vision_scene_classify import SCENE_LABELS as S_LABELS
        from app.tools.vision_scene_classify import SCENE_LABEL_MAP as S_MAP
        from app.tools.vision_scene_classify import SCENE_LABEL_ZH as S_ZH

        scene_results = classify_batch([keyframe_map[s["shotId"]] for s in shots], list(S_LABELS))
        scene_tags: list[dict[str, Any]] = []
        for shot, scores in zip(shots, scene_results):
            top3 = sorted(scores.items(), key=lambda x: x[1], reverse=True)[:3]
            scene_tags.append({
                "shotId": shot["shotId"],
                "sourceAssetId": shot_payload["sourceAssetId"],
                "keyframeArtifactId": shot["keyframeArtifactId"],
                "index": shot["index"],
                "sceneTags": [
                    {"label": S_MAP[lbl], "labelZh": S_ZH[lbl], "confidence": round(conf, 4)}
                    for lbl, conf in top3 if conf > 0.10
                ],
            })

        if report_progress is not None:
            report_progress(40)

        # Object detect
        from app.tools.vision_object_detect import OBJECT_LABELS as O_LABELS
        from app.tools.vision_object_detect import OBJECT_LABEL_MAP as O_MAP
        from app.tools.vision_object_detect import OBJECT_LABEL_ZH as O_ZH

        obj_results = classify_batch([keyframe_map[s["shotId"]] for s in shots], list(O_LABELS))
        object_tags: list[dict[str, Any]] = []
        for shot, scores in zip(shots, obj_results):
            hits = sorted(scores.items(), key=lambda x: x[1], reverse=True)[:5]
            object_tags.append({
                "shotId": shot["shotId"],
                "sourceAssetId": shot_payload["sourceAssetId"],
                "keyframeArtifactId": shot["keyframeArtifactId"],
                "index": shot["index"],
                "objectTags": [
                    {"label": O_MAP[lbl], "labelZh": O_ZH[lbl], "confidence": round(conf, 4)}
                    for lbl, conf in hits if conf > 0.20
                ],
            })

        if report_progress is not None:
            report_progress(70)

        # Person detect
        from app.tools.vision_person_detect import PERSON_LABELS as P_LABELS
        from app.tools.vision_person_detect import PERSON_LABEL_MAP as P_MAP
        from app.tools.vision_person_detect import PERSON_LABEL_ZH as P_ZH
        from app.tools.vision_person_detect import _summarize_person

        person_results = classify_batch([keyframe_map[s["shotId"]] for s in shots], list(P_LABELS))
        person_tags: list[dict[str, Any]] = []
        for shot, scores in zip(shots, person_results):
            person_tags.append({
                "shotId": shot["shotId"],
                "sourceAssetId": shot_payload["sourceAssetId"],
                "keyframeArtifactId": shot["keyframeArtifactId"],
                "index": shot["index"],
                "personTags": _summarize_person(scores),
            })

        if report_progress is not None:
            report_progress(90)

        source_asset_id = shot_payload["sourceAssetId"]
        artifacts: list[ArtifactDescriptor] = []

        scene_payload = {
            "schemaVersion": "1.0",
            "modelName": "openai/clip-vit-base-patch32 (fallback)",
            "sourceAssetId": source_asset_id,
            "sourceShotListArtifactId": shot_input.artifact_id,
            "shotCount": len(scene_tags),
            "shots": scene_tags,
        }
        artifacts.append(write_json_artifact("SCENE_TAGS", "scene-tags.json", scene_payload, scene_payload))

        object_payload = {
            "schemaVersion": "1.0",
            "modelName": "openai/clip-vit-base-patch32 (fallback)",
            "sourceAssetId": source_asset_id,
            "sourceShotListArtifactId": shot_input.artifact_id,
            "shotCount": len(object_tags),
            "shots": object_tags,
        }
        artifacts.append(write_json_artifact("OBJECT_TAGS", "object-tags.json", object_payload, object_payload))

        person_payload = {
            "schemaVersion": "1.0",
            "modelName": "openai/clip-vit-base-patch32 (fallback)",
            "sourceAssetId": source_asset_id,
            "sourceShotListArtifactId": shot_input.artifact_id,
            "shotCount": len(person_tags),
            "shots": person_tags,
        }
        artifacts.append(write_json_artifact("PERSON_TAGS", "person-tags.json", person_payload, person_payload))

        if report_progress is not None:
            report_progress(99)

        return artifacts


def _reverse_lookup_label(label: str, label_map: dict[str, str]) -> str:
    """Given a label value like 'WATERSIDE', find the original English description key."""
    for eng, code in label_map.items():
        if code == label:
            return eng
    return label


def _count_to_label(count: str) -> str:
    mapping = {
        "NO_PERSON": "no people in it",
        "SINGLE_PERSON": "a single person",
        "SMALL_GROUP": "a small group of two to five people",
        "CROWD": "a large crowd of people",
    }
    return mapping.get(count, "a person")


def _comp_to_label(comp: str) -> str:
    mapping = {
        "CLOSE_UP": "a close-up of a person's face",
        "FULL_BODY": "a person with their full body visible",
    }
    return mapping.get(comp, "a person")


def _activity_to_label(activity: str) -> str:
    mapping = {
        "WALKING": "a person walking",
        "STANDING": "a person standing",
        "SITTING": "a person sitting",
        "INTERACTING": "people talking or interacting",
    }
    return mapping.get(activity, "a person")
