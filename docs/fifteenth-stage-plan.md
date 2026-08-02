# 第十五阶段计划：RabbitMQ、Worker 横向扩展与 Redis

> 计划日期：2026-08-03  
> 正式仓库：`C:\Users\XRZ\Desktop\ninth\WwDa3B884n8dj`  
> 前置条件：第十四阶段已完成指标基线、ArtifactStorage 抽象、OSS 输入物化与输出发布。  
> 阶段目标：将任务投递从单实例 HTTP/线程池扩展为 RabbitMQ 驱动的多 Worker 模式，并使用 Redis 保存可丢失的草稿、缓存和实时状态。  
> 产品边界：Workflow 在执行前动态 DAG Gate 确认后冻结；本阶段不实现运行中拓扑 Replan。

## 1. 阶段结论

RabbitMQ 和 Redis 的职责必须分开：

```text
MySQL
  = Workflow、Task、Attempt、Artifact 血缘和最终状态的唯一真相

RabbitMQ
  = Task 请求与结果事件的可靠传输、削峰和资源队列隔离

Redis
  = DAG/Gate 草稿、镜头元数据缓存、LLM 审计缓存、进度快照和 Worker 心跳

OSS
  = 素材、关键帧、代理视频、JSON、字幕、音频和最终视频
```

RabbitMQ 采用至少一次投递，系统通过 MySQL attempt/token、幂等键和 Artifact 唯一性实现幂等收敛。Redis 丢失只能导致草稿或缓存丢失，不能改变正在运行的 Workflow 和最终结果。

## 2. 当前问题

### 2.1 当前任务执行模式

- Control Plane 通过 HTTP 创建并轮询 Tool Service execution；
- Tool Service 在单进程 `ThreadPoolExecutor` 中执行所有资源组；
- `ExecutionStore` 使用本地 SQLite Journal；
- 资源组限制只能约束单个 Tool Service 实例；
- 横向启动多个 Tool Service 时，队列、幂等和恢复状态彼此不可见；
- Control Plane 的轮询频率随 Task 数量增长，产生额外 HTTP 和数据库开销；
- 单实例崩溃会同时影响 LIGHT、MEDIA、MODEL 和 RENDER 任务。

### 2.2 LLM 审计页面 N+1

当前前端加载路径为：

```text
查询所有项目
  -> 每个项目查询 Workflow 列表
  -> 每个 Workflow 查询完整运行快照
  -> 找到 STORY_PLAN Artifact
  -> 再请求 Artifact JSON
```

这是串行 N+1 请求，Redis 只能缓解重复读取，不能替代服务端分页聚合 API。第十五阶段必须先修复查询模型，再增加缓存。

### 2.3 临时编辑状态

- 动态 DAG 画布当前只存在前端内存；
- 刷新页面会丢失未确认节点、连线和布局；
- 镜头排序、Story Plan、Timeline 和 BGM 的未提交编辑缺少统一草稿；
- 镜头元数据会在不同 Gate 重复解析不可变 Artifact；
- Workflow 进度依赖频繁轮询完整快照。

## 3. 架构原则

1. **数据库状态优先于消息状态**：MQ 中没有“唯一真相”；
2. **至少一次，不承诺恰好一次**：所有消费者必须幂等；
3. **消息只传小数据**：不传视频、关键帧、大 JSON、Prompt 全文或二进制；
4. **OSS 是横向 Worker 前提**：Worker 不依赖共享磁盘；
5. **业务重试与传输重试分离**：Task attempt 由 Control Plane 管理，MQ 只处理投递与消费故障；
6. **Redis 可丢失**：Redis 清空后系统仍能从 MySQL/OSS 继续运行；
7. **先消除 N+1，再缓存**；
8. **渐进迁移**：HTTP/轮询路径保留为回滚模式，RabbitMQ 通过配置开启；
9. **拓扑冻结**：Redis 草稿只存在于确认前或业务 Gate 内，不修改运行中 Workflow Definition。

## 4. RabbitMQ 拓扑

### 4.1 Exchange 与队列

