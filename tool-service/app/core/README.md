# Tool Service Core

本模块定义 Python Tool Service 的公共模型、配置、错误、日志和扩展接口。

## 职责

- 定义 Tool、ToolRequest、ToolResult、ArtifactDescriptor 等基础抽象。
- 管理配置、Trace、标准错误码和公共校验。
- 提供所有 Tool 可复用的生命周期钩子。
- 保持框架与具体 Web、队列和存储实现解耦。

## 边界

不包含具体视频算法和业务场景策略。

