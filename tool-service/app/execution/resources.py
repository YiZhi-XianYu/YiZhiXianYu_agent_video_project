from __future__ import annotations

from dataclasses import dataclass


LIGHT = "LIGHT"
MEDIA = "MEDIA"
MODEL = "MODEL"
RENDER = "RENDER"


@dataclass(frozen=True)
class ResourcePolicy:
    max_workers: int
    limits: dict[str, int]
    heavy_limit: int = 1

    def limit_for(self, group: str) -> int:
        return max(1, self.limits.get(group, self.max_workers))

    @staticmethod
    def is_heavy(group: str) -> bool:
        return group in {MEDIA, MODEL, RENDER}

    @staticmethod
    def heavy_weight(group: str) -> int:
        if group in {MODEL, RENDER}:
            return 2
        if group == MEDIA:
            return 1
        return 0


def resource_group(manifest: dict | None) -> str:
    if not manifest:
        return LIGHT
    configured = str(manifest.get("resourceGroup", "")).strip().upper()
    if configured:
        return configured
    resource_class = str(manifest.get("resourceClass", "CPU_LIGHT")).upper()
    return LIGHT if resource_class in {"CPU_LOW", "CPU_LIGHT"} else MEDIA
