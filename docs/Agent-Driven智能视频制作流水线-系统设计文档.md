# Agent-Driven Intelligent Video Production Pipeline

## Agent 驱动的智能视频制作流水线 - 系统设计文档

**文档版本：** V1.0  
**文档状态：** 设计基线（后续开发依据）  
**适用项目：** 暑期实训项目  
**目标仓库：** `(https://gitlab.omniedu.com/root/WwDa3B884n8dj.git)`  
**编制日期：** 2026-07-16

---

## 文档说明

本文档描述一个全新设计的 Agent 驱动智能视频制作平台。该平台继承旧版“小型视频处理流水线”中模块化、流水线、统一错误处理、dry-run 和 FFmpeg 工具化等工程经验，但不沿用旧系统的固定功能组合与进程内脚本编排方式。

本文档既描述目标架构，也给出适合暑期实训周期的最小可行产品（MVP）边界。文档中的“目标态”用于保证架构方向，“MVP”用于约束首轮开发范围；二者不能混为一次性实现清单。

### 读者对象

- 项目开发者与测试者
- 实训指导教师与项目评审人员
- 后续维护者与 Tool 插件开发者

### 关键词

Agent、Planner、Workflow、DAG、Task、Tool Calling、Plugin、Context、Memory、Video Understanding、Shot、Timeline、Explainable AI。

---

# 1. 执行摘要

本项目不是“输入一句 Prompt，然后拼接一条 FFmpeg 命令”的视频编辑器，而是一个以任务和工作流为核心的智能视频生产系统。用户提交素材和自然语言目标后，系统先理解需求与素材，再生成可验证的 DAG 工作流，按依赖关系执行分析、决策与渲染任务，最终输出视频、过程记录和可解释报告。

系统总体闭环为：

```text
User Intent
    -> Requirement Understanding
    -> Workflow Planning
    -> Video Understanding
    -> Shot-level Decision
    -> Timeline Planning
    -> Tool Execution
    -> Rendering
    -> Evaluation / Explanation
    -> Prompt-based Revision
```

系统采用 Java + Python 双语言架构：

- **Java 控制面**：Spring Boot、REST API、Planner、Workflow Engine、DAG Scheduler、状态机、上下文、Tool Registry、任务重试、WebSocket、MySQL、Redis。
- **Python 能力面**：FastAPI AI Tool Service、模型适配器、视频分析工具、LLM 工具、FFmpeg 渲染工具、异步 Worker。
- **通信原则**：Java 不启动 Python 脚本，不拼接本地 Python 命令；所有 Python 能力通过版本化 HTTP Tool API 调用。
- **数据原则**：MySQL 保存业务与执行真相，Redis 保存缓存、短期进度、锁与消息；视频和大型中间产物进入对象存储。

推荐首版采用“模块化单体 Java 控制面 + 一个可横向扩展的 Python Tool Service”，避免过早微服务化。首个垂直场景聚焦“自动生成 30 秒旅行 Vlog”，同时保留扩展游戏集锦、知识分享、产品宣传和会议总结的能力。

---

# 2. 项目背景与旧系统评估

## 2.1 旧系统概况

旧项目是 Rust 编写的小型视频处理 CLI，主要能力包括：

- SRT 解析和硬字幕烧录
- FFmpeg 音频提取与 whisper.cpp 自动字幕
- Python/OpenCV 人脸马赛克与人物头顶贴纸
- Decoder -> Renderer -> Encoder 三段有界流水线
- 规则型 Agent，根据 `isekai`、`privacy`、`subtitle` 等固定目标选择步骤
- 关键词型中文助手，将自然语言映射为预定义目标
- dry-run、统一错误类型、基本单元测试和集成测试

## 2.2 可继承的工程经验

- 流水线阶段职责清晰，便于测试和定位问题。
- 外部能力封装为工具，核心逻辑不直接处理全部底层细节。
- 有界队列体现背压意识。
- dry-run 有利于在昂贵渲染前检查计划。
- 错误统一建模，用户能获得可理解的失败原因。
- 字幕、ASR、马赛克、贴纸等能力可转化为新系统的 Tool Adapter。

## 2.3 必须替换的设计

- 固定枚举和固定串行步骤不能表达动态 DAG。
- 规则关键词不构成真正的需求理解和规划能力。
- Rust 主进程直接启动 Python 脚本，不利于隔离、扩缩容、版本治理和远程部署。
- 中间结果主要依赖临时文件，缺少统一 Artifact、Context 和数据血缘。
- 没有持久化任务状态、失败恢复、幂等、取消、版本化工作流。
- 没有 Shot 级 Video Knowledge，后续工具无法共享分析结果。
- 没有 Workflow 可视化、解释记录、Prompt 二次编辑和方案版本对比。

## 2.4 新旧系统定位对比

| 维度 | 旧系统 | 新系统 |
|---|---|---|
| 核心对象 | 视频与命令 | Workflow、Task、Artifact、Shot |
| 规划方式 | 固定规则 | LLM + 模板 + 约束验证的 Planner |
| 执行结构 | 固定串行管线 | 持久化、可并行、可恢复的 DAG |
| Python 调用 | 本地进程 | 统一 HTTP Tool Service |
| 上下文 | 临时参数和文件 | Project/Workflow/Task 分层 Context |
| 分析粒度 | 整段视频 | 视频级 + Shot 级知识 |
| 可解释性 | 日志 | 决策证据、评分分解、Plan Diff |
| 扩展方式 | 修改主程序 | 注册新 Tool 与 Schema |

---

# 3. 项目目标、范围与非目标

## 3.1 核心目标

1. 理解用户自然语言目标，并生成结构化需求。
2. 对视频进行可复用的内容分析，形成 Video Knowledge。
3. 由 Planner 根据需求、素材和 Tool 能力生成 DAG。
4. 支持任务并行、重试、超时、取消、恢复和进度推送。
5. 以 Shot 为主要编辑单位，完成筛选、排序、叙事和时间线规划。
6. 通过标准化 Tool API 调用 Python AI 能力。
7. 生成可渲染的 Timeline，并由 FFmpeg Tool 产生最终视频。
8. 支持自然语言二次编辑和工作流增量重规划。
9. 对镜头保留、删除、排序、转场和高光判断给出解释。
10. 形成具有软件工程和系统设计价值的可扩展平台。

## 3.2 非目标

- 不在 MVP 中实现完整桌面级非线性编辑器。
- 不追求与剪映、Premiere 的全部手工编辑能力对齐。
- 不在首版训练自有大模型或视频生成模型。
- 不在首版支持多人实时协同编辑。
- 不在首版支持无限类型的视频模板和全部 AI 模型。
- 不让 LLM 直接生成并执行 Shell/FFmpeg 命令。
- 不在首版拆分大量独立微服务。

## 3.3 成功标准

以“30 秒旅行 Vlog”为基准场景，用户上传若干视频并输入自然语言目标后，系统能够：

- 自动生成并展示 DAG；
- 完成视频探测、镜头切分、语音/OCR/视觉分析中的核心步骤；
- 自动选择并排序镜头；
- 生成含字幕和基础转场的 30 秒左右成片；
- 展示每个任务的状态、耗时和失败原因；
- 解释主要镜头选择；
- 接受“字幕大一点”“前三秒慢一点”等编辑指令并生成新版本。

---

# 4. 用户角色与核心用例

## 4.1 用户角色

- **创作者**：上传素材、输入目标、查看和修改成片。
- **Tool 开发者**：实现 Python Tool、发布版本、声明输入输出 Schema。
- **系统管理员**：管理 Tool 启停、模型配置、配额、失败任务和系统健康度。
- **评审/教师**：查看架构、DAG、执行过程、解释性报告与测试证据。

