# LLM Provider Infrastructure

本模块为 Planning Domain 和 Explanation Domain 提供统一的大模型访问适配层，使上层领域逻辑不依赖某一家云端或本地模型。

## 职责

- 适配支持结构化输出和 Tool/Function Calling 的大模型 API。
- 管理模型选择、系统提示词、超时、重试、限流和调用取消。
- 透传 Trace，记录 Token 使用量、延迟、模型版本和估算成本。
- 支持云端模型、本地模型或兼容 OpenAI API 的服务之间切换。
- 对发送给模型的上下文进行裁剪、脱敏和大小控制。
- 校验模型返回的 JSON，并将响应映射为领域层定义的结构化对象。

## 安全边界

- 不向大模型提供数据库凭据、对象存储密钥或任意系统命令执行能力。
- 默认只发送用户目标、Tool Manifest、Video Knowledge 摘要和必要证据，不发送完整原始视频。
- 大模型输出永远视为不可信输入，必须由 Planning Domain 和 Workflow Validator 校验。

## 演进方向

首版可以接入一个主要模型和一个模板降级路径；后续可增加模型路由、响应缓存、质量评估和多模型回退。
