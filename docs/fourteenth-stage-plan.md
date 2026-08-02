# 第十四阶段交接：可观测性、压测基线与 Artifact Storage / OSS

> 交接日期：2026-08-03
> 正式仓库：`C:\Users\XRZ\Desktop\ninth\WwDa3B884n8dj`  
> 本文已由实施计划更新为阶段交接记录：先建立可复现的性能与容量基线，再将素材和 Artifact 从单机共享目录迁移为可切换的本地/阿里云 OSS 存储。
> 范围边界：本阶段不引入 RabbitMQ、Redis、运行中 Replan，也不重写现有 Workflow 状态机。

## 1. 阶段结论

第十四阶段包含两个有先后依赖的交付：

1. **可观测性与压测基线**：回答系统时间花在哪里、资源瓶颈是什么、单机并发上限在哪里；
2. **Artifact Storage 抽象与 OSS**：消除 Control Plane、Tool Service 和共享 Docker Volume 对同一台机器文件系统的依赖，为后续 Worker 横向扩展提供前置条件。

推荐目标链路：

```text
浏览器
  -> Control Plane 鉴权与业务 API
  -> 素材/Artifact Content API
  -> 本地文件响应（开发环境）
     或 OSS 短时签名 URL / Range 下载（生产环境）

Control Plane
  -> MySQL 保存业务状态、对象标识、hash 与血缘
  -> ArtifactStorage 接口

Tool Service
  -> 执行前将远端输入物化到本地临时工作目录
  -> Tool 继续处理本地 Path
  -> 执行成功后发布输出到 ArtifactStorage
  -> 回调只返回持久化后的 storage URI
```

OSS 的价值是解除单机磁盘绑定和大文件传输瓶颈，不保证 FFmpeg 或模型推理本身变快；单条 Workflow 的计算性能仍需要根据基线数据单独优化。

## 2. 当前基线与问题

### 2.1 当前存储耦合

- `LocalStorageService` 将上传素材直接写入 `app.storage.root`；
- `ArtifactController` 和 `ProjectController.assetContent()` 只接受 `file:` URI；
- Python Tool 直接从 `TOOL_SERVICE_ARTIFACT_ROOT` 读写输出；
- Control Plane 与 Tool Service 通过 Docker `shared_runtime` Volume 共享同一目录；
- `WorkflowExecutionService` 仍有直接创建 Timeline、BGM、Story Plan 等本地 Artifact 文件的路径；
- 多 Worker 或跨主机部署时，`file:` URI 对其他实例不可见；
- 视频下载目前经过 Java 本地文件响应，无法直接利用 OSS Range、带宽和生命周期策略。

### 2.2 当前可观测性缺口

- 没有统一的 HTTP、Workflow、Task、Tool、Artifact 和资源指标；
- Control Plane 只能看到 Task 状态，不能区分排队、下载、计算、上传和回调耗时；
- Tool Service 已有资源分组与并发限制，但缺少队列长度、等待时间和资源占用指标；
- 日志缺少统一的 `projectId/workflowRunId/taskRunId/executionId/artifactId` 关联字段；
- 没有固定素材集、固定并发模型和可重复的压测报告；
- 目前无法量化共享磁盘、MySQL、网络、FFmpeg 或模型究竟谁是主要瓶颈。

## 3. 设计原则

1. **先测量再优化**：不以“引入了 OSS”代替性能结论；
2. **MySQL 仍是真相来源**：对象存储只保存二进制和不可变 JSON；
3. **私有 Bucket**：所有访问先经过项目权限校验，再返回短时签名地址；
4. **URI 与实现解耦**：业务层不再判断 `file:` 或拼接本地 Path；
5. **Tool 保持本地文件语义**：在执行边界统一物化输入、发布输出，避免逐个重写媒体 Tool；
6. **Artifact 不可变**：对象 Key 与 Artifact ID 绑定，不覆盖同名对象；
7. **本地开发不依赖云服务**：默认继续使用 Local Provider；
8. **可回滚**：OSS 故障时可切回 Local Provider，新旧 `storageUri` 均能读取；
9. **不提交凭据**：AccessKey、Secret、STS Token 和 Bucket 配置只来自环境变量或密钥服务。

