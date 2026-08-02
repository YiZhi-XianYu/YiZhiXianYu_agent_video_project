from __future__ import annotations

import hashlib
from pathlib import Path
from urllib.parse import urlparse

import oss2

from app.core.config import settings
from app.core.models import ArtifactDescriptor, ArtifactInput, ToolExecutionRequest


class OssArtifactStorage:
    def __init__(self) -> None:
        self.enabled = settings.artifact_storage_provider.lower() == "oss"
        self._bucket = None

    def _client(self):
        if self._bucket is None:
            required = {
                "endpoint": settings.oss_endpoint,
                "bucket": settings.oss_bucket,
                "access key id": settings.oss_access_key_id,
                "access key secret": settings.oss_access_key_secret,
            }
            missing = [name for name, value in required.items() if not value]
            if missing:
                raise RuntimeError("OSS configuration is incomplete: " + ", ".join(missing))
            auth = oss2.Auth(settings.oss_access_key_id, settings.oss_access_key_secret)
            self._bucket = oss2.Bucket(auth, settings.oss_endpoint, settings.oss_bucket)
        return self._bucket

    def materialize_request(self, request: ToolExecutionRequest) -> ToolExecutionRequest:
        if not self.enabled:
            return request
        local_inputs: dict[str, ArtifactInput] = {}
        for name, artifact in request.inputs.items():
            parsed = urlparse(artifact.uri)
            if parsed.scheme != "oss":
                local_inputs[name] = artifact
                continue
            bucket = parsed.netloc
            if bucket != settings.oss_bucket:
                raise ValueError(f"OSS input bucket is not allowed: {bucket}")
            key = parsed.path.lstrip("/")
            if not key or ".." in key.split("/"):
                raise ValueError("Invalid OSS object key")
            file_name = artifact.file_name or Path(key).name or "artifact.bin"
            target_dir = settings.artifact_root / artifact.artifact_id
            target_dir.mkdir(parents=True, exist_ok=True)
            target = (target_dir / Path(file_name).name).resolve()
            if not target.is_relative_to(target_dir.resolve()):
                raise ValueError("Invalid materialized file name")
            self._client().get_object_to_file(key, str(target))
            local_inputs[name] = artifact.model_copy(update={"uri": target.as_uri(), "file_name": target.name})
        return request.model_copy(update={"inputs": local_inputs})

    def publish_outputs(self, outputs: list[ArtifactDescriptor]) -> list[ArtifactDescriptor]:
        if not self.enabled:
            return outputs
        published: list[ArtifactDescriptor] = []
        for output in outputs:
            # Structured outputs (JSON/SRT/manifest) stay local. Binary media
            # (video/audio/image) is published to OSS for durable direct delivery.
            media_type = (output.media_type or "").lower()
            if not (media_type.startswith("video/") or media_type.startswith("audio/") or media_type.startswith("image/")):
                published.append(output)
                continue
            parsed = urlparse(output.uri)
            if parsed.scheme == "oss":
                published.append(output)
                continue
            if parsed.scheme != "file":
                raise ValueError(f"Unsupported Tool output URI: {output.uri}")
            path = Path(parsed.path)
            if not path.is_file():
                raise FileNotFoundError(f"Tool output is missing: {path}")
            content_hash = self._sha256(path)
            suffix = path.suffix or ".bin"
            key = f"projects/tool-artifacts/{output.artifact_id}/{output.type.lower()}/{content_hash}{suffix}"
            headers = {"Content-Type": output.media_type or "application/octet-stream"}
            self._client().put_object_from_file(key, str(path), headers=headers)
            published.append(output.model_copy(update={
                "uri": f"oss://{settings.oss_bucket}/{key}",
                "size": path.stat().st_size,
                "content_hash": content_hash,
            }))
        return published

    @staticmethod
    def _sha256(path: Path) -> str:
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        return digest.hexdigest()


artifact_storage = OssArtifactStorage()