## 4.2 核心用例

### UC-01 创建智能视频项目

用户创建项目，上传一个或多个视频，输入“帮我做一个 30 秒、轻松温暖的旅行 Vlog”。系统保存原始素材、生成素材元数据并创建规划请求。

### UC-02 自动规划与执行

Planner 生成 DAG，系统验证后执行。无依赖任务可并行，前端实时显示任务状态。

### UC-03 查看 Video Knowledge

用户可以查看镜头边界、转写文本、OCR、标签、运动程度、清晰度、情绪和评分。

### UC-04 查看解释

用户点击某个镜头，系统显示其入选/淘汰原因、相关证据和评分分解。

### UC-05 自然语言二次编辑

用户输入“开头节奏更快，字幕放大，整体颜色暖一点”。系统解析修改意图，尽量复用已有分析结果，仅重跑受影响的规划和渲染任务。

### UC-06 失败恢复

某个 Tool 超时或 Worker 中断后，任务按策略重试；达到上限后工作流进入 `PAUSED` 或 `FAILED`，用户可从失败节点重试。

### UC-07 Tool 插件注册

开发者部署新 Tool，在 Tool Registry 注册 Manifest。Planner 可在能力满足、版本兼容且健康检查通过时使用该 Tool，无需修改 Workflow Engine。

---

# 5. 架构原则

1. **Task-first**：围绕 Task、Artifact 和依赖关系设计，而不是围绕单个 Video Controller 设计。
2. **Control/Data Separation**：Java 负责编排与状态，Python 负责 AI 与媒体计算。
3. **Contract-first**：Tool 必须先定义 Manifest、JSON Schema 和错误契约。
4. **LLM is untrusted**：Planner 输出必须经过 Schema、能力、类型、DAG、安全和预算验证。
5. **Database as source of truth**：MySQL 是执行状态真相；Redis 丢失不应破坏工作流正确性。
6. **Artifact immutability**：中间产物不可原地覆盖，新版本产生新 Artifact 并记录血缘。
7. **Idempotency by default**：任务可安全重试，Tool 调用使用幂等键。
8. **Reuse before recompute**：以输入、参数、Tool 版本和模型版本构造缓存指纹。
9. **Observability built in**：每次规划、任务调用和决策都具有 Trace、日志、指标和审计记录。
10. **MVP vertical slice**：先打通一个完整场景，再扩充 Tool 数量和视频类型。

---

# 6. 总体架构

## 6.1 系统上下文

```text
+------------------+       HTTPS / WebSocket       +--------------------------+
| Web Frontend     | <----------------------------> | Java Control Plane       |
| Project / DAG UI |                                | Spring Boot               |
+------------------+                                +------------+-------------+
                                                                  |
                                      HTTP Tool API / Callback     |
                                                                  v
                                                     +------------+-------------+
                                                     | Python AI Tool Service   |
                                                     | FastAPI + Workers        |
                                                     +------------+-------------+
                                                                  |
                             +------------------------------------+-------------------+
                             |                    |                 |                  |
                             v                    v                 v                  v
                         AI Models             FFmpeg          OCR/ASR/CV        LLM Provider

Shared infrastructure: MySQL, Redis, Object Storage (MinIO/S3), optional Vector Index
```

## 6.2 逻辑容器

| 容器 | 主要职责 | 推荐技术 |
|---|---|---|
| Web App | 项目、素材、对话、DAG、时间线、解释与预览 | Vue 3 + TypeScript + Vite |
| Java Control Plane | API、Planner、DAG、状态机、上下文、Tool Registry | Java 21 + Spring Boot 3 |
| Python Tool Service | Tool API、模型适配、媒体处理、异步任务 | Python 3.11 + FastAPI |
| Tool Workers | CPU/GPU 任务执行与资源隔离 | Celery/RQ 或进程池，MVP 可先 Celery |
| MySQL | 业务、计划、任务、版本、审计的持久化真相 | MySQL 8 |
| Redis | 缓存、锁、进度、限流、Python 队列 | Redis 7 |
| Object Storage | 原视频、代理视频、关键帧、模型结果、成片 | MinIO（本地）/S3（云） |

## 6.3 部署策略

MVP 使用 Docker Compose：

```text
frontend
java-control-plane
python-tool-service
python-worker-cpu
python-worker-gpu (可选)
mysql
redis
minio
```

目标态可按 Tool 类型拆分 Worker 池，但 API 契约保持不变。Java 控制面优先保持模块化单体，只有在明确出现独立扩缩容、隔离或团队边界时再拆服务。

---

# 7. 领域模型

## 7.1 核心实体关系

```text
User
  -> Project
       -> Asset
       -> ProductionRequest
       -> WorkflowDefinition (versioned)
            -> TaskDefinition
            -> Edge
       -> WorkflowRun
            -> TaskRun
                 -> ToolExecution
                 -> Artifact
       -> VideoKnowledge
            -> Shot
                 -> Observation
                 -> Score
       -> Timeline (versioned)
            -> TimelineTrack
            -> TimelineClip
       -> DecisionRecord / Explanation
```

## 7.2 核心概念定义

- **Project**：一次创作的长期容器，可包含多次工作流与成片版本。
- **Asset**：用户上传或系统生成的媒体对象，包括视频、音频、图片、字幕、关键帧。
- **ProductionRequest**：用户目标的结构化表达，如类型、时长、风格、平台、语言和硬约束。
- **WorkflowDefinition**：Planner 生成的不可变 DAG 定义。
- **WorkflowRun**：某个 WorkflowDefinition 的一次执行实例。
- **TaskDefinition**：节点定义，描述 Tool、输入绑定、参数、重试和资源需求。
- **TaskRun**：节点的一次实际运行，保存状态、尝试次数、进度、错误和输出。
- **Tool**：可被编排的能力单元，具有版本化 Manifest 和 HTTP API。
- **Artifact**：Task 的持久化输入/输出，使用 URI 与元数据引用。
- **VideoKnowledge**：对素材的共享、版本化分析结果。
- **Shot**：连续镜头片段，是选择、排序和编辑的主要单位。
- **Timeline**：渲染前的声明式编辑决策，不等同于 FFmpeg 命令。
- **DecisionRecord**：Planner 或决策引擎对某项选择的结构化解释。

## 7.3 Artifact 类型

建议至少定义：

```text
VIDEO_SOURCE, VIDEO_PROXY, AUDIO_TRACK, KEYFRAME_SET,
SHOT_LIST, TRANSCRIPT, OCR_RESULT, VISUAL_TAGS, EMBEDDING_SET,
VIDEO_KNOWLEDGE, SHOT_RANKING, HIGHLIGHT_SET, STORY_PLAN,
SUBTITLE_PLAN, TRANSITION_PLAN, EFFECT_PLAN, MUSIC_PLAN,
TIMELINE, RENDERED_VIDEO, THUMBNAIL, EXPLANATION_REPORT, LOG_BUNDLE
```

Artifact 记录 `contentHash`、`mediaType`、`size`、`storageUri`、`producerTaskRunId`、`schemaVersion`、`createdAt` 和 `expiresAt`。数据库不保存大型二进制内容。

---

# 8. 端到端处理流程

## 8.1 首次生成