## 4. 目标可观测性

### 4.1 Control Plane 指标

建议引入 Spring Boot Actuator、Micrometer 和 Prometheus Registry，至少暴露：

```text
http.server.requests
workflow.created.total
workflow.completed.total{status,autoMode}
workflow.duration.seconds{definitionKey,status}
workflow.active
workflow.paused
task.queue.wait.seconds{tool,resourceGroup}
task.execution.duration.seconds{tool,status}
task.retry.total{tool,reason}
task.poll.failure.total{tool}
artifact.metadata.created.total{type,provider}
artifact.content.redirect.total{provider,type}
artifact.content.error.total{provider,reason}
storage.operation.duration.seconds{provider,operation}
storage.bytes.total{provider,direction,type}
```

禁止在指标标签中放入 `projectId`、`runId`、文件名或 Artifact ID，避免高基数；这些标识只进入结构化日志。

### 4.2 Tool Service 指标

建议使用 `prometheus-client`，至少暴露：

```text
tool_execution_total{tool,status,resourceGroup}
tool_execution_duration_seconds{tool,status}
tool_queue_wait_seconds{resourceGroup}
tool_queue_depth{resourceGroup}
tool_active{resourceGroup}
tool_progress_stall_total{tool}
tool_input_materialize_duration_seconds{provider,type}
tool_output_publish_duration_seconds{provider,type}
tool_storage_bytes_total{direction,type}
tool_callback_duration_seconds{status}
tool_callback_failure_total{reason}
process_resident_memory_bytes
process_cpu_seconds_total
```

模型加载、释放和 Render 应额外记录事件日志，但模型名不应成为无限增长的指标标签。

### 4.3 结构化日志与关联 ID

Java 和 Python 日志统一携带可用的上下文字段：

```text
requestId
projectId
workflowRunId
taskRunId
executionId
toolName
attempt
artifactId
storageProvider
```

- 浏览器请求产生或透传 `X-Request-Id`；
- Control Plane 调用 Tool Service 时透传关联信息；
- Tool 回调保留原 `executionId/taskRunId/attempt`；
- 日志不输出 AccessKey、Cookie、Prompt 全文、预签名 URL 查询参数和用户原始文件内容。

### 4.4 Dashboard 与告警

本地 Compose 增加可选的 Prometheus + Grafana profile，不强制所有开发者常驻启动。至少提供：

1. API 延迟与错误率；
2. Workflow 吞吐、状态和端到端时长；
3. 各资源组排队、执行时长和并发；
4. CPU、内存、磁盘、网络；
5. Artifact 上传/下载吞吐与错误；
6. Tool 回调失败、重试和恢复；
7. OSS 请求失败、超时和限流。

告警阈值在完成基线后再确定，不在没有数据时虚构 P95/P99 目标。

## 5. 压测基线设计

### 5.1 固定测试素材集

在不提交大体积视频的前提下，记录一套可复现的测试素材清单：

| 档位 | 数量与时长 | 分辨率 | 用途 |
| --- | --- | --- | --- |
| S | 2 个 × 10～20 秒 | 720p | 快速回归 |
| M | 3 个 × 30～60 秒 | 1080p | 标准容量测试 |
| L | 5 个 × 2～5 分钟 | 1080p/4K | 磁盘、网络、Render 压力 |

素材文件放在仓库外或专用测试 Bucket；仓库只保存 hash、媒体属性和获取说明。

### 5.2 场景矩阵

每个场景至少重复 3 次，记录冷启动和热缓存两类结果：

