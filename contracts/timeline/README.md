# Timeline Schema

本目录定义声明式视频编辑中间表示，包括画布、轨道、Clip、字幕、音频、转场和受限效果参数。

Timeline 是 Planner 与 FFmpeg Renderer 之间的安全边界，不允许包含任意 Shell 或未验证的 FFmpeg 字符串。

## v1.1（第八阶段 P4）

[`timeline.schema.json`](timeline.schema.json) 扩展了 v1.0，新增：

- **转场类型**: `CUT`（0ms）、`FADE`（200–2000ms 淡入）、`CROSS_DISSOLVE`（200–2000ms 交叉溶解）
- **AUDIO 轨道**: 可选的 BGM 背景音乐轨，含 `uri`、`volume`（0.0–1.0）、`ducking` 配置
- **SUBTITLE 轨道**: 可选的 SRT/VTT 字幕轨，含 `uri`、`format`
- **`transitionIn` 条件校验**: CUT 要求 0ms，FADE/CROSS_DISSOLVE 要求 ≥200ms
- **向后兼容**: v1.0 时间线（仅 CUT）仍然通过校验

`TimelineValidator` 额外检查：首个 Clip 必须为 CUT、转场时长不能超过 Clip 时长、AUDIO/SUBTITLE 轨道参数合法性。

`VideoRenderTool` v1.1.0 使用 `xfade`/`acrossfade`（CROSS_DISSOLVE）、`fade`/`afade`（FADE）、`amix`（BGM 混音）、`subtitles`（字幕烧录）构建 FFmpeg 滤镜图。