```text
1. 创建项目并上传素材
2. Media Probe 生成基础元数据与代理视频
3. Intent Parser 将用户 Prompt 转换为 ProductionRequest
4. Planner 根据请求、素材摘要、Tool Registry 生成 WorkflowDefinition
5. Workflow Validator 校验并冻结 DAG 版本
6. Scheduler 创建 WorkflowRun 和 TaskRun
7. 分析任务并行执行，形成 Video Knowledge
8. 决策任务完成 Shot Ranking、Highlight、Story 与各 Planner
9. Timeline Composer 合并计划并验证时长/冲突
10. Renderer 根据 Timeline 生成视频
11. Quality Evaluator 执行技术检查
12. 系统输出成片、DAG、解释报告和可编辑版本
```

## 8.2 Prompt 二次编辑

```text
Edit Prompt
  -> Edit Intent
  -> Impact Analysis
  -> Clone Workflow/Timeline Version
  -> Reuse unaffected Artifacts
  -> Re-plan affected subgraph
  -> Execute delta tasks
  -> Render new version
  -> Show Plan Diff and Result Diff
```

例如“字幕放大”只影响 Subtitle Plan、Timeline 和 Render；“不要夜景镜头”会影响 Shot Ranking、Story、Timeline 和 Render，但应复用 Scene Detection、Whisper、OCR 等分析 Artifact。

---

# 9. Java 控制面设计

## 9.1 模块划分

建议使用单仓库、模块化单体：

```text
control-plane/
  pom.xml
  src/main/java/com/yizhixianyu/agentvideo/
    api
    project
    asset
    artifact
    planning
    workflow
    toolregistry
    execution
    context
    explanation
    infrastructure/
      mysql
      redis
      storage
      toolclient
      llm
  src/main/resources/
  src/test/java/
```

首版采用单 Maven 工程，通过 Java package 表达模块边界，而不是创建多个 Maven 子模块。模块之间通过应用服务和领域事件交互，禁止 Controller 直接操作 Repository 或拼装 Tool 请求。模块职责说明集中保存于 `docs/modules/control-plane/`。

其中 `infrastructure-llm-provider` 为 Planning Domain 和 Explanation Domain 提供统一大模型适配，支持云端模型、本地模型或兼容 OpenAI API 的服务。领域层不直接绑定具体模型厂商。

## 9.2 分层职责

- **Interface**：REST/WebSocket DTO、鉴权、参数校验、异常映射。
- **Application**：用例编排、事务边界、权限检查。
- **Domain**：Workflow、Task、状态机、策略和不变量。
- **Infrastructure**：MySQL、Redis、对象存储、HTTP Client、LLM Provider。

## 9.3 Planner 组件

Planner 由以下部分组成：

- Intent Parser：将自然语言转换为 `ProductionRequest`。
- Plan Template Library：提供旅行、游戏、知识、宣传、会议等基础骨架。
- LLM Planner：选择模板、补充节点与参数、生成理由。
- Plan Compiler：将高层计划编译为 Workflow DSL。
- Workflow Validator：执行确定性校验。
- Plan Repairer：将校验错误反馈给 LLM，最多修复指定次数。
- Fallback Planner：LLM 不可用时使用模板产生可演示计划。

大模型是意图理解和任务编排的核心推理能力，具体参与 Intent Parser、Workflow Planner、Plan Repairer、Edit Planner 和 Explanation Generator。系统向模型提供用户目标、场景模板、可用 Tool Manifest、输入输出 Schema、Video Knowledge 摘要、预算与策略约束，并要求模型返回符合 JSON Schema 的结构化结果。

大模型输出始终被视为不可信候选数据：不得直接执行，必须经过 Tool 存在性、Schema、类型、DAG 无环、权限、资源和预算等确定性校验。大模型不得生成或执行 Shell、Python、SQL 或 FFmpeg 命令。

## 9.4 Workflow Engine 组件

- Definition Service：创建、版本化、冻结 WorkflowDefinition。
- Dependency Resolver：计算入度和可运行节点。
- Scheduler：按优先级、资源和并发限制领取 READY 任务。
- Task Dispatcher：调用 Python Tool API。
- Callback Handler：接收 Tool 完成/失败回调。
- Retry Manager：处理重试、退避和死信。
- Recovery Scanner：扫描超时租约和异常中断任务。
- Progress Aggregator：汇总任务权重并推送 WebSocket。
- Cancellation Manager：传播取消并停止未开始任务。

## 9.5 Context 分层

| Context | 内容 | 生命周期 |
|---|---|---|
| User Preference | 默认语言、字幕风格、平台、审美偏好 | 跨项目，可选 |
| Project Context | 目标、素材、已确认偏好、版本历史 | 项目级 |
| Workflow Context | 当前计划、预算、Artifact 引用、全局变量 | 工作流级 |
| Task Context | 解析后的输入、参数、上游输出、Trace | 单任务 |
| Conversation Context | 用户编辑指令和系统答复摘要 | 会话级 |

Context 中仅保存结构化数据和 Artifact 引用，避免把完整转写或大数组重复复制到每个 Task。

---

# 10. Workflow DSL 与 DAG 引擎

## 10.1 WorkflowDefinition 示例

```json
{
  "workflowId": "wf_travel_vlog_001",
  "version": 1,
  "goal": "生成30秒温暖旅行Vlog",
  "nodes": [
    {
      "id": "probe",
      "tool": "video.probe@1.0.0",
      "inputs": {"video": "asset://source-video"},
      "params": {},
      "retryPolicy": {"maxAttempts": 2, "backoff": "EXPONENTIAL"}
    },
    {
      "id": "shot_detect",
      "tool": "video.shot-detect@1.1.0",
      "inputs": {"video": "asset://source-video"},
      "params": {"threshold": 0.42}
    },
    {
      "id": "transcribe",
      "tool": "audio.transcribe@1.0.0",
      "inputs": {"video": "asset://source-video"},
      "params": {"language": "auto"}
    },
    {
      "id": "knowledge_merge",
      "tool": "knowledge.video-merge@1.0.0",
      "inputs": {
        "shots": "task://shot_detect/output",
        "transcript": "task://transcribe/output"
      }
    }
  ],
  "edges": [
    {"from": "probe", "to": "knowledge_merge"},
    {"from": "shot_detect", "to": "knowledge_merge"},
    {"from": "transcribe", "to": "knowledge_merge"}
  ]
}
```

## 10.2 计划校验

Workflow 在运行前必须通过：

1. JSON Schema 校验。
2. 节点 ID 唯一性校验。
3. Tool 存在、启用、健康和版本兼容校验。
4. 输入输出类型兼容校验。
5. 引用完整性校验。
6. DAG 无环校验。
7. 必需输入可达性校验。
8. 权限与安全策略校验。
9. 资源、预计时长和预算校验。
10. 最终输出可达性校验。

LLM 只产生候选计划，校验器才决定计划是否可执行。

## 10.3 Task 状态机

```text
PENDING -> READY -> DISPATCHING -> RUNNING -> SUCCEEDED
   |         |           |            |
   |         |           |            +-> RETRY_WAIT -> READY
   |         |           +--------------> FAILED
   |         +--------------------------> CANCELLED
   +------------------------------------> SKIPPED
```

补充状态：

- `BLOCKED`：依赖失败且策略不允许继续。
- `TIMED_OUT`：超过执行期限。
- `LOST`：Worker 租约过期且无法确认结果。

## 10.4 WorkflowRun 状态机

```text
CREATED -> VALIDATED -> RUNNING -> SUCCEEDED
                         |  |  |
                         |  |  +-> PARTIALLY_SUCCEEDED
                         |  +----> PAUSED -> RUNNING
                         +-------> FAILED
CREATED / RUNNING ----------------> CANCELLING -> CANCELLED
```

## 10.5 调度算法

每次任务完成后，Dependency Resolver 更新下游节点的未满足依赖数。满足以下条件的节点进入 READY：

