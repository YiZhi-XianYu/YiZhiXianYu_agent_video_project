# RabbitMQ 与 Worker 生产化收口

## 本次实现

- Task 消息补齐 `messageId`、`createdAt`、`schemaVersion`、`workflowRunId`、`taskRunId`、`attempt`、`traceId`、`resourceGroup` 和 `idempotencyKey`（后者位于 request）。
- Worker 改为两阶段交接：先写入本地 SQLite，再调用 Control Plane claim；claim 成功后才调度执行，避免短任务在 MySQL 建立 ToolExecution 记录前完成回调。
- Claim API 返回 `{accepted:boolean}`。旧 attempt、终态 Task 或不匹配幂等键会返回 `accepted=false`，Worker ACK 丢弃，不执行旧消息。
- Outbox 增加 pending/failed 积压 Gauge、发布成功/失败 Counter 和发布耗时 Timer，并继续使用 Rabbit Publisher Confirm。
- 新增 `scripts/rabbit-dlq.ps1`：`list`/`peek` 只读；`replay` 必须显式 `-Force`，默认最多处理 100 条，避免误清空或无限重放。
- Worker 增加消费、ACK、拒绝入 DLQ、连接状态指标。
- Worker 结果回传升级为 SQLite Result Outbox：执行终态与待回调记录原子落盘，后台 Publisher 按指数退避持续投递，Worker 重启后继续发送。
- Control Plane Callback 按 `executionId`、`idempotencyKey` 和当前 Task attempt 收敛；旧 attempt 的迟到结果返回 HTTP 成功但不修改业务状态。

## 验收矩阵

| 场景 | 机制 | 状态 |
|---|---|---|
| 数据库提交后 Publisher 重启 | MySQL Outbox + 定时扫描 + Confirm | 已实现；需在部署环境注入重启故障做演练 |
| Rabbit 暂时不可用 | Outbox 保留 `PENDING/FAILED`，指数退避 | 已实现 |
| 重复投递 | Tool Service SQLite 唯一 `idempotencyKey`；Task attempt 幂等校验 | 已实现 |
| ACK 前 Worker 崩溃 | 未 ACK 消息由 Rabbit 重新投递；本地执行记录可恢复 | 已实现；需故障注入演练 |
| 旧 attempt/迟到 Claim | Control Plane 比对 `nodeKey:taskRunId:attempt`，返回 `accepted=false` | 已实现 |
| Poison/schema 错误 | `NACK(requeue=false)` 进入 `avp.task.dead.v1` | 已实现 |
| DLQ 查询/重放 | `scripts/rabbit-dlq.ps1`，重放需要 `-Force` | 已实现 |
| 多 Worker 分担 | 资源组独立队列 + `prefetch` + Compose workers profile | 已实现；当前 Docker 已观察到四个队列各 1 consumer |
| Rabbit 重启、Confirm 超时、网络分区 | 运行手册已有回滚路径 | 待在本地做故障注入并记录耗时 |
| 结果回调失败 | Python 有有限重试和指标；Control Plane 结果以 MySQL 为准 | 已实现；待故障注入 |

## 本地检查

```powershell
docker exec avp-rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers
.
scripts\rabbit-dlq.ps1 -Action list
.
scripts\rabbit-dlq.ps1 -Action peek -Count 10
```

只在确认消息属于当前可重试 attempt 后执行：

```powershell
.
scripts\rabbit-dlq.ps1 -Action replay -Queue avp.task.dead.v1 -Count 10 -Force
```

## 已知验证边界

- Java `mvn -q -f control-plane/pom.xml test` 已通过。
- Python `compileall` 已通过；当前环境未安装 `pytest`，Python 单测未执行。
- 本地 RabbitMQ 队列、DLQ 和多资源组 consumer 已通过 `rabbitmqctl` 只读检查。
- `docker compose -f docker-compose.prod.yml config --quiet` 需要正式部署环境提供 `SITE_ADDRESS` 等必需变量；不是代码错误。
- 最新 Control Plane、Tool Service 和三个 Worker 镜像已完成构建并滚动重启；所有应用容器运行正常。
- `CLAIM_PENDING` 修复已通过镜像内源码检查，四类队列均保持 1 个 consumer，Ready/Unacked/DLQ 均为 0。

## Agent Runtime 演进边界

RabbitMQ/Worker 是 Agent Runtime 的可靠执行内核。后续按以下顺序叠加：

```text
Result Outbox
  → Agent Trace（sessionId/turnId/planId）
  → Agent Session
  → Blackboard 投影（MySQL 真相 + Redis 快照）
  → Tool Governance（Manifest + Policy + Gate）
  → Model Router（确定性能力路由）
```

LLM 只生成受约束的候选 Workflow Definition；Tool Policy、DAG Validator 和人工 Gate 控制准入，冻结后的 Workflow 不允许运行中无约束 Replan。
