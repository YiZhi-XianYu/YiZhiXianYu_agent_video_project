# 第十五阶段交接：RabbitMQ、Worker 横向扩展与 Redis

## 阶段结论

第十五阶段已完成核心架构升级并保留 HTTP/内存回退路径。MySQL 仍是 Workflow、Task、Attempt、Artifact 和 Outbox 的最终真相；RabbitMQ 负责任务投递与资源队列；Redis 负责临时草稿、缓存和实时性增强；OSS 继续保存视频、音频、图片等二进制 Artifact。

本阶段不实现运行中 Replan。动态 DAG 在执行前确认后冻结，Rabbit Worker 只执行已经确认的 Task。

## 已交付

- `V3__outbox.sql` 与 Outbox 实体/仓储/发布服务；Task 进入 `DISPATCHING` 与 Outbox `PENDING` 在同一个事务内完成。
- RabbitMQ Topic Exchange、LIGHT/MEDIA/MODEL/RENDER 队列、持久化消息和 DLQ 拓扑。
- Rabbit Worker 消费、幂等提交、Control Plane claim API，以及可选 `RABBITMQ_WORKER_TOKEN` 内部认证。
- 本地和生产 Compose 的 RabbitMQ、Redis 基础容器。
- `workers` profile：可独立启动 `tool-worker-media`、`tool-worker-model`、`tool-worker-render`，同一镜像按资源组消费不同队列。
- Redis best-effort 服务，Redis 不可用时回退到进程内内存。
- DAG/Gate 草稿已增加 Redis Hash + Lua CAS 版本控制、TTL 返回和 409 冲突语义；前缀清理改为 SCAN，避免请求路径使用 `KEYS`。
- DAG 草稿 API：
  - `PUT/GET/DELETE /api/v1/projects/{projectId}/dag-drafts/{draftId}`
- Gate 草稿 API：
  - `PUT/GET/DELETE /api/v1/workflow-runs/{workflowRunId}/gate-drafts/{gateKey}`
- 服务端 LLM 审计分页聚合 API：
  - `GET /api/v1/llm-audits?projectId=&page=&size=`
  - 前端审计页由一次聚合请求替代项目/Workflow/Artifact N+1 请求。
- LLM 审计列表使用 Redis 缓存 10 分钟，Redis 清空后可从 MySQL/OSS 重建。

## 启动方式

默认仍关闭 MQ/Redis，兼容原有 HTTP 路径：

```dotenv
RABBITMQ_ENABLED=false
REDIS_ENABLED=false
```

本地启用基础设施：

```powershell
docker compose up -d rabbitmq redis
```

启用 Rabbit Worker 资源组：

```powershell
$env:RABBITMQ_ENABLED="true"
$env:REDIS_ENABLED="true"
$env:RABBITMQ_DEFAULT_PASS="change-me"
$env:RABBITMQ_WORKER_TOKEN="change-me-too"
docker compose --profile workers up -d --build
```

当前临时 Docker 密码统一为 `Xrz-060625`；部署时仍可通过 `.env` 的 `RABBITMQ_DEFAULT_PASS` 覆盖。正式环境应更换为独立强密码，不要把 AccessKey、密码或 Worker token 提交到 Git。

## 回滚与故障处理

- 将 `RABBITMQ_ENABLED` 切回 `false`，Control Plane 恢复 HTTP Tool Service dispatch。
- 将 `REDIS_ENABLED` 切回 `false`，草稿/缓存 API 使用内存回退；最终 Workflow 状态不依赖 Redis。
- Worker 在 ACK 前崩溃时 Rabbit 会重新投递；Control Plane 通过 attempt/idempotencyKey 收敛重复执行。
- Schema 错误或 Poison message 使用 `avp.task.dead.v1` / `avp.task.dead.v1` 队列隔离，不进行无限 requeue。
- 不删除 Outbox、RabbitMQ 或 OSS 历史数据来“回滚”；先停新消费，再切回 HTTP。

## 验证记录

- `mvn -q -f control-plane/pom.xml -DskipTests compile` 通过。
- `python -m compileall -q tool-service/app` 通过。
- `docker compose config --quiet` 通过。
- `docker compose -f docker-compose.prod.yml config --quiet` 在当前临时密码配置下可执行；正式部署应覆盖默认密码。
- 前端 `npm run build` 当前机器缺少 `vue-tsc`，依赖安装后应补跑 `vue-tsc -b && vite build`。

## 后续建议

项目当前可以暂时收尾。若继续生产化，优先补做真实 Rabbit/Redis 启用验证、重复投递和 Worker 崩溃故障注入、DLQ 运维脚本、审计缓存失效策略以及前端 DAG/Gate 自动保存接入。