1. 项目列表、项目详情、Workflow 历史和运行快照 API；
2. 单文件与多文件上传；
3. 关键帧、代理视频、最终视频的首字节、完整下载和 Range 请求；
4. 1 个 Workflow 串行执行；
5. 3 个并发 Workflow；
6. 5 个并发 Workflow；
7. VLM/Whisper 模型冷启动与热启动；
8. Render 与模型任务竞争资源；
9. 服务重启后的任务恢复；
10. Local Provider 与 OSS Provider 对照。

如果 5 并发已经触发稳定性问题，不继续无意义提高并发，先记录饱和点和失败模式。

### 5.3 必须记录的数据

- 请求数、吞吐、错误率、P50/P95/P99；
- Workflow 总耗时、Task 排队时间、Task 执行时间；
- 输入下载、Tool 计算、输出上传、回调各阶段耗时；
- CPU 峰值/均值、RSS、磁盘吞吐、网络吞吐；
- MySQL 连接数、慢查询、锁等待；
- 各资源组队列长度与饱和时间；
- 每个 Workflow 的输入/输出字节数、Artifact 数量；
- 失败、重试、恢复次数和最终状态；
- OSS 请求次数、流量和估算成本。

### 5.4 工具与产物

- API/上传/下载压测优先使用 `k6`；
- 容器资源使用 Docker Stats/Prometheus；
- JVM 使用 Micrometer/JVM 指标；
- Python 使用进程和自定义执行指标；
- 结果保存为带日期、Git commit、环境配置和素材 hash 的 Markdown/JSON 报告；
- 第十四阶段必须分别产出改造前 Local 基线和改造后 Local/OSS 对照报告。

## 6. Artifact Storage 抽象

### 6.1 Java 接口边界

将当前 `LocalStorageService` 收敛为统一接口，建议能力如下：

```java
interface ArtifactStorage {
    StoredObject put(StorageWriteRequest request);
    InputStream open(String storageUri);
    ResourceMetadata stat(String storageUri);
    URI createReadUrl(String storageUri, ReadOptions options);
    void deleteTemporary(String storageUri);
}
```

实际命名可按现有风格调整，但业务层只依赖接口。首批实现：

- `LocalArtifactStorage`：兼容现有 `file:` URI；
- `OssArtifactStorage`：使用私有阿里云 OSS Bucket；
- `ArtifactStorageRouter`：按 URI scheme 或 provider 读取历史与新对象。

素材和 Tool 输出可以共享底层存储接口，但对象类别、Key 前缀和生命周期策略必须区分。

### 6.2 OSS Object Key

对象 Key 不包含用户文件原名和敏感信息，建议：

```text
projects/{projectId}/assets/{assetId}/source/{contentHash}.{ext}
projects/{projectId}/artifacts/{artifactId}/{type}/{contentHash}.{ext}
temporary/{executionId}/{randomName}
```

- 正式素材和 Artifact Key 不覆盖；
- 原始文件名只保存在 MySQL 元数据；
- 临时对象设置短生命周期；
- 已确认 Artifact 不使用自动过期策略；
- 删除项目或素材时仍遵循软删除与血缘规则，不能直接删除历史 Artifact。

### 6.3 上传策略

分两步上线，降低风险：

1. **兼容上传**：浏览器仍上传到 Control Plane，服务端流式写入 OSS，先验证存储抽象；
2. **直传优化**：增加初始化、分片上传、完成确认 API，浏览器通过短时签名直接上传 OSS。

直传必须满足：

- 服务端验证项目所有权、文件大小、媒体类型和对象 Key；
- 使用 STS 或单对象短时签名，禁止把长期 AccessKey 发给浏览器；
- 大文件使用 multipart upload，支持失败重试和终止未完成分片；
- 完成接口重新 `HEAD` 对象并核对大小、ETag/hash 后创建 Asset；
- Bucket CORS 只开放正式前端来源和必要方法；
- 设置单文件与项目级配额，防止绕过 Java multipart 限制。

### 6.4 下载与预览

