# Python AI Tool Service

Python Tool Service 是系统的能力执行面，通过统一 HTTP API 暴露视频分析、AI 推理、规划辅助和 FFmpeg 渲染能力。

## 职责

- 加载和注册版本化 Tool。
- 校验 Tool 输入输出 Schema。
- 创建异步 Tool Execution，并支持查询、回调、取消和幂等。
- 调度 CPU/GPU Worker，管理模型与媒体处理资源。
- 将大型结果写入对象存储，只返回 Artifact 描述。

## 架构约束

每项能力都是独立 Tool。Tool 不直接修改 Java 工作流状态，也不把大型二进制数据塞入 JSON。

## 当前 Tool

- `video.probe@1.0.0`：生成 `VIDEO_METADATA`；
- `video.proxy-generate@1.0.0`：生成浏览器兼容的 `VIDEO_PROXY`，并报告 FFmpeg 转码进度。