- 所有强依赖成功；
- 条件表达式为真；
- 并发配额允许；
- 所需 Tool 健康；
- 资源标签可匹配 CPU/GPU Worker；
- 工作流未暂停或取消。

Scheduler 使用数据库行锁或乐观锁领取任务，确保多实例下不重复分发。Redis 锁只能作为性能优化，不能替代数据库状态校验。

## 10.6 重试与幂等

- 每个 TaskDefinition 声明最大尝试次数、退避、可重试错误码和超时。
- Java 生成 `idempotencyKey = hash(workflowRunId, taskId, attemptSemanticKey)`。
- Python Tool Service 对同一幂等键返回已有执行或结果。
- Artifact 使用内容哈希去重，但业务上仍保留独立引用。
- 参数错误、Schema 错误、权限错误不可自动重试。
- 网络错误、Worker 丢失、模型临时不可用可重试。

## 10.7 缓存指纹

```text
fingerprint = SHA-256(
  toolName + toolVersion + modelVersion +
  normalizedParams + orderedInputArtifactHashes + schemaVersion
)
```

命中缓存时创建新的 TaskRun 记录并引用已有 Artifact，同时标记 `executionMode=CACHE_HIT`，保证审计完整。

---

# 11. Planner：真正的 Agent 设计

## 11.1 Planner 的输入

- 用户原始 Prompt 和历史编辑指令
- 结构化 ProductionRequest
- 素材清单与低成本 Probe 摘要
- 可用 Tool Manifest 与健康状态
- 系统策略、用户配额和资源预算
- 已存在 Video Knowledge 和 Artifact
- 场景模板与领域知识

## 11.2 ProductionRequest

```json
{
  "videoType": "TRAVEL_VLOG",
  "targetDurationSec": 30,
  "targetPlatform": "GENERAL",
  "aspectRatio": "16:9",
  "language": "zh-CN",
  "style": ["warm", "relaxed", "cinematic"],
  "mustInclude": [],
  "mustExclude": [],
  "subtitlePreference": "AUTO",
  "musicPreference": "LIGHT",
  "hardConstraints": {
    "maxDurationSec": 35,
    "privacyProtection": false
  }
}
```

Intent Parser 对不确定字段给出 `confidence` 和 `assumptions`。非关键歧义使用默认值继续；会显著改变结果或成本的歧义才要求用户确认。

## 11.3 混合规划策略

不建议让 LLM 从空白直接生成任意 DAG。推荐：

```text
Scenario Template
  + Tool Capability Retrieval
  + LLM Selection / Parameterization
  + Deterministic Compilation
  + Validation / Repair
  = Executable Workflow
```

模板保证演示稳定，LLM 提供需求理解、工具选择、参数化、条件分支和解释。随着系统成熟，可逐步提高动态规划自由度。

## 11.4 Planner 输出要求

Planner 必须输出：

- 结构化需求与假设；
- 高层阶段计划；
- Workflow DSL；
- 预计成本、耗时和资源需求；
- 每个节点的选择理由；
- 不可满足需求和降级策略；
- 计划置信度。

## 11.5 Plan Repair

当校验失败时，系统把错误列表、相关 Tool Schema 和原计划交给 Plan Repairer。修复次数建议最多 2 次，仍失败则回退到模板计划并记录原因，避免无限 LLM 循环。

## 11.6 计划安全

- LLM 不得产生 Shell、文件系统路径或任意 URL 调用。
- Tool 只能从 Registry 白名单选择。
- 参数必须通过 Tool JSON Schema。
- 输入 URI 只能引用当前项目有权限的 Artifact。
- 对外 LLM 请求应脱敏，默认不发送原始视频，只发送必要摘要或抽帧。

---

# 12. Python AI Tool Service 与插件机制

## 12.1 服务结构

```text
tool-service/
  app/api
  app/core
  app/registry
  app/execution
  app/storage
  tools/video_probe
  tools/shot_detection
  tools/transcription
  tools/ocr
  tools/visual_embedding
  tools/highlight
  tools/story
  tools/subtitle_plan
  tools/transition_plan
  tools/render
  workers
  tests
```

## 12.2 Tool Manifest

每个 Tool 必须声明：

```yaml
name: video.shot-detect
version: 1.1.0
description: Detect shot boundaries and produce shot segments
executionMode: ASYNC
inputSchema: schemas/input.json
outputSchema: schemas/output.json
resourceClass: CPU_MEDIUM
timeoutSeconds: 900
supportsCancellation: true
deterministic: true
cacheable: true
healthEndpoint: /health
```

可选字段包括模型版本、GPU 显存、支持格式、最大输入大小、隐私等级和费用估算函数。

## 12.3 统一执行 API

### 创建 Tool Execution

`POST /api/v1/tool-executions`

```json
{
  "tool": "video.shot-detect",
  "version": "1.1.0",
  "idempotencyKey": "...",
  "inputs": {
    "video": {"artifactId": "art_001", "uri": "s3://bucket/source.mp4"}
  },
  "parameters": {"threshold": 0.42},
  "callbackUrl": "http://control-plane/internal/tool-callbacks",
  "traceContext": {"traceId": "...", "workflowRunId": "...", "taskRunId": "..."}
}
```

返回 `202 Accepted`：

```json
{
  "executionId": "tex_001",
  "status": "QUEUED",
  "statusUrl": "/api/v1/tool-executions/tex_001"
}
```

### 查询与取消

- `GET /api/v1/tool-executions/{id}`
- `POST /api/v1/tool-executions/{id}/cancel`
- `GET /api/v1/tools`
- `GET /api/v1/tools/{name}/versions/{version}`
- `GET /health`、`GET /ready`

回调和查询结果使用相同状态结构。Java 必须同时支持回调和轮询补偿，避免回调丢失造成永久 RUNNING。

## 12.4 错误契约

```json
{
  "code": "MODEL_UNAVAILABLE",
  "message": "transcription model is temporarily unavailable",
  "retryable": true,
  "details": {},
  "traceId": "..."
}
```

标准错误类别：`INVALID_INPUT`、`UNSUPPORTED_MEDIA`、`MODEL_UNAVAILABLE`、`RESOURCE_EXHAUSTED`、`TIMEOUT`、`CANCELLED`、`INTERNAL_ERROR`。

## 12.5 Tool 实现接口

```python
class Tool(Protocol):
    manifest: ToolManifest

    def validate(self, request: ToolRequest) -> None: ...
    def estimate(self, request: ToolRequest) -> CostEstimate: ...
    def execute(self, context: ToolContext) -> ToolResult: ...
    def cancel(self, execution_id: str) -> None: ...
```

Tool 不能把大型二进制结果直接放入 JSON；应写入对象存储并返回 Artifact Descriptor。

## 12.6 Tool 注册方式

MVP 采用启动时扫描本地 Tool Package，并由 Python Service 暴露 Manifest；Java Tool Registry 定期同步。目标态可增加管理 API 和签名发布包。

新增 Tool 的最小步骤：

1. 实现 Tool 接口。
2. 提供 Manifest 与输入输出 Schema。
3. 添加契约测试。
4. 部署并通过健康检查。
5. 在 Registry 启用版本。

Workflow Engine 不需要修改。

---

# 13. Video Understanding 与 Video Knowledge

## 13.1 分析层级

- **Asset 级**：格式、时长、分辨率、FPS、编码、音轨。
- **Video 级**：整体主题、语言、节奏、色彩、质量摘要。
- **Shot 级**：边界、人物、动作、场景、文本、情绪、质量与向量。
- **Frame/Segment 级**：关键帧、OCR 区域、转写词时间戳、运动曲线。

