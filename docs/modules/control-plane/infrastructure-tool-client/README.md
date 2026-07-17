# Tool Client Infrastructure

本模块实现 Java 对 Python Tool Service 的标准 HTTP 客户端。

## 职责

- 创建、查询和取消 Tool Execution。
- 透传幂等键、Trace Context 和签名信息。
- 处理超时、连接错误、重试分类和响应 Schema。
- 提供 Tool Manifest 同步和健康检查客户端。

## 边界

不在客户端实现业务重试策略或状态机；这些规则属于 Execution Domain。

