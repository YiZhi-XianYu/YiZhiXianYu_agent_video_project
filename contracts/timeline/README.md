# Timeline Schema

本目录定义声明式视频编辑中间表示，包括画布、轨道、Clip、字幕、音频、转场和受限效果参数。

Timeline 是 Planner 与 FFmpeg Renderer 之间的安全边界，不允许包含任意 Shell 或未验证的 FFmpeg 字符串。

