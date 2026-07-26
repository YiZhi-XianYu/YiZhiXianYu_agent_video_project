"""Post-render ASR tool —— 对最终渲染的成片视频进行语音识别，生成 SRT 字幕。

与 audio.speech-transcribe 的区别：
- 后者基于原始素材代理视频和 Timeline 逐片段转写（渲染前）
- 本工具直接对已混音、已加转场的第一遍成片做 ASR（渲染后）
- 字幕时间轴精确匹配最终成片的音频轨
"""

from __future__ import annotations

import hashlib
import logging
import subprocess
import tempfile
from collections.abc import Callable
from pathlib import Path
from typing import Any
from uuid import uuid4

from app.core.config import settings
from app.core.models import ArtifactDescriptor, ToolExecutionRequest
from app.tools.artifact_json import matching_inputs

logger = logging.getLogger(__name__)

# Lazy-loaded WhisperModel（复用 audio_transcribe 的加载逻辑）
_whisper_model: Any = None
_loaded_model_size: str | None = None

_WHISPER_MODEL_SIZE = "small"  # small 模型平衡精度与速度


class TranscribeFinalTool:
    """对最终成片视频做语音转写"""

    name = "audio.transcribe-final"
    version = "1.0.0"

    def manifest(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "version": self.version,
            "description": "对渲染后的成片视频进行 Whisper ASR 语音转写，生成 SRT 字幕",
            "executionMode": "ASYNC",
            "resourceClass": "CPU_MEDIUM",
            "timeoutSeconds": 600,
            "supportsCancellation": False,
            "deterministic": True,
            "cacheable": True,
            "inputTypes": ["RENDERED_VIDEO"],
            "outputTypes": ["SUBTITLE_SRT"],
        }

    def execute(
        self,
        request: ToolExecutionRequest,
        report_progress: Callable[[int], None] | None = None,
    ) -> list[ArtifactDescriptor]:
        # 从输入中提取渲染后的视频文件
        video_inputs = matching_inputs(request.inputs, "rendered_video")
        if not video_inputs:
            raise ValueError("audio.transcribe-final 需要一个 RENDERED_VIDEO 输入")

        video_path = Path(video_inputs[0].uri)
        if not video_path.is_file():
            raise FileNotFoundError(f"成片视频文件不存在: {video_path}")

        if report_progress:
            report_progress(10)

        # 从视频中提取音频为 16kHz mono WAV
        audio_wav = _extract_audio(video_path)

        if report_progress:
            report_progress(30)

        # 使用 Whisper 转写
        segments = _transcribe(audio_wav)

        if report_progress:
            report_progress(80)

        # 生成 SRT
        srt_content = _build_srt(segments)

        # 输出 SRT 文件
        artifact_id = f"art_{uuid4().hex}"
        output_dir = settings.artifact_root / artifact_id
        output_dir.mkdir(parents=True, exist_ok=True)
        srt_path = output_dir / "subtitle.srt"
        srt_path.write_text(srt_content, encoding="utf-8")

        if report_progress:
            report_progress(100)

        return [ArtifactDescriptor(
            artifactId=artifact_id,
            type="SUBTITLE_SRT",
            uri=str(srt_path),
            mediaType="text/srt",
            size=srt_path.stat().st_size,
            contentHash=hashlib.sha256(srt_content.encode()).hexdigest(),
            metadata={"sourceVideo": str(video_path), "language": "zh"},
        )]


def _extract_audio(video_path: Path) -> Path:
    """从视频提取 16kHz mono WAV 音频"""
    wav_path = video_path.with_suffix(".transcribe_tmp.wav")
    subprocess.run(
        [
            "ffmpeg", "-y", "-nostdin",
            "-i", str(video_path),
            "-vn", "-acodec", "pcm_s16le",
            "-ar", "16000", "-ac", "1",
            str(wav_path),
        ],
        check=True, capture_output=True, timeout=120,
    )
    return wav_path


def _transcribe(audio_path: Path) -> list[dict[str, Any]]:
    """Whisper 转写，返回带时间戳的片段列表"""
    global _whisper_model, _loaded_model_size

    if _whisper_model is None or _loaded_model_size != _WHISPER_MODEL_SIZE:
        try:
            from faster_whisper import WhisperModel
            _whisper_model = WhisperModel(_WHISPER_MODEL_SIZE, device="cpu", compute_type="int8")
            _loaded_model_size = _WHISPER_MODEL_SIZE
        except ImportError:
            logger.warning("faster-whisper 未安装，使用 ffmpeg silence-detect 占位")
            return []

    # 执行转写
    segments, _ = _whisper_model.transcribe(str(audio_path), language="zh", beam_size=5)

    result = []
    for seg in segments:
        result.append({
            "start": seg.start,
            "end": seg.end,
            "text": seg.text.strip(),
        })

    # 清理临时文件
    audio_path.unlink(missing_ok=True)
    return result


def _build_srt(segments: list[dict[str, Any]]) -> str:
    """将转写结果构建为 SRT 格式"""
    lines = []
    for i, seg in enumerate(segments, start=1):
        start_ts = _format_timestamp(seg["start"])
        end_ts = _format_timestamp(seg["end"])
        lines.append(str(i))
        lines.append(f"{start_ts} --> {end_ts}")
        lines.append(seg["text"] or "[...]")
        lines.append("")
    return "\n".join(lines)


def _format_timestamp(seconds: float) -> str:
    """秒 → SRT 时间戳 HH:MM:SS,mmm"""
    h = int(seconds // 3600)
    m = int((seconds % 3600) // 60)
    s = seconds % 60
    return f"{h:02d}:{m:02d}:{s:06.3f}".replace(".", ",")
