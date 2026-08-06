# Redis Infrastructure

本模块实现缓存、草稿版本控制、短租约、限流、实时事件和进度发布的 Redis 适配。

## 职责

- 缓存 Tool Manifest、Planner 响应和短期查询结果。
- 支持 WebSocket 进度 Pub/Sub。
- 实现限流、取消信号和性能优化型分布式锁。
- 提供 Redis 失效后的安全降级策略。

## 当前已实现

- DAG/Gate 草稿使用 `avp:v1:{type}:draft:{owner}:{scope}:{id}` 版本化 Key；
- Redis Hash 保存 `revision` 和受控 JSON `payload`，所有草稿写入都设置 TTL；
- 使用 Lua CAS 原子校验 `draftRevision` 并递增版本，冲突返回 HTTP 409 `DRAFT_REVISION_CONFLICT`；
- 草稿读取返回 `draftRevision` 和剩余 `draftTtlSeconds`，前端自动保存会携带版本；
- 删除使用精确 Key；前缀清理使用 SCAN，不在请求路径执行阻塞式 `KEYS`；
- Redis 不可用时回退到进程内临时存储，Redis 恢复后以 Redis 为准；该回退不承诺多实例一致性。

## 边界

Redis 不保存唯一权威任务状态，清空 Redis 后系统应能从 MySQL 恢复。

草稿 CAS 只保护临时编辑状态，不替代确认 DAG 时的服务端校验、MySQL 事务和 Workflow 拓扑冻结。

