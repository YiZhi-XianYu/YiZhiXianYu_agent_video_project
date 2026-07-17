# Tool Service API

本模块实现 FastAPI 路由与协议适配。

## 职责

- 提供 Tool 列表、Manifest、Execution 创建、查询、取消和健康接口。
- 校验认证、幂等键和请求 Schema。
- 将内部执行状态映射为统一响应和错误结构。
- 触发回调但不承载具体 Tool 算法。

## 边界

API 层调用核心服务，不直接操作模型、FFmpeg 或 Worker 队列细节。