建议使用版本化 Topic Exchange：

```text
avp.task.v1
  task.light.requested  -> avp.task.light.v1
  task.media.requested  -> avp.task.media.v1
  task.model.requested  -> avp.task.model.v1
  task.render.requested -> avp.task.render.v1

avp.result.v1
  task.succeeded -> avp.result.control-plane.v1
  task.failed    -> avp.result.control-plane.v1
  task.progress  -> avp.progress.control-plane.v1（可选、限频）

avp.dead.v1
  -> avp.task.dead.v1
  -> avp.result.dead.v1
```

- 队列、Exchange、消息均持久化；
- Publisher Confirm 必须开启；
- Consumer 使用手动 ACK；
- 失败消息进入 DLQ，不做无限 requeue；
- 业务重试由 Control Plane 创建下一 attempt 并发布新消息；
- 延迟重试优先复用数据库 `nextAttemptAt` 扫描，不依赖 RabbitMQ 延迟插件。

### 4.2 消息契约

Task 消息只包含执行所需的小型结构和引用：

```json
{
  "schemaVersion": "1.0",
  "messageId": "...",
  "occurredAt": "...",
  "workflowRunId": "...",
  "taskRunId": "...",
  "attempt": 1,
  "dispatchToken": "...",
  "idempotencyKey": "...",
  "tool": "video.shot-detect",
  "toolVersion": "1.0.0",
  "resourceGroup": "MEDIA",
  "inputArtifactIds": ["..."],
  "parameterRef": "taskRunId"
}
```

推荐 Worker 使用内部 API 按 `taskRunId + dispatchToken` 获取完整、已授权的执行请求，避免消息携带过多参数和签名 URL。结果消息包含：

```json
{
  "schemaVersion": "1.0",
  "messageId": "...",
  "workflowRunId": "...",
  "taskRunId": "...",
  "attempt": 1,
  "dispatchToken": "...",
  "executionId": "...",
  "status": "SUCCEEDED",
  "outputs": [{ "artifactId": "...", "type": "...", "storageUri": "..." }],
  "error": null
}
```

所有消息使用 JSON Schema/契约测试，未知主版本拒绝，兼容字段只能向后追加。

## 5. Transactional Outbox

为避免数据库更新成功但消息发送失败，Control Plane 增加 Outbox：

```text
同一 MySQL 事务：
  Task -> DISPATCHING
  Task attempt/token -> 固化
  Outbox -> TASK_REQUESTED/PENDING

Outbox Publisher：
  查询待发送记录
  -> 发布 RabbitMQ
  -> 等待 Publisher Confirm
  -> 标记 PUBLISHED
```

要求：

- Outbox 记录包含稳定 `messageId` 和聚合键；
- 发布器可多实例运行，使用数据库锁/跳过锁定行避免重复争抢；
- 即使重复发布，Worker 和结果消费者也必须幂等；
- 发布失败指数退避并记录指标；
- Outbox 有保留与归档策略，不能无限增长；
- 不修改已发布的旧 Flyway Migration，只新增向前 Migration。

## 6. Worker 横向扩展

### 6.1 Worker 类型

同一 Tool Service 代码镜像按环境变量启动不同消费组：

| Worker | 队列 | 典型 Tool | 默认并发策略 |
| --- | --- | --- | --- |
| LIGHT | `avp.task.light.v1` | Ranking、Story、Timeline | CPU 轻量，可多并发 |
| MEDIA | `avp.task.media.v1` | Probe、Proxy、Shot Detect | 磁盘/CPU 受控 |
| MODEL | `avp.task.model.v1` | VLM、Whisper | 内存/GPU 独占或低并发 |
| RENDER | `avp.task.render.v1` | FFmpeg Render | 独占或严格限流 |

RabbitMQ `prefetch` 必须与 Worker 实际并发匹配，不能预取大量重任务导致其他 Worker 空闲。

### 6.2 Worker 执行语义

