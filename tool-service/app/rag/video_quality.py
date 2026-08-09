from __future__ import annotations

from typing import Any


ROLE_QUERIES = {
    "HOOK": ("opening", "attention", "开头", "精彩", "person", "motion"),
    "INTRO": ("establish", "context", "介绍", "风景", "wide", "scenery"),
    "JOURNEY": ("journey", "movement", "旅程", "游玩", "activity", "continuity"),
    "CLIMAX": ("climax", "impressive", "高潮", "精彩", "action", "quality"),
    "ENDING": ("ending", "calm", "结尾", "风景", "sky", "complete"),
}


def _tags(shot: dict[str, Any], semantic: dict[str, Any]) -> set[str]:
    values: list[str] = []
    for key in ("scene", "object", "person"):
        values.extend(str(value).lower() for value in semantic.get(key, []) or [])
    values.extend(str(value).lower() for value in semantic.get("transcript", []) or [])
    for key in ("sceneTags", "objectTags", "personTags"):
        raw = shot.get(key) or []
        values.extend(str(item.get("label", item)).lower() if isinstance(item, dict) else str(item).lower() for item in raw)
    return {value.split("(")[0].replace("_", " ") for value in values}


def retrieve_story_evidence(
    candidates: list[dict[str, Any]],
    semantic_by_shot: dict[str, dict[str, list[str]]] | None = None,
    target_duration_ms: int = 30_000,
    max_shots: int = 12,
) -> dict[str, Any]:
    """Retrieve project-local evidence for each narrative beat.

    This is intentionally deterministic hybrid retrieval: structured quality
    fields are filtered first, then semantic tags are scored against a bounded
    role query. It returns evidence IDs and summaries, never executable data.
    """
    semantic = semantic_by_shot or {}
    evidence: dict[str, list[dict[str, Any]]] = {}
    for role, query_terms in ROLE_QUERIES.items():
        ranked: list[tuple[float, dict[str, Any]]] = []
        for shot in candidates:
            if not shot.get("eligible", True):
                continue
            tags = _tags(shot, semantic.get(str(shot.get("shotId")), {}))
            tag_score = sum(1 for term in query_terms if any(term in tag for tag in tags)) / len(query_terms)
            quality = float(shot.get("qualityScore", shot.get("finalScore", 0)) or 0)
            motion = float(shot.get("motionInterest", 0) or 0)
            chronology = 1.0 - (float(shot.get("startMs", 0)) / max(1.0, float(shot.get("endMs", 1))))
            role_score = 0.55 * tag_score + 0.25 * quality + 0.15 * motion + 0.05 * chronology
            ranked.append((role_score, shot))
        ranked.sort(key=lambda item: (item[0], float(item[1].get("finalScore", 0) or 0)), reverse=True)
        rows = []
        for score, shot in ranked[: max(3, min(6, max_shots))]:
            sid = str(shot.get("shotId"))
            rows.append({
                "shotId": sid,
                "sourceAssetId": shot.get("sourceAssetId", ""),
                "score": round(score, 4),
                "evidence": {
                    "durationMs": shot.get("durationMs"),
                    "qualityScore": shot.get("qualityScore"),
                    "motionInterest": shot.get("motionInterest"),
                    "tags": sorted(_tags(shot, semantic.get(sid, {}))),
                    "transcript": (semantic.get(sid, {}).get("transcript") or [])[:3],
                },
            })
        evidence[role] = rows
    return {
        "strategy": "PROJECT_HYBRID_ROLE_RETRIEVAL_V1",
        "targetDurationMs": target_duration_ms,
        "maxShots": max_shots,
        "roles": evidence,
    }
