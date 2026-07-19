# Shot Detection Tool

检测镜头边界，将整段视频拆分为可供分析、评分和编辑的 Shot。

## 输出

Shot ID、开始/结束时间、持续时长、边界置信度和关键帧 Artifact。

## 实现边界

结果应保持时间有序且不越界。该 Tool 只识别镜头结构，不负责选择精彩镜头。

## 当前实现

`video.shot-detect@1.0.0` 使用 FFmpeg scene score 在 CPU 上检测边界：

- 输入 `VIDEO_PROXY`；
- 参数支持 `sceneThreshold` 和 `minShotDurationMs`；
- 输出 `SHOT_LIST` JSON；
- 为每个 Shot 输出一个中点 `KEYFRAME_IMAGE`；
- 无明显切镜时输出覆盖全片的单 Shot；
- Shot 保留源 Asset、代理 Artifact、时间范围和关键帧血缘。

真实素材验证覆盖了 1920x1080/有音轨与 3840x2160/无音轨两种输入。两条视频均成功生成覆盖全片的单 Shot 和一张中点关键帧；当素材存在显著切镜时会按阈值输出多个连续 Shot。