```text
消费 Task 消息
  -> 校验 schema/version
  -> 调用 Control Plane claim/prepare API
  -> attempt/token 不匹配则 ACK 丢弃旧消息
  -> 从 OSS 物化输入
  -> 本地执行 Tool
  -> 输出上传 OSS 并校验
  -> 发布结果事件并等待 Confirm
  -> ACK Task 消息
```

- Worker 崩溃前未 ACK，消息会重新投递；
- Control Plane claim 必须原子判断 Task、attempt 和租约；
- 同一 attempt 只能有一个有效 lease；
- lease 超时后允许重新投递，但旧 Worker 的迟到结果因 token 不匹配被拒绝；
- Artifact 以 external ID/内容 hash 幂等写入，重复结果不能产生重复血缘；
- 结果发布成功但 ACK 前崩溃时，重复执行或重复结果必须安全收敛；
- SQLite Journal 可保留为 Worker 本地诊断和进程恢复记录，但不再作为跨 Worker 真相来源。

### 6.3 进度事件

进度不是最终状态，可以限频：

- 每个 execution 最多每 1～2 秒发布一次；
- 仅进度增长达到阈值时发布；
- Progress 消息允许丢失或覆盖；
- Task 完成/失败结果不得丢失；
- Control Plane 将最新进度写入 MySQL，Redis 可保存更高频的临时快照供 SSE 使用。

## 7. Redis 使用范围

### 7.1 动态 DAG 草稿

```text
dag:draft:{userId}:{projectId}:{draftId}
```

保存：

- 自然语言目标与目标时长；
- 候选/默认 DAG 来源；
- 能力选择；
- 节点和连线编辑；
- 画布坐标、缩放和高度；
- 服务端校验结果；
- `version`、创建时间、更新时间。

规则：

- TTL 建议 24 小时；
- 使用版本号/CAS 或 Lua 做乐观锁；
- 只能由所属用户和项目访问；
- 确认时以请求内容重新运行服务端校验并写入 MySQL；
- MySQL 创建 Workflow 成功后删除草稿；
- Redis 丢失只损失未确认草稿。

### 7.2 运行中业务 Gate 草稿

```text
review:draft:{userId}:{workflowRunId}:{gateKey}
```

保存尚未提交的镜头排除/强制选择、Story Plan 编辑、Timeline 编辑和 BGM 选择。确认后仍生成既有不可变 Artifact，再删除 Redis 草稿。

- TTL 建议 2～24 小时；
- 草稿不能改变 Workflow Definition；
- Gate 已继续或 Workflow 已终止时草稿失效；
- 多标签页使用 version 防止覆盖。

### 7.3 镜头元数据缓存

```text
shot:metadata:{artifactContentHash}
shot:list:{workflowRunId}:{rankingHash}
```

缓存从不可变 `SHOT_LIST`、`SHOT_RANKING` 和关键帧 Artifact 组装出的镜头摘要：

- shotId、素材来源、起止时间；
- keyframe Artifact ID；
- proxy Artifact ID；
- 评分与选中状态；
- 不保存 JPEG、视频或音频二进制；
- 使用 Artifact content hash 作为版本，TTL 可设 24 小时或更长；
- 缓存未命中从 MySQL/OSS 重建。

### 7.4 LLM 审计读缓存

先新增后端聚合分页 API：

```text
GET /api/v1/llm-audits?projectId=&page=&size=
GET /api/v1/llm-audits/{auditId}
GET /api/v1/llm-audits/stats?projectId=
```

列表只返回 provider、model、latency、结果、错误摘要、项目和 Workflow；完整 Prompt/响应仅在详情中按需加载。

Redis Key：

```text
llm:audit:list:{userId}:{projectId|all}:{page}:{size}:{sort}
llm:audit:stats:{userId}:{projectId|all}
llm:audit:detail:{auditId}:{contentHash}
```

- 先由后端批量查询 Workflow/Artifact 索引，消除前端 N+1；
- 摘要缓存 TTL 建议 15～60 分钟；
- 不可变详情可按 content hash 缓存 6～24 小时；
- 新审计产生时失效对应用户/项目的列表与统计；
- 不长期缓存超长 Prompt 和原始响应；
- 前端使用分页、详情懒加载和列表虚拟化（记录量大时）。

