# Timeline Schema

本目录定义声明式视频编辑中间表示，包括画布、轨道、Clip、字幕、音频、转场和受限效果参数。

Timeline 是 Planner 与 FFmpeg Renderer 之间的安全边界，不允许包含任意 Shell 或未验证的 FFmpeg 字符串。

当前第四阶段实现使用 [`timeline.schema.json`](timeline.schema.json) 定义只读视频轨道、受约束 `CUT` 转场和素材/Shot/Proxy 血缘。`timeline.compose@1.0.0` 只消费 `HIGHLIGHT_SET`，不会生成或执行 FFmpeg 命令。

`TimelineValidator` 在 Artifact 创建前检查偶数画布尺寸、唯一 Clip/Shot、源时间不越过 Shot、Timeline 连续无间隙、源/目标时长一致、总时长一致以及零时长 `CUT` 白名单。校验失败时 Tool 失败且不生成 `TIMELINE`。