## 13.2 Shot 数据模型

```json
{
  "shotId": "shot_0007",
  "assetId": "asset_001",
  "startMs": 12500,
  "endMs": 16820,
  "durationMs": 4320,
  "keyframes": ["artifact://kf_001"],
  "persons": [{"trackId": "p1", "confidence": 0.91}],
  "actions": [{"label": "walking", "confidence": 0.84}],
  "scenes": [{"label": "mountain", "confidence": 0.88}],
  "keywords": ["mountain", "sunset", "travel"],
  "emotion": {"label": "uplifting", "score": 0.78},
  "ocr": [{"text": "...", "startMs": 13000, "endMs": 14500}],
  "transcriptRefs": ["segment_021"],
  "quality": {"clarity": 0.82, "exposure": 0.73, "stability": 0.66},
  "motion": {"level": 0.71, "direction": "left_to_right"},
  "embeddingRef": "artifact://emb_007",
  "schemaVersion": "1.0"
}
```

## 13.3 Knowledge Merge

不同 Tool 的时间轴可能不同。Knowledge Merge Tool 负责：

- 将 OCR、转写、人物轨迹映射到 Shot 时间范围；
- 统一置信度和缺失值表示；
- 保存来源 Tool、模型版本和证据引用；
- 生成可供 Planner 使用的紧凑摘要；
- 避免下游重复读取和分析原视频。

## 13.4 Embedding 存储

MVP 可把向量作为 Artifact 文件保存，并在 MySQL 中保存引用；若需要相似镜头搜索，再引入 Qdrant 或 Milvus。不要为了“架构完整”在首版强行增加向量数据库。

---

# 14. Shot Ranking 与 Highlight Detection

## 14.1 Ranking Engine

基础评分模型：

```text
finalScore =
  w1 * visualQuality
  + w2 * composition
  + w3 * subjectCompleteness
  + w4 * motionInterest
  + w5 * emotionMatch
  + w6 * semanticRelevance
  + w7 * highlightScore
  + w8 * diversityContribution
  + w9 * llmPreference
  - penalties
```

权重由视频类型策略、用户目标和时长约束决定。`penalties` 包括模糊、过曝、重复、镜头过短、隐私风险和与目标冲突。

评分必须保存分解结果，不能只保存一个总分。

## 14.2 不同类型的策略

- **旅行**：景观质量、地点多样性、人物体验、运动连续性、情绪递进。
- **游戏**：击杀/得分事件、音量峰值、操作强度、失败到胜利的上下文。
- **知识分享**：语义完整、观点密度、口误与停顿、Hook、例子和总结。
- **采访/会议**：说话人、议题重要度、结论、行动项、重复内容。
- **宣传片**：产品露出、品牌信息、清晰度、情绪和 CTA。

## 14.3 Highlight 检测

Highlight 不应依赖一条固定规则。系统先由场景策略选择信号，再融合：

- 音频峰值、语速和情绪变化；
- 运动强度和镜头变化；
- 事件/动作检测；
- 转写语义和关键词；
- Shot 质量；
- LLM 对候选摘要的二次判断。

LLM 只评价候选集合与摘要，不直接处理全部原始帧，以降低成本和不确定性。

---

# 15. Story Builder 与各类 Planner

## 15.1 Story Builder

Story Builder 输出 Story Plan，而非直接输出视频。基本结构：

```json
{
  "template": "TRAVEL_JOURNEY",
  "targetDurationMs": 30000,
  "beats": [
    {"role": "HOOK", "durationMs": 3000, "candidateShots": ["s9", "s3"]},
    {"role": "DEPARTURE", "durationMs": 5000, "candidateShots": ["s1"]},
    {"role": "JOURNEY", "durationMs": 9000, "candidateShots": ["s4", "s5"]},
    {"role": "CLIMAX", "durationMs": 9000, "candidateShots": ["s9", "s10"]},
    {"role": "ENDING", "durationMs": 4000, "candidateShots": ["s12"]}
  ]
}
```

Story Builder 需要同时满足叙事角色、时间预算、镜头不重复、内容多样性和转场可行性。

## 15.2 Transition Planner

输入相邻镜头的运动、色彩、构图、节拍和情绪，输出转场类型、时长和理由。MVP 仅支持：`CUT`、`FADE`、`CROSS_DISSOLVE`、`FLASH`，避免首版实现复杂 Motion Blur/Camera Move。

## 15.3 Subtitle Planner

输出：

- 是否显示该段字幕；
- 文本清洗与断句；
- 关键词强调；
- 样式 Token；
- 位置与安全区；
- 动画类型；
- 时间范围。

字幕计划与转写结果分离，使“字幕放大”“只保留重点字幕”无需重新 ASR。

## 15.4 Music Planner

MVP 使用用户提供或本地版权安全曲库，不自动从互联网下载音乐。输出曲目引用、裁剪范围、音量包络、淡入淡出和节拍点。音乐版权信息必须保存在 Asset 元数据中。

## 15.5 Effect Planner

通过受限效果目录选择 LUT、色温、Film Grain、Glow 等参数。MVP 建议只支持色温、饱和度、对比度和一个预设 LUT，确保渲染可靠。

---

# 16. Timeline 与渲染

## 16.1 Timeline 是渲染中间表示

Planner 不能直接输出 FFmpeg 字符串。所有剪辑决策先进入版本化 Timeline：

```json
{
  "timelineId": "tl_001",
  "version": 3,
  "canvas": {"width": 1920, "height": 1080, "fps": 30},
  "durationMs": 30200,
  "tracks": [
    {
      "type": "VIDEO",
      "clips": [
        {
          "assetId": "asset_001",
          "sourceInMs": 12500,
          "sourceOutMs": 15800,
          "timelineInMs": 0,
          "playbackRate": 1.0,
          "effects": [{"type": "COLOR_TEMPERATURE", "value": 0.12}]
        }
      ]
    }
  ]
}
```

## 16.2 Timeline Validator

渲染前校验：

- Clip 时间范围不越界；
- Track 内无非法重叠；
- 转场时长不超过相邻片段；
- 输出总时长符合硬约束；
- 资源和字体存在；
- 效果参数在白名单范围；
- 音视频轨道映射合法；
- 输出分辨率、FPS 和编码配置可支持。

## 16.3 FFmpeg Render Tool

Renderer 将 Timeline 编译为内部 Filter Graph，再构造受控 FFmpeg 参数。命令生成器只接受已验证的类型化对象，不接受 Planner 生成的字符串。

渲染产物包括：

- 最终视频；
- 缩略图；
- 渲染日志；
- FFmpeg 版本与编码参数；
- Timeline/Artifact 血缘；
- 可选低分辨率预览。

## 16.4 质量检查

MVP 技术检查：文件可读取、时长误差、分辨率、音轨存在、黑帧比例、静音比例、响度范围和字幕越界。内容审美评分作为增强项，不阻塞首版交付。

---

# 17. Prompt Editing 与版本管理

## 17.1 编辑指令分类

- **局部参数修改**：字幕大小、色温、音量。
- **时间线修改**：慢放、删除片段、调整开头。
- **内容约束修改**：不要夜景、更多人物、突出某地点。
- **风格修改**：更电影感、更活泼、更科技。
- **目标修改**：30 秒改为 60 秒，旅行 Vlog 改为宣传片。

## 17.2 Impact Analyzer

系统维护“字段 -> 受影响任务类型”的依赖规则。例如：