前端继续使用现有受保护的 `contentUrl`，后端完成权限校验后：

- Local Provider：返回支持 Range 的本地资源响应；
- OSS Provider：返回短时签名 URL 的 302/307，或在确有需求时代理流式响应；
- `download=true` 通过签名参数设置 Content-Disposition；
- 图片、视频、音频和 JSON 都保持正确的 Content-Type；
- 签名 TTL 建议 5～15 分钟，不缓存带签名的完整 URL；
- 视频预览必须验证 `206 Partial Content`、拖动和断点请求。

### 6.5 Tool Service 输入物化与输出发布

为避免逐个 Tool 改写，优先在 `ExecutionService` 边界处理：

```text
收到 ToolExecutionRequest
  -> 为 executionId 创建隔离工作目录
  -> 将 oss:// 输入下载并校验 hash/size
  -> 替换为本地 file:// 临时 URI
  -> 原 Tool 按现有 Path 逻辑执行
  -> 校验输出文件和 Descriptor
  -> 上传正式 Artifact 到 OSS
  -> 将输出 URI 替换为 oss://bucket/key
  -> 持久化执行记录并回调
  -> 清理本地临时目录
```

- 下载和上传必须有超时、重试和校验；
- 输出未成功上传前不得回调 `SUCCEEDED`；
- 回调失败时保留已上传对象与执行记录，重试回调不能重复计算；
- Worker 本地目录只作为可清理缓存，不是业务真相；
- 本地模式继续走 `file:`，不进行无意义复制；
- Control Plane 自己生成的 Timeline、Story Plan、BGM Selection 等 Artifact 也必须通过同一存储抽象写入。

## 7. 配置与安全

建议配置项：

```dotenv
ARTIFACT_STORAGE_PROVIDER=local|oss
OSS_ENDPOINT=
OSS_REGION=
OSS_BUCKET=
OSS_ACCESS_KEY_ID=
OSS_ACCESS_KEY_SECRET=
OSS_ROLE_ARN=
OSS_SIGNED_URL_TTL_SECONDS=600
OSS_MULTIPART_THRESHOLD_MB=100
OSS_MULTIPART_PART_SIZE_MB=16
TOOL_WORKSPACE_ROOT=/tmp/agent-video
TOOL_WORKSPACE_RETENTION_HOURS=6
```

生产环境优先使用 ECS RAM Role/STS，不使用长期静态密钥。Bucket 开启：

- 私有读写；
- 服务端加密；
- 传输强制 HTTPS；
- 访问日志与审计；
- 临时前缀生命周期；
- 未完成 multipart upload 自动清理；
- 最小权限策略，仅允许指定 Bucket 和前缀。

## 8. 分阶段实施

### 14.0 基线冻结与测试工具

- 固定环境、素材清单和配置；
- 增加 k6 场景与报告模板；
- 记录当前 Local + shared Volume 基线；
- 保存当前瓶颈、饱和点和失败模式。

### 14.1 指标与结构化日志

- Java Actuator/Micrometer/Prometheus；
- Python Prometheus 指标；
- 统一关联 ID 和日志字段；
- 增加可选 Prometheus/Grafana Compose profile；
- 完成 Dashboard 与基础告警模板。

### 14.2 Java Storage 抽象

- 提取 `ArtifactStorage` 接口；
- 迁移素材上传、素材下载和 Artifact 下载；
- 迁移 Control Plane 自生成 Artifact；
- Local Provider 行为与现有 API 保持兼容；
- 增加 provider 路由与历史 `file:` URI 兼容测试。

### 14.3 Tool Service Storage 边界

- 增加输入物化、输出发布和执行工作目录；
- 保持各媒体 Tool 的本地 Path 接口；
- 增加 hash、size、清理和回调重试测试；
- 去除生产环境对 shared runtime Volume 的必需依赖。

### 14.4 OSS Provider

