from typing import Protocol

from app.core.models import ArtifactDescriptor, ToolExecutionRequest
from app.tools.video_probe import VideoProbeTool


class Tool(Protocol):
    name: str
    version: str

    def manifest(self) -> dict: ...

    def execute(self, request: ToolExecutionRequest) -> list[ArtifactDescriptor]: ...


class ToolRegistry:
    def __init__(self) -> None:
        tools: list[Tool] = [VideoProbeTool()]
        self._tools = {(tool.name, tool.version): tool for tool in tools}

    def get(self, name: str, version: str) -> Tool:
        tool = self._tools.get((name, version))
        if tool is None:
            raise ValueError(f"Tool not found or disabled: {name}@{version}")
        return tool

    def manifests(self) -> list[dict]:
        return [tool.manifest() for tool in self._tools.values()]


registry = ToolRegistry()