| 修改 | 需要重跑 | 可复用 |
|---|---|---|
| 字幕放大 | Subtitle Planner、Timeline、Render | ASR、Shot、Ranking |
| 颜色暖一点 | Effect Planner、Timeline、Render | 全部分析 |
| 删除夜景 | Ranking、Story、Timeline、Render | Probe、Shot、ASR、OCR |
| 改为 60 秒 | Story、各 Planner、Timeline、Render | Video Knowledge |
| 更换素材 | 受影响素材的全部分析及下游 | 未变化素材分析 |

## 17.3 版本规则

- ProductionRequest、WorkflowDefinition、Timeline 和 RenderedVideo 都版本化。
- 已执行版本不可原地修改。
- 新版本记录 `parentVersionId`、编辑 Prompt 和变更摘要。
- 前端展示 Plan Diff：新增、删除、参数变化和复用节点。
- 用户可以回退到任一历史版本，但回退本身创建新版本。

---

# 18. Explainable AI

## 18.1 解释类型

- **计划解释**：为什么选择这些 Tool 和步骤。
- **镜头解释**：为什么保留、删除或排序。
- **转场解释**：为什么选择某种转场。
- **高光解释**：哪些信号支持其为高潮。
- **失败解释**：失败发生在哪个节点、是否可重试、如何处理。
- **版本解释**：编辑 Prompt 使哪些计划发生变化。

## 18.2 DecisionRecord

```json
{
  "subjectType": "SHOT",
  "subjectId": "shot_0007",
  "decision": "SELECTED_FOR_CLIMAX",
  "reasonCodes": ["HIGH_VISUAL_QUALITY", "EMOTION_MATCH", "SCENE_DIVERSITY"],
  "scoreBreakdown": {
    "visualQuality": 0.82,
    "emotionMatch": 0.78,
    "semanticRelevance": 0.91
  },
  "evidenceRefs": ["artifact://kf_001", "transcript://segment_021"],
  "naturalLanguage": "该镜头画面清晰，包含山顶日落，并与温暖旅行主题高度匹配。",
  "model": "...",
  "promptVersion": "..."
}
```

解释文本可以由模板或 LLM 生成，但数值、证据和实际决策必须来自结构化记录，禁止生成与执行事实不一致的“事后故事”。

---

# 19. 数据架构

## 19.1 MySQL 核心表

| 表 | 关键字段 |
|---|---|
| `project` | id, owner_id, name, status, created_at |
| `asset` | id, project_id, type, storage_uri, content_hash, metadata_json |
| `production_request` | id, project_id, version, raw_prompt, structured_json |
| `workflow_definition` | id, project_id, version, status, definition_json, parent_id |
| `workflow_node` | id, workflow_id, node_key, tool_name, tool_version, config_json |
| `workflow_edge` | workflow_id, from_node, to_node, condition_json |
| `workflow_run` | id, workflow_id, status, progress, started_at, ended_at |
| `task_run` | id, run_id, node_key, status, attempt, lease_until, error_json |
| `tool_execution` | id, task_run_id, external_id, idempotency_key, status |
| `artifact` | id, project_id, type, storage_uri, content_hash, lineage_json |
| `video_knowledge` | id, asset_id, version, summary_json |
| `shot` | id, knowledge_id, start_ms, end_ms, feature_json |
| `timeline` | id, project_id, version, parent_id, timeline_json |
| `decision_record` | id, project_id, subject_type, subject_id, decision_json |
| `tool_registry` | name, version, status, manifest_json, last_health_at |
| `outbox_event` | id, aggregate_type, aggregate_id, event_type, payload, status |

MVP 可以将部分复杂结构保存在 JSON 列，待查询模式稳定后再规范化。Task 状态、依赖和幂等相关字段应结构化，不能全部塞入 JSON。

## 19.2 Redis 使用边界

- Tool Manifest 与健康状态缓存；
- WebSocket 进度 Pub/Sub；
- 分布式限流与短租约；
- Planner/LLM 短期响应缓存；
- Python Worker 队列；
- 临时进度和取消信号。

Redis 不单独保存唯一任务状态。服务恢复后应可从 MySQL 重建运行状态。

## 19.3 对象存储目录约定

```text
projects/{projectId}/assets/{assetId}/source.ext
projects/{projectId}/artifacts/{artifactId}/payload.ext
projects/{projectId}/renders/{timelineVersion}/final.mp4
projects/{projectId}/previews/{timelineVersion}/preview.mp4
```

URI 必须通过后端生成短期签名访问地址，前端不能获得对象存储永久密钥。

## 19.4 事务与事件

Java 使用 Transactional Outbox：业务状态变更与待发布事件在同一 MySQL 事务提交，后台发布到 Redis Pub/Sub 或内部事件总线，防止“状态已变但进度消息丢失”。

---

# 20. 对外 API 与 WebSocket

## 20.1 REST API 草案

```text
POST   /api/v1/projects
GET    /api/v1/projects/{projectId}
POST   /api/v1/projects/{projectId}/assets/upload-sessions
POST   /api/v1/projects/{projectId}/production-requests
POST   /api/v1/projects/{projectId}/plans
GET    /api/v1/workflows/{workflowId}
POST   /api/v1/workflows/{workflowId}/runs
GET    /api/v1/workflow-runs/{runId}
POST   /api/v1/workflow-runs/{runId}/pause
POST   /api/v1/workflow-runs/{runId}/resume
POST   /api/v1/workflow-runs/{runId}/cancel
POST   /api/v1/task-runs/{taskRunId}/retry
GET    /api/v1/projects/{projectId}/knowledge
GET    /api/v1/projects/{projectId}/shots
GET    /api/v1/projects/{projectId}/timelines/{version}
POST   /api/v1/projects/{projectId}/edits
GET    /api/v1/projects/{projectId}/explanations
```

API 采用统一响应和错误结构，写操作支持 `Idempotency-Key`。

## 20.2 WebSocket 事件

订阅地址示例：`/topic/workflow-runs/{runId}`。

事件类型：

```text
WORKFLOW_STATUS_CHANGED
TASK_STATUS_CHANGED
TASK_PROGRESS_UPDATED
TASK_LOG_APPENDED
ARTIFACT_CREATED
PLAN_REPAIRED
WORKFLOW_COMPLETED
WORKFLOW_FAILED
```

事件携带单调递增序号。前端发现序号缺失时通过 REST 拉取完整快照，不依赖 WebSocket 消息的绝对可靠性。

---

# 21. 前端设计

## 21.1 页面

- 项目列表与新建项目
- 素材上传与预览
- Agent 对话与 ProductionRequest 确认
- Workflow DAG 画布
- 任务详情抽屉（输入、输出、日志、耗时、重试）
- Video Knowledge/Shot 浏览器
- Timeline 与视频预览
- Explainability 面板
- 版本历史与 Plan Diff
- Tool Registry 管理页（管理角色）

## 21.2 DAG 交互

节点颜色表达状态，图标表达 Tool 类型。节点至少显示名称、状态、进度、耗时和重试次数。点击节点展示结构化输入输出引用，而不是直接展示巨大的原始 JSON。

## 21.3 MVP 前端边界

MVP 不实现复杂拖拽剪辑器。Timeline 以只读可视化、片段列表和少量表单调整为主，主要编辑入口仍是自然语言 Prompt。

---

# 22. 安全、隐私与内容治理

- 上传文件使用 MIME、扩展名、魔数和大小四重校验。
- FFmpeg 和模型处理在隔离 Worker 中运行，限制 CPU、内存、GPU 和执行时间。
- 不允许用户输入成为 Shell 片段；所有命令参数由类型化编译器生成。
- Artifact URI 进行项目级授权校验，防止跨项目访问。
- Tool 回调使用签名、时间戳和重放保护。
- 对 LLM Provider 的数据最小化，记录是否发送关键帧/文本。
- 敏感视频支持项目级保留期限和一键删除。
- 日志不得记录对象存储密钥、完整签名 URL 和用户敏感文本。
- 人脸分析只在明确功能需要时启用；隐私保护场景保存必要结果，不默认保存人脸特征向量。
- 音乐与素材记录版权来源和使用许可。

