# Redis Infrastructure

本模块实现缓存、短租约、限流、实时事件和进度发布的 Redis 适配。

## 职责

- 缓存 Tool Manifest、Planner 响应和短期查询结果。
- 支持 WebSocket 进度 Pub/Sub。
- 实现限流、取消信号和性能优化型分布式锁。
- 提供 Redis 失效后的安全降级策略。

## 边界

Redis 不保存唯一权威任务状态，清空 Redis 后系统应能从 MySQL 恢复。

