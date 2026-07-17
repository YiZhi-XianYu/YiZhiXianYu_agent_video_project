# Frontend API Layer

本模块封装 REST、WebSocket、上传会话和统一错误处理。

## 职责

- 管理 API Base URL、认证、重试和取消。
- 将后端 DTO 映射为前端类型。
- 处理 Workflow 事件序号和 REST 快照补偿。

业务页面不得散落重复的底层网络调用。