---

# 23. 可观测性与运维

## 23.1 Trace

统一 Trace 链：

```text
HTTP Request -> Planner -> WorkflowRun -> TaskRun -> ToolExecution -> Artifact
```

Java 调用 Python 时透传 W3C `traceparent`。日志统一包含 `projectId`、`workflowRunId`、`taskRunId`、`toolExecutionId`。

## 23.2 指标

- Workflow 成功率、P50/P95 总耗时；
- 各 Tool 成功率、队列等待、执行耗时；
- Task 重试率、超时率、缓存命中率；
- CPU/GPU 利用率和 Worker 队列长度；
- 每分钟渲染视频时长；
- Planner 校验失败率、修复率、模板回退率；
- LLM Token、模型调用次数和估算成本。

## 23.3 日志和审计

日志用于技术排障，Audit Event 用于回答“谁在何时用什么 Prompt 生成了哪个计划和版本”。二者分开保存。

## 23.4 健康检查

- Java：数据库、Redis、对象存储、Python Service 可达性。
- Python：API 就绪、Worker 心跳、模型加载、FFmpeg 可用、磁盘空间。
- Tool Registry 自动将持续失败的 Tool 标记为 `DEGRADED` 或 `DISABLED`。

---

# 24. 性能与容量设计

## 24.1 目标指标（MVP 建议）

- 普通 API P95 < 500 ms（不含上传和 AI 任务）。
- Workflow 状态变化 2 秒内推送到前端。
- 1 GB 以内视频支持断点/分片上传。
- 任务恢复扫描周期 <= 30 秒。
- 30 秒成片的端到端时间以机器配置为基线记录，不承诺云级实时性。

## 24.2 并发控制

- 按 Tool、用户和资源类型设置并发上限。
- GPU Tool 使用独立队列，防止 CPU 任务阻塞。
- 同一素材的重复分析优先缓存复用。
- 生成代理视频和关键帧，避免所有分析读取原始高码率视频。

## 24.3 成本控制

- Planner 先使用 Probe 摘要，完整分析按需生成。
- LLM 只读取压缩摘要与候选集合。
- 每个 Workflow 保存预算上限和预计成本。
- Prompt 编辑执行子图差量重跑。

---

# 25. 测试策略

## 25.1 Java 测试

- 领域单元测试：DAG 校验、状态机、重试、进度计算、Impact Analyzer。
- Repository 集成测试：Testcontainers MySQL/Redis/MinIO。
- Tool Client 契约测试：Mock Server 验证超时、回调、幂等。
- API 测试：权限、错误结构、幂等写操作。
- 恢复测试：模拟 Java 重启、回调丢失、租约过期。

## 25.2 Python 测试

- Tool 输入输出 Schema 测试。
- 每个 Tool 的单元与小样本回归测试。
- FFmpeg Timeline 编译 Golden Test。
- 模型不可用、OOM、超时和取消测试。
- Artifact 上传失败和重复幂等键测试。

## 25.3 端到端测试

准备短小、可入库或可下载的测试素材集：

- 有语音/无语音；
- 单镜头/多镜头；
- 横屏/竖屏；
- 中文/英文；
- 损坏文件；
- 极短镜头和静音视频。

核心 E2E：创建项目 -> 上传 -> 规划 -> 执行 -> 渲染 -> 编辑 -> 新版本。

## 25.4 AI 评估

AI 结果不只用“是否报错”判断。建议构建人工标注的小型评估集，记录 Shot 边界 F1、转写 WER/CER、高光 Top-K 命中、目标时长误差和人工偏好评分。

---

# 26. MVP 设计

## 26.1 场景范围

首个场景：**使用一个或多个旅行视频素材，生成约 30 秒、温暖轻松风格的旅行 Vlog。**

## 26.2 MVP Tool 清单

| Tool | 必选 | 说明 |
|---|---|---|
| `video.probe` | 是 | FFprobe 基础元数据 |
| `video.proxy-generate` | 是 | 低码率代理视频 |
| `video.shot-detect` | 是 | Shot 边界与关键帧 |
| `audio.transcribe` | 是 | Whisper 转写，可无语音降级 |
| `vision.quality-score` | 是 | 清晰度、亮度、稳定性、运动度 |
| `vision.scene-tag` | 是 | 基础场景标签，可用 CLIP/轻量模型 |
| `knowledge.video-merge` | 是 | 生成 Video Knowledge |
| `decision.shot-rank` | 是 | 旅行策略评分与多样性 |
| `decision.highlight` | 是 | 候选高光 |
| `planning.story` | 是 | 旅行叙事模板 + LLM 参数化 |
| `planning.subtitle` | 是 | 重点字幕与样式 |
| `planning.transition` | 是 | 有限转场集合 |
| `timeline.compose` | 是 | 合并并校验 Timeline |
| `render.ffmpeg` | 是 | 最终渲染 |
| `quality.technical-check` | 是 | 成片技术检查 |
| OCR | 可选 | 时间不足时进入第二阶段 |
| Music/Effect Planner | 简化 | 预设音乐与基础色温即可 |

## 26.3 MVP 可演示亮点

- 自然语言到结构化请求；
- Planner 生成而非写死的可视化 DAG；
- Shot Detection、转写与质量分析并行；
- Task 状态机、重试和缓存命中；
- Python Tool 统一 HTTP 协议；
- Video Knowledge 和 Shot 评分分解；
- Timeline IR 到 FFmpeg；
- Prompt 二次编辑与差量重跑；
- 镜头选择解释。

## 26.4 明确降级方案

- LLM 不可用：使用旅行模板和确定性参数。
- CLIP 不可用：使用颜色、运动、清晰度与基础图像标签。
- 无语音：跳过字幕或仅显示标题卡。
- 无音乐：生成无背景音乐版本。
- GPU 不可用：使用 CPU 模型和更低抽帧率。

---

# 27. 迭代计划

## Phase 0：工程基线（第 1 周）

- 建立 Java/Python/Frontend 目录和 Docker Compose。
- 接通 MySQL、Redis、MinIO。
- 定义 OpenAPI、Workflow DSL、Tool Manifest 和核心表。
- 完成 `video.probe` Tool 的端到端调用。

**里程碑：** Java 创建 Task，Python 执行 Tool，结果写入 Artifact，前端看到状态。

## Phase 1：DAG 执行内核（第 2-3 周）

- WorkflowDefinition、Validator、状态机、Scheduler。
- 并行、重试、取消、恢复、WebSocket。
- Tool Registry 与幂等。

**里程碑：** 可运行包含并行分支和失败重试的示例 DAG。

## Phase 2：视频理解（第 4-5 周）

- Probe、代理视频、Shot Detection、转写、质量评分、场景标签。
- Video Knowledge 与 Shot 浏览器。

**里程碑：** 用户可查看结构化 Shot 分析结果。

## Phase 3：Agent 决策与成片（第 6-7 周）

- Intent Parser、模板库、Planner、Validator/Repair。
- Ranking、Highlight、Story、Subtitle、Transition、Timeline、Render。

**里程碑：** 自动生成 30 秒旅行 Vlog。

## Phase 4：二次编辑与解释（第 8 周）

