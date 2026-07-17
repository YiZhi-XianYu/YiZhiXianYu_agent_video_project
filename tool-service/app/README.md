# Tool Service 运行框架

本目录保存所有 Tool 共用的服务框架，而不是具体视频能力。

## 职责

- FastAPI 入口和统一协议。
- Tool 注册、发现、校验和执行生命周期。
- Artifact 存取、回调、幂等、日志、Trace 与错误映射。
- Worker 调度和取消信号的基础能力。

## 边界

具体算法、模型和媒体逻辑必须放在 `tool-service/tools/`，避免核心框架与某个 Tool 强耦合。

