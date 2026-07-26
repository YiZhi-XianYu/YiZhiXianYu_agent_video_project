"""字幕烧录工具 —— 将 SRT 字幕烧录到第一遍渲染的成片中，输出带字幕的最终视频。

与 video.render 的区别：
- video.render 是完整的 Timeline→MP4 渲染（包含转场/BGM）
- 本工具仅执行最后一步：将字幕烧录到已有视频中
- 复用了 video.render 中的 FFmpeg subtitles 滤镜逻辑
"""

from __future__ import annotations

import hashlib
import json
import subprocess
from collections.abc import Callable
from pathlib import Path
from typing import Any
from uuid import uuid4

from app.core.config import settings
from app.core.models import ArtifactDescriptor, ToolExecutionRequest
from app.tools.artifact_json import matching_inputs


class RenderSubtitlesTool:
    """将 SRT 字幕烧录到视频中"""

    name = "video.render-subtitles"
    version = "1.0.0"

    def manifest(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "version": self.version,
            "description": "将 SRT 字幕烧录到已渲染的成片视频中，支持自定义样式",
            "executionMode": "ASYNC",
            "resourceClass": "CPU_MEDIUM",
            "timeoutSeconds": 600,
            "supportsCancellation": False,
            "deterministic": True,
            "cacheable": False,
            "inputTypes": ["RENDERED_VIDEO", "SUBTITLE_SRT"],
            "outputTypes": ["SUBTITLED_VIDEO"],
            "parameters": {
                "subtitleStyle": {
                    "type": "object",
                    "description": "字幕样式配置",
                    "default": {
                        "fontSize": 24,
                        "fontColor": "white",
                        "position": "bottom",
                        "outlineColor": "black",
                    },
                },
            },
        }

    def execute(
        self,
        request: ToolExecutionRequest,
        report_progress: Callable[[int], None] | None = None,
    ) -> list[ArtifactDescriptor]:
        video_inputs = matching_inputs(request.inputs, "rendered_video")
        srt_inputs = matching_inputs(request.inputs, "subtitle_srt")

        if not video_inputs:
            raise ValueError("video.render-subtitles 需要一个 RENDERED_VIDEO 输入")
        if not srt_inputs:
            raise ValueError("video.render-subtitles 需要一个 SUBTITLE_SRT 输入")

        video_path = Path(video_inputs[0].uri)
        srt_path = Path(srt_inputs[0].uri)

        if not video_path.is_file():
            raise FileNotFoundError(f"视频文件不存在: {video_path}")
        if not srt_path.is_file():
            raise FileNotFoundError(f"字幕文件不存在: {srt_path}")

        # 解析字幕样式参数
        style = (request.parameters or {}).get("subtitleStyle", {})
        font_size = style.get("fontSize", 24)
        font_color = style.get("fontColor", "white")
        position = style.get("position", "bottom")
        outline_color = style.get("outlineColor", "black")

        if report_progress:
            report_progress(10)

        # 构建 FFmpeg 命令
        artifact_id = f"art_{uuid4().hex}"
        output_dir = settings.artifact_root / artifact_id
        output_dir.mkdir(parents=True, exist_ok=True)
        output_path = output_dir / "final_subtitled.mp4"

        # 字幕滤镜：Force_style 应用于所有字幕
        alignment = "2" if position == "bottom" else "6"  # 2=底部居中, 6=顶部居中
        force_style = (
            f"FontSize={font_size},"
            f"PrimaryColour=&H{_color_to_bgr(font_color)},"
            f"OutlineColour=&H{_color_to_bgr(outline_color)},"
            f"Outline=2,"
            f"Alignment={alignment}"
        )

        # FFmpeg 命令：直接 copy 视频流 + 烧录字幕
        subprocess.run(
            [
                "ffmpeg", "-y", "-nostdin",
                "-i", str(video_path),
                "-vf", f"subtitles={srt_path}:force_style='{force_style}'",
                "-c:v", "libx264", "-preset", "medium", "-crf", "20",
                "-c:a", "copy",
                str(output_path),
            ],
            check=True, capture_output=True, timeout=600,
        )

        if report_progress:
            report_progress(100)

        return [ArtifactDescriptor(
            artifactId=artifact_id,
            type="SUBTITLED_VIDEO",
            uri=str(output_path),
            mediaType="video/mp4",
            size=output_path.stat().st_size,
            contentHash=hashlib.sha256(output_path.read_bytes()).hexdigest(),
            metadata={"sourceVideo": str(video_path), "subtitleStyle": style},
        )]


def _color_to_bgr(html_color: str) -> str:
    """将 HTML 颜色 (#rrggbb 或颜色名) 转为 FFmpeg BGR 十六进制"""
    color_map = {
        "white": "FFFFFF", "black": "000000", "yellow": "00FFFF",
        "green": "00FF00", "cyan": "FFFF00", "red": "0000FF",
        "blue": "FF0000",
    }
    if html_color.startswith("#"):
        hex_val = html_color.lstrip("#")
        # HTML RGB → BGR（交换 R 和 B）
        if len(hex_val) == 6:
            return hex_val[4:6] + hex_val[2:4] + hex_val[0:2]
        return hex_val
    return color_map.get(html_color.lower(), "FFFFFF")