### 7.5 进度、心跳和限流

可选 Key：

```text
workflow:progress:{workflowRunId}
worker:heartbeat:{workerId}
worker:capacity:{resourceGroup}:{workerId}
rate-limit:{scope}:{identity}:{window}
```

- 进度和心跳 TTL 为秒级/分钟级；
- Worker 是否能领取任务最终仍由 RabbitMQ、Control Plane lease 和数据库状态决定；
- Redis 分布式锁不用于替代数据库唯一约束和事务；
- Session 是否迁移 Redis 单独评估，多 Control Plane 实例确有需要时再做。

## 8. Redis 数据治理

- 所有缓存与草稿 Key 必须设置 TTL；
- 配置明确内存上限和淘汰策略，避免挤占宿主机；
- Key 带版本前缀，例如 `avp:v1:`；
- value 使用受控 JSON/Hash，不使用 Java/Python原生对象序列化；
- 单条草稿和缓存设置大小上限；
- 监控命中率、内存、eviction、过期量和慢命令；
- 禁止 `KEYS *`，列表失效使用索引集合、版本号或短 TTL；
- Redis 不保存二进制媒体、最终业务状态、Artifact 血缘和唯一审计副本；
- 本地开发可关闭 Redis，所有读缓存必须有直读回退。

## 9. 迁移与回滚

### 配置开关

```dotenv
TASK_DISPATCH_MODE=http|rabbit
RABBITMQ_ENABLED=false
REDIS_ENABLED=false
DAG_DRAFT_STORAGE=memory|redis
REVIEW_DRAFT_STORAGE=memory|redis
LLM_AUDIT_CACHE_ENABLED=false
WORKFLOW_PROGRESS_CACHE_ENABLED=false
```

### 上线顺序

1. 发布消息契约、Outbox 表和指标，但保持 HTTP dispatch；
2. 启动 RabbitMQ、Redis 与 Worker，做影子消息验证，不执行真实任务；
3. 只将 LIGHT 队列切到 RabbitMQ；
4. 再迁移 MEDIA、MODEL；
5. 最后迁移 RENDER；
6. 稳定后停止旧轮询，但保留配置回滚能力；
7. Redis 功能按 DAG 草稿、审计缓存、镜头缓存、进度依次开启。

回滚时：

- 停止发布新 MQ Task，等待或隔离在途消息；
- 将 `TASK_DISPATCH_MODE` 切回 HTTP；
- MySQL 中 Task 状态和 attempt 继续作为恢复依据；
- Redis 可直接禁用，不能要求从 Redis 恢复最终状态；
- 不通过清空 RabbitMQ、删除 Outbox 或修改历史 Migration 粗暴回滚；
- OSS 中已成功上传的 Artifact 保持不变。

## 10. 分阶段实施

### 15.0 契约与故障模型

- 定义 Task/Result/Progress Schema；
- 明确 attempt、dispatchToken、lease 和幂等规则；
- 建立 RabbitMQ/Redis 本地 Compose 和监控；
- 编写重复投递、乱序、迟到结果和 Worker 崩溃测试。

### 15.1 LLM 审计 API 重构

- 后端聚合分页查询；
- 前端改为一次列表请求与详情懒加载；
- 建立改造前后的请求数、首屏耗时和数据量对照；
- 此步骤即使 Redis 关闭也必须明显消除 N+1。

### 15.2 Redis 草稿与缓存

- DAG 草稿自动保存与恢复；
- Gate 草稿自动保存与版本冲突提示；
- 镜头元数据按 Artifact hash 缓存；
- LLM 审计分页、统计与详情缓存；
- 进度快照与 SSE 可作为后续子项。

### 15.3 RabbitMQ 与 Outbox

- 新增向前数据库 Migration；
- Outbox Publisher + Confirm；
- Exchange、队列、DLQ 和监控；
- HTTP/Rabbit 双模式；
- LIGHT 任务首批迁移。

