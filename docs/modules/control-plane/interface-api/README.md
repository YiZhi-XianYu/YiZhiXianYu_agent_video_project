# Interface API

本模块承载 Java 控制面对外的 REST API、WebSocket 和内部 Tool 回调入口。

## 职责

- 定义 Controller、请求响应 DTO、参数校验和异常映射。
- 处理认证授权、幂等请求头和 API 版本。
- 发布 Workflow 与 Task 实时进度。
- 接收并验证 Python Tool Service 的签名回调。

## 边界

只调用应用服务，不直接访问 Repository、Redis 或拼装 Tool 请求。