- Java/Python OSS 客户端与配置；
- 私有 Bucket、签名读取、Range 和下载文件名；
- 服务端流式上传；
- 直传 multipart API 与前端上传进度；
- 临时对象和未完成分片清理。

### 14.5 对照压测与收口

- Local 改造后回归，确认抽象没有明显退化；
- OSS 冷/热读取与多并发 Workflow 压测；
- 记录性能、稳定性和成本对照；
- 更新 README、部署文档、故障排查和阶段交接。

## 9. 测试矩阵

### 单元与契约

- Local/OSS URI 路由；
- Object Key 安全与路径穿越；
- hash、size、Content-Type 和文件名；
- 签名 TTL 与下载模式；
- 输入物化和输出发布；
- OSS 超时、重试、限流和权限错误；
- 输出上传成功但回调失败；
- 历史 `file:` Artifact 与新 `oss:` Artifact 共存。

### 集成

- 素材上传、列表、预览、软删除；
- 关键帧、代理视频、音频、JSON、SRT 和最终视频；
- 视频 Range 与拖动预览；
- 多素材完整 Workflow；
- Tool Service 重启后的执行恢复；
- OSS 临时不可用后的明确失败与重试；
- Local/OSS 配置切换不修改业务代码。

### 安全

- 跨用户、跨项目对象访问拒绝；
- 伪造 storage URI、对象 Key 和媒体类型拒绝；
- 过期签名不可用；
- 浏览器无法获得长期 OSS 凭据；
- 日志和指标不泄漏签名参数、Cookie 或 AccessKey。

## 10. 完成标准

### 当前实现进度（2026-08-03）

- 已完成 Java `ArtifactStorage` 接口、Local Provider、URI 路由，以及素材、Artifact、Timeline、BGM 和 JSON 写入路径的统一接入。
- 已完成 Actuator/Micrometer/Prometheus 与 Tool Service 基础执行指标；压测脚本见 `scripts/stage14-k6.js`，报告模板见 `docs/stage14-baseline-report-template.md`。
- 已接入阿里云 OSS Java SDK 与 Python `oss2`，完成 OSS 对象上传、私有对象预签名读取、Tool Service 输入物化和输出发布；已通过当前 Bucket 只读鉴权验证。Range/multipart 与写入型故障测试仍需在隔离测试前缀下完成。
- 已修正 Artifact 分类：视频、音频、图片二进制走 OSS，JSON/SRT/Manifest 保留本地共享卷；历史 OSS JSON 不再走二进制媒体重定向。
- 音频和图片已完成 OSS 发布策略接入，适用于 BGM、关键帧和其他二进制预览资源。
- Java Maven 构建暂未在本机完成：IDEA 自带 `mvn.cmd` 被系统拒绝访问；Python `compileall` 已通过。

第十四阶段完成必须同时满足：

- 有可重复执行的压测脚本、固定素材说明和基线报告；
- 能从指标中分解 Workflow 排队、计算、存储和回调耗时；
- Java 和 Python 均不再由业务代码直接假设所有 Artifact 都是本地文件；
- Local Provider 完整兼容现有开发与测试；
- OSS Provider 支持素材与所有 Artifact 类型；
- 私有对象经过项目鉴权后可预览、Range 播放和下载；
- Tool Worker 可在没有共享 Volume 的条件下物化输入并发布输出；
- OSS/网络故障不会产生 `SUCCEEDED` 但对象不存在的脏 Artifact；
- 历史 `file:` URI 仍可读取；
- Local 与 OSS 的性能、稳定性和成本对照有真实数据；
- Java、Python、前端、Docker 和真实多素材 Workflow 回归通过。

## 11. 非目标

- 不在本阶段引入 RabbitMQ 或改变任务投递语义；
- 不引入 Redis；
- 不修改动态 DAG 产品边界；
- 不实现运行中 Replan；
- 不以 CDN、转码服务或 GPU 作为 OSS 改造的前置条件；
- 不删除历史本地文件和 Artifact；迁移策略必须另行确认后执行。