- Prompt Editing、Impact Analyzer、版本管理、Plan Diff。
- DecisionRecord 和解释页面。
- E2E、性能、故障恢复与演示脚本。

**里程碑：** 完成“生成 -> 解释 -> 修改 -> 新版本”的完整演示。

若实训周期更短，应优先保证 Phase 0-3 的纵向闭环，把复杂 OCR、Music Beat、LUT 和多场景模板后移。

---

# 28. 推荐代码仓库结构

```text
WwDa3B884n8dj/
  README.md
  docs/
    architecture.md
    api/
    adr/
  deploy/
    docker-compose.yml
    env.example
  control-plane/
    pom.xml
    src/
  tool-service/
    pyproject.toml
    app/
    tools/
    tests/
  web-app/
    package.json
    src/
  contracts/
    workflow.schema.json
    timeline.schema.json
    tool-manifest.schema.json
    llm/
    openapi/
  samples/
  scripts/
```

`contracts/` 是 Java、Python、前端共享的契约源。建议通过 CI 检查 Schema 兼容性，不复制多份后各自修改。

---

# 29. 技术选型与关键架构决策

## ADR-001：首版使用模块化单体而非微服务

**决定：** Java 控制面作为一个 Spring Boot 应用，内部按领域模块隔离。  
**理由：** 实训团队和周期有限；核心挑战是 Agent 与 DAG，不是服务治理。  
**演进触发条件：** 某模块需要独立扩缩容、独立发布或出现明确团队边界。

## ADR-002：MySQL 是工作流状态真相

**决定：** TaskRun/WorkflowRun 的权威状态进入 MySQL。  
**理由：** Redis 消息和锁可能丢失，系统必须支持重启恢复和审计。

## ADR-003：Java 通过异步 HTTP 调用 Python

**决定：** Tool Execution 使用 `POST + 202 + callback/polling`。  
**理由：** 视频任务耗时长，同步 HTTP 容易超时；HTTP 契约满足语言解耦和远程部署。

## ADR-004：Planner 输出 Workflow DSL，不输出命令

**决定：** LLM 只生成受约束的声明式计划。  
**理由：** 可验证、可解释、可版本化，避免命令注入和不可控执行。

## ADR-005：Artifact 不可变

**决定：** 编辑产生新版本和新 Artifact，不覆盖旧结果。  
**理由：** 支持缓存、血缘、审计、回退和可重复实验。

## ADR-006：MVP 选择旅行 Vlog 单场景

**决定：** 先完成一个端到端高质量场景。  
**理由：** 多场景同时开发会扩大模型、规则、素材和测试矩阵，削弱闭环完成度。

---

# 30. 风险与应对

| 风险 | 影响 | 应对 |
|---|---|---|
| 功能范围过大 | 无法完成闭环 | 强制 MVP Tool 清单与阶段验收 |
| LLM 计划不稳定 | DAG 不可执行 | 模板、Schema、Validator、Repair、Fallback |
| 视频处理耗时 | 演示等待长 | 代理视频、短样例、缓存、并行、低分辨率预览 |
| GPU/模型环境复杂 | 部署失败 | CPU 降级、模型适配层、启动健康检查 |
| FFmpeg 参数复杂 | 渲染失败或注入 | Timeline IR、白名单编译器、Golden Test |
| 任务状态不一致 | 卡住或重复执行 | MySQL 真相、幂等键、租约、补偿扫描 |
| 中间文件过大 | 磁盘耗尽 | 对象存储、生命周期、配额与清理任务 |
| AI 结果难评估 | 无法证明效果 | 小型标注集、指标、人工评分和回归样例 |
| 解释与事实不一致 | 降低可信度 | 先记录结构化证据，再生成自然语言 |
| 新 Tool 破坏兼容性 | 工作流回归 | 语义化版本、Schema 契约测试、灰度启用 |

---

# 31. MVP 验收清单

## 功能验收

- [ ] 可创建项目并上传视频。
- [ ] 可输入旅行 Vlog 自然语言目标。
- [ ] 可生成通过校验的 Workflow DAG。
- [ ] 至少三个分析 Task 可并行执行。
- [ ] 前端可实时展示任务状态和进度。
- [ ] 失败 Task 可自动重试并可手动重试。
- [ ] 可生成 Video Knowledge 和 Shot 列表。
- [ ] 可展示 Shot 总分与评分分解。
- [ ] 可生成约 30 秒成片。
- [ ] 可解释至少主要入选和淘汰镜头。
- [ ] “字幕放大”编辑不重新执行 ASR。
- [ ] 可查看新旧 Workflow/Timeline 版本差异。

## 工程验收

- [ ] Java 不直接启动 Python 脚本。
- [ ] Tool 通过版本化 HTTP API 调用。
- [ ] MySQL 中可恢复 WorkflowRun 和 TaskRun。
- [ ] Redis 清空后不丢失权威业务状态。
- [ ] Artifact 具有内容哈希和生产血缘。
- [ ] Planner 计划经过确定性 DAG 校验。
- [ ] Tool 调用支持幂等键。
- [ ] 关键模块具有单元、契约和 E2E 测试。
- [ ] Docker Compose 可启动完整 MVP 环境。
- [ ] 演示素材和演示脚本可重复执行。

---

# 32. 后续扩展方向

- 游戏集锦、知识分享、产品宣传、会议总结模板。
- OCR、说话人分离、人脸隐私保护、Logo/品牌检测。
- Beat 检测与音乐节奏同步。
- 更丰富的转场、LUT 和动效。
- 多模态 LLM 直接评价候选关键帧。
- 向量数据库与跨项目素材语义搜索。
- 人工审阅节点和 Human-in-the-loop Workflow。
- Tool Marketplace、签名插件和运行沙箱。
- Kubernetes 下的 CPU/GPU Worker 自动扩缩容。
- A/B 方案生成、自动质量评价和最佳方案选择。

---

# 33. 术语表

| 术语 | 含义 |
|---|---|
| Agent | 能理解目标、规划、调用工具、观察结果并修正的控制实体 |
| Planner | 将需求、上下文和 Tool 能力转换为 Workflow 的组件 |
| Workflow | 有版本的任务依赖定义 |
| DAG | 有向无环图，用于表达 Task 依赖和并行关系 |
| TaskDefinition | 工作流中的节点定义 |
| TaskRun | 节点的一次执行实例 |
| Tool | 可通过统一协议调用的原子或复合能力 |
| Artifact | Task 产生或消费的不可变数据对象 |
| Context | Agent 和 Workflow 使用的结构化上下文 |
| Video Knowledge | 视频及 Shot 的共享分析知识 |
| Shot | 连续摄影镜头，是主要剪辑单位 |
| Timeline | 声明式编辑与渲染中间表示 |
| Plan Diff | 两个计划版本之间的节点和参数差异 |
| DecisionRecord | 决策、评分、证据和解释的结构化记录 |

---

# 34. 结论

本项目的核心价值不在于堆叠字幕、滤镜和贴纸，而在于建立一个可规划、可执行、可恢复、可解释、可扩展的智能视频生产系统。设计以 Workflow 和 Task 为中心，以标准化 Tool 为能力边界，以 Video Knowledge 为共享语义层，以 Timeline 为安全渲染中间表示。

对暑期实训而言，最重要的实施原则是：先完成“旅行 Vlog”这一条可演示的完整纵向链路，再扩展更多模型与场景。只要 MVP 真正实现自然语言需求、动态 DAG、Shot 级理解、Tool Calling、可视化执行、差量编辑和解释记录，就已经能够显著区别于普通视频编辑器和简单 Prompt-to-FFmpeg 项目，并充分体现 Agent 与软件体系结构价值。