### 15.4 Worker 消费与横向扩展

- Worker profile 和资源队列；
- claim/lease/token；
- OSS 输入物化与输出发布；
- Result Consumer 幂等更新 MySQL；
- MEDIA、MODEL、RENDER 逐类迁移。

### 15.5 故障注入与压测

- RabbitMQ 重启、网络分区、Publisher Confirm 超时；
- 重复消息、乱序结果、旧 attempt、DLQ；
- Worker 执行中崩溃、输出上传后崩溃、结果发布后 ACK 前崩溃；
- Redis 清空、重启、超时和缓存击穿；
- 1/3/5/10 Worker 的吞吐与资源对照；
- 与第十四阶段单实例基线比较。

### 15.6 文档与收口

- 更新部署、运维、队列、缓存和故障排查文档；
- 明确容量规划、扩容步骤、DLQ 处理和 Redis 内存预算；
- 输出第十五阶段交接及真实压测报告。

## 11. 验收矩阵

### RabbitMQ/Outbox

- 数据库提交后发布器崩溃，消息最终仍能发送；
- 消息重复发布不会重复创建有效 Task attempt；
- RabbitMQ 暂时不可用不会丢 Task；
- DLQ 可查看、告警和人工重新驱动；
- 旧 schema 主版本被拒绝且不无限重试。

### Worker

- 同一队列启动多个 Worker 能横向分担任务；
- Worker 崩溃后任务重新投递并最终收敛；
- 旧 lease/attempt 的迟到结果被拒绝；
- 输出上传成功但结果重复时只保留一份 Artifact 血缘；
- MODEL/RENDER 并发限制不会被多实例意外突破；
- 不挂载 shared runtime Volume 也能执行完整 Workflow。

### Redis

- Redis 关闭时系统仍可运行，只失去草稿自动恢复和缓存加速；
- DAG 草稿刷新后可恢复，确认后被清理；
- 多标签页编辑产生明确版本冲突，不静默覆盖；
- Gate 草稿不能修改 Workflow Definition；
- 镜头缓存与 Artifact hash 一致；
- LLM 审计页面不再产生逐 Workflow/Artifact 请求；
- Redis 清空后审计可从 MySQL/OSS 重建。

### 性能

- 记录 HTTP 与 Rabbit 模式的吞吐、排队和端到端耗时对照；
- Worker 数量增加时吞吐在资源未饱和区间可验证提升；
- LLM 审计记录量增长时首屏请求数保持常数级；
- Redis 命中率、内存、eviction 和慢命令可观测；
- MQ backlog、消息年龄、消费速率和 DLQ 可观测。

## 12. 完成标准

第十五阶段完成必须同时满足：

- MySQL + Outbox 能可靠驱动 RabbitMQ 投递；
- LIGHT/MEDIA/MODEL/RENDER Worker 可独立部署和横向扩容；
- 重复投递、Worker 崩溃、旧 attempt 和迟到结果均能幂等收敛；
- 生产 Worker 不依赖共享本地文件系统；
- Redis 已用于 DAG/Gate 草稿、镜头元数据和 LLM 审计缓存，且所有功能都有无 Redis 回退；
- LLM 审计 N+1 被后端分页聚合 API 根治；
- HTTP dispatch 可作为阶段性回滚路径；
- RabbitMQ、Redis、Worker 与业务指标均进入 Dashboard；
- 故障注入、容量压测和真实多素材 Workflow 通过；
- 动态 DAG Gate 确认后拓扑冻结的产品原则未被破坏。

## 13. 非目标

- 不实现运行中 Workflow 拓扑 Replan；
- 不用 Redis 保存 Workflow/Task 最终状态或 Artifact 唯一副本；
- 不用 RabbitMQ 传输媒体文件和超大 JSON；
- 不追求“恰好一次”营销口径，以至少一次 + 幂等收敛为准；
- 不在本阶段引入 Kafka、Kubernetes 或服务网格；
- 不因为引入 MQ 就删除现有重试、恢复、Artifact 校验和审计机制。

