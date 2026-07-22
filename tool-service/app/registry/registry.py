from collections.abc import Callable
from typing import Protocol

from app.core.models import ArtifactDescriptor, ToolExecutionRequest
from app.tools.video_proxy_generate import VideoProxyGenerateTool
from app.tools.video_probe import VideoProbeTool
from app.tools.video_shot_detect import VideoShotDetectTool
from app.tools.vision_quality_score import VisionQualityScoreTool
from app.tools.vision_scene_classify import VisionSceneClassifyTool
from app.tools.vision_object_detect import VisionObjectDetectTool
from app.tools.vision_person_detect import VisionPersonDetectTool
from app.tools.shot_decisions import HighlightSelectionTool, ShotRankingTool, TimelineComposeTool
from app.tools.story_plan import StoryPlanTool


class Tool(Protocol):
    name: str
    version: str

    def manifest(self) -> dict: ...

    def execute(
        self,
        request: ToolExecutionRequest,
        report_progress: Callable[[int], None] | None = None,
    ) -> list[ArtifactDescriptor]: ...


class ToolRegistry:
    def __init__(self) -> None:
        tools: list[Tool] = [
            VideoProbeTool(), VideoProxyGenerateTool(), VideoShotDetectTool(), VisionQualityScoreTool(),
            VisionSceneClassifyTool(), VisionObjectDetectTool(), VisionPersonDetectTool(),
            ShotRankingTool(), StoryPlanTool(), HighlightSelectionTool(), TimelineComposeTool(),
        ]
        self._tools = {(tool.name, tool.version): tool for tool in tools}

    def get(self, name: str, version: str) -> Tool:
        tool = self._tools.get((name, version))
        if tool is None:
            raise ValueError(f"Tool not found or disabled: {name}@{version}")
        return tool

    def manifests(self) -> list[dict]:
        return [tool.manifest() for tool in self._tools.values()]


registry = ToolRegistry()
