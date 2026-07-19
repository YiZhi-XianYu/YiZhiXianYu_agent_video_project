# Java Control Plane

Java 控制面是系统的编排与业务核心，负责把用户目标转换为可验证、可执行和可恢复的工作流。

## 职责

- 提供 REST API 与 WebSocket。
- 实现 Planner、Workflow Engine、DAG Scheduler 和任务状态机。
- 管理项目、素材、上下文、Tool Registry、解释记录和版本。
- 通过标准 HTTP 协议调用 Python Tool Service。
- 使用 MySQL 保存权威状态，使用 Redis 提升实时性和调度效率。

## 架构约束

首版采用 Spring Boot 模块化单体。Java 不直接启动 Python 脚本，不执行模型，也不接受 LLM 生成的 Shell 或 FFmpeg 命令。

## 源码结构

本目录是单个 Maven/Spring Boot 工程：

```text
control-plane/
  pom.xml
  src/main/java/
  src/main/resources/
  src/test/java/
```

模块化边界通过 Java package 实现，不使用 `control-plane/modules` Maven 子模块。各逻辑模块的职责与源码映射见 [`docs/modules/control-plane`](../docs/modules/control-plane/README.md)。

当前主链路为 `MULTI_ASSET_ANALYSIS`：Java 校验 `WorkflowDefinition`，为每个关联素材展开 `video.probe -> video.proxy-generate -> video.shot-detect`，通过依赖表进行就绪扫描、并行分发和失败传播。Artifact 内容接口向浏览器提供支持 Range 请求的代理 MP4 和关键帧图片。第二阶段 `VIDEO_PROXY_PIPELINE` API 继续兼容。
