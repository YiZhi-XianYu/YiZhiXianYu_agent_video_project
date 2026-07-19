from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any
from uuid import uuid4

from app.core.config import settings
from app.core.models import ArtifactDescriptor, ArtifactInput
from app.tools.video_probe import VideoProbeTool


def read_json_artifact(artifact: ArtifactInput) -> dict[str, Any]:
    path = VideoProbeTool._uri_to_path(artifact.uri)
    if not path.is_file():
        raise ValueError(f"Artifact not found: {path}")
    return json.loads(path.read_text(encoding="utf-8"))


def matching_inputs(inputs: dict[str, ArtifactInput], prefix: str) -> list[ArtifactInput]:
    return [value for key, value in inputs.items() if key == prefix or key.startswith(prefix)]


def write_json_artifact(
    artifact_type: str,
    file_name: str,
    payload: dict[str, Any],
    metadata: dict[str, Any],
) -> ArtifactDescriptor:
    artifact_id = f"art_{uuid4().hex}"
    output_dir = settings.artifact_root / artifact_id
    output_dir.mkdir(parents=True, exist_ok=False)
    output_path = output_dir / file_name
    content = json.dumps(payload, ensure_ascii=False, indent=2).encode("utf-8")
    output_path.write_bytes(content)
    return ArtifactDescriptor(
        artifactId=artifact_id,
        type=artifact_type,
        uri=output_path.resolve().as_uri(),
        mediaType="application/json",
        size=len(content),
        contentHash=hashlib.sha256(content).hexdigest(),
        metadata=metadata,
    )
