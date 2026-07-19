# 第四阶段交接：确定性 Shot 决策、Story Plan 与结构化 Timeline

> 文档日期：2026-07-19
> 当前阶段：第四阶段已完成，并通过云南长视频双素材 API 与浏览器端到端验证
> 阶段边界：本阶段不调用 LLM，不生成 Shell/FFmpeg 命令，不接入不受约束的 Planner

## 1. 阶段目标与解决的痛点

第三阶段已经能够把多个视频展开为并行 DAG，产出 `SHOT_LIST` 和 `KEYFRAME_IMAGE`，但仍然只回答了“素材里有哪些镜头”，没有回答以下问题：

- 哪些镜头清晰、曝光合理、稳定且有可用运动；
- 如何在数百个跨素材 Shot 中建立稳定、可解释的 Ranking；
- 如何排除低质量、过短、近重复和时间上过近的镜头；
- 如何避免高分镜头全部来自同一个素材；
- 如何把候选镜头组织为固定时长、固定结构的故事；
- 如何把选择结果转为可校验的 Timeline，而不是直接拼接 FFmpeg 字符串；
- 用户如何复用数据库中的历史项目和历史 Workflow，而不是每次重新创建项目、重新上传素材。

第四阶段围绕这些痛点建立了从视觉质量到声明式 Timeline 的确定性决策链，并为第五阶段受约束地接入 LLM 准备了安全契约。

本阶段已经解决：

1. Shot 级确定性质量评分和运动兴趣度；
2. 质量阈值拒绝、时长适配、视觉去重、素材均衡与时间邻近惩罚；
3. 可解释的跨素材 Ranking；
4. 固定 `HOOK -> INTRO -> JOURNEY -> CLIMAX -> ENDING` 的 Story Plan；
5. 精确填满目标时长的 Highlight 与结构化 Timeline；
6. Timeline 在生成 Artifact 前的语义校验；
7. 历史项目、历史素材和历史 Workflow 的数据库读取与浏览器恢复；
8. 第五阶段 LLM Story Proposal 的最小权限 Schema 与语义 Validator。

## 2. 完成的主链路

```text
浏览器选择历史项目或创建新项目
  -> 读取历史素材或批量上传多个视频
  -> POST /projects/{projectId}/multi-asset-analysis-runs
  -> Java 生成并校验 WorkflowDefinition v3
  -> 每个素材并行展开 ASSET 分支

     video.probe
       -> video.proxy-generate
            -> video.shot-detect
                 -> vision.quality-score

  -> 所有素材质量 Artifact 汇聚到 WORKFLOW 分支

     decision.shot-rank
       -> planning.story-template
            -> decision.highlight-select
                 -> timeline.compose

  -> SHOT_QUALITY
  -> SHOT_RANKING
  -> STORY_PLAN
  -> HIGHLIGHT_SET
  -> TIMELINE
  -> 浏览器恢复评分、排名、五段故事和 Timeline Clip
```

双素材会展开为：

```text
2 * 4 个 ASSET Task + 4 个 WORKFLOW Task = 12 个 Task
```

Java 与 Python 仍然只通过异步 HTTP Tool API 通信。Java 不启动 Python 脚本，Python 不访问 Java 数据库；Artifact 仍然不可变，并通过生产 Task、源 Asset、上游 Artifact ID 和内容哈希保留血缘。

## 3. WorkflowDefinition v3 与 DAG Validator

当前模板定义：

```text
definitionKey: MULTI_ASSET_ANALYSIS
definitionVersion: 3
nodes: 8
```

节点与作用域：

| 节点 | Tool | 作用域 | 输入 |
|---|---|---|---|
| `video_probe` | `video.probe@1.0.0` | ASSET | PROJECT_ASSET |
| `video_proxy_generate` | `video.proxy-generate@1.0.0` | ASSET | VIDEO_METADATA/Asset 上下文 |
| `video_shot_detect` | `video.shot-detect@1.0.0` | ASSET | VIDEO_PROXY |
| `vision_quality_score` | `vision.quality-score@1.0.0` | ASSET | VIDEO_PROXY + SHOT_LIST |
| `shot_ranking` | `decision.shot-rank@1.0.0` | WORKFLOW | 全部 SHOT_QUALITY |
| `story_plan` | `planning.story-template@1.0.0` | WORKFLOW | SHOT_RANKING |
| `highlight_selection` | `decision.highlight-select@1.0.0` | WORKFLOW | STORY_PLAN |
| `timeline_compose` | `timeline.compose@1.0.0` | WORKFLOW | HIGHLIGHT_SET |

Validator 当前检查：

- definition key 和正版本号；
- node key 格式与唯一性；
- Tool 名称和版本白名单；
- 每个 Tool 的参数白名单；
- 边端点存在、无重复边、无自环；
- 拓扑排序必须覆盖全部节点；
- `UPSTREAM_ARTIFACT` 节点必须存在前置依赖；
- WORKFLOW 节点不能直接绑定单一 `PROJECT_ASSET`；
- 禁止 `WORKFLOW -> ASSET` 的反向作用域边。

客户端和 LLM 仍不能提交任意可执行 DAG。当前可执行定义由服务端 `MultiAssetAnalysisTemplate` 生成并持久化快照。

## 4. 数据模型、Artifact 与兼容策略

第三阶段的数据模型继续作为权威执行状态：

- `workflow_assets` 保存 Workflow 与有序 Asset 集合；
- `task_dependencies` 保存任意 Task 依赖边；
- `TaskRun.assetId` 标识 ASSET 分支；
- `TaskRun.instanceKey` 唯一标识展开后的节点实例；
- `WorkflowRun.definitionKey/version/json` 保存执行定义快照；
- MySQL 保存 Project、Asset、Workflow、Task、Tool Execution 和 Artifact 状态。

第四阶段新增 Artifact：

| Artifact | 生产 Tool | 主要内容 |
|---|---|---|
| `SHOT_QUALITY` | `vision.quality-score` | 每个 Shot 的视觉指标、运动、指纹、原因码和完整血缘 |
| `SHOT_RANKING` | `decision.shot-rank` | 阈值、资格状态、基础分、惩罚、最终分、排名和解释 |
| `STORY_PLAN` | `planning.story-template` | 五段式故事预算、候选 Shot、裁剪范围、角色和选择理由 |
| `HIGHLIGHT_SET` | `decision.highlight-select` | 已验证 Story Plan 的不可变编译结果 |
| `TIMELINE` | `timeline.compose` | 画布、视频轨道、Clip、源范围、目标范围、角色和验证结果 |

第二阶段单素材 `POST /video-proxy-runs` 继续保留。旧数据库中的 `workflow_runs.asset_id` 继续保存第一个 Asset 作为兼容字段，完整集合以 `workflow_assets` 为准。

## 5. 确定性 Shot Quality Score

`vision.quality-score@1.0.0` 对每个 Shot 默认均匀抽取 3 张 `160x90` 灰度帧，计算：

- `clarity`：Laplacian 方差经对数归一化后的清晰度；
- `exposure`：平均亮度居中程度，并惩罚过黑和过白裁切；
- `stability`：相邻采样帧平均绝对差推导的稳定性；
- `composition`：左右和上下亮度质量平衡；
- `motionLevel`：相邻帧变化量；
- `motionInterest`：偏好适度运动，惩罚完全静止和剧烈抖动；
- `visualFingerprint`：关键帧的 64 位灰度感知指纹。

当前总分：

```text
qualityScore =
  0.30 * clarity
  + 0.22 * exposure
  + 0.18 * stability
  + 0.18 * composition
  + 0.12 * motionInterest
```

评分输出原因码，例如：

```text
HIGH_CLARITY
BALANCED_EXPOSURE
STABLE_MOTION
BALANCED_COMPOSITION
INTERESTING_MOTION
LOW_VISUAL_QUALITY
```

该模型是可复现的 CPU 确定性基线，不依赖 LLM、CLIP 或外部视觉 API。

## 6. Ranking v2

`decision.shot-rank@1.0.0` 汇聚所有素材的 `SHOT_QUALITY`，策略标识为：

```text
DETERMINISTIC_MMR_QUALITY_MOTION_DIVERSITY_V2
```

### 6.1 资格阈值

当前拒绝规则：

```text
qualityScore < 0.45       -> BELOW_QUALITY_THRESHOLD
clarity < 0.25            -> TOO_BLURRY
exposure < 0.25           -> POOR_EXPOSURE
durationMs < 800          -> TOO_SHORT
```

被拒绝的 Shot 仍保留在 `SHOT_RANKING` 中，`eligible=false`、`finalScore=0`，便于审计和前端解释，但 Story 正常情况下只使用合格 Shot。

### 6.2 基础分

```text
baseScore =
  0.72 * qualityScore
  + 0.13 * motionInterest
  + 0.10 * durationFitness
  + 0.05 * boundaryConfidence
```

`durationFitness` 偏好适合剪辑的中等时长，同时允许较长 Shot 为 9 秒 Story Beat 提供连续画面。

### 6.3 多样性惩罚

Ranking 逐个选择下一个 Shot，并保存每项惩罚：

- `nearDuplicatePenalty`：64 位视觉指纹相似度超过 0.82 后逐步增加；
- `assetBalancePenalty`：已过度使用的素材相对于最少使用素材受到惩罚；
- `temporalProximityPenalty`：同一素材中与已选 Shot 中心点小于 8 秒时受到惩罚；
- 非合格 Shot 额外受到资格惩罚。

最终输出包含 `baseScore`、`finalScore`、全部惩罚、拒绝原因、排名和 `rankingReasons`，不存在无法解释的单一黑盒分数。

## 7. Story Plan、Highlight 与 Timeline

### 7.1 确定性 Story Plan

`planning.story-template@1.0.0` 在 Ranking 与 Highlight 之间新增 `STORY_PLAN` 安全中间表示。当前模板固定为：

```text
TRAVEL_JOURNEY_V1

HOOK     11.67%
INTRO    15.00%
JOURNEY  30.00%
CLIMAX   30.00%
ENDING   剩余时长
```

30 秒目标对应本次真实结果：

```text
HOOK      3,501 ms
INTRO     4,500 ms
JOURNEY   9,000 ms
CLIMAX    9,000 ms
ENDING    3,999 ms
TOTAL    30,000 ms
```

每个 Beat 使用质量、运动、构图、稳定性、时长适配和粗粒度时间位置生成确定性角色分。选择时优先使用当前 Story 中使用次数最少、且能够精确填满 Beat 的素材，因此最终多素材分布不会被 Story 层重新破坏。

Story Validator 检查：

- Beat 顺序固定且每段非空；
- Shot ID 必须来自对应 Ranking；
- Shot ID 在全部 Beat 中唯一；
- `storyRole` 与 Beat 一致；
- `sourceInMs/sourceOutMs` 不越过源 Shot；
- 裁剪时长与源范围一致且不少于 600 ms；
- 每段实际时长等于预算；
- 全部 Beat 精确填满目标时长；
- 总 Shot 数不超过 `maxShots`。

### 7.2 Highlight Selection

`decision.highlight-select@1.0.0` 不再自行重新排名，而是把已验证 `STORY_PLAN` 编译为不可变 `HIGHLIGHT_SET`。这样 Highlight、Story 和 Timeline 对 Shot、角色、时长与选择理由保持一致。

### 7.3 结构化 Timeline

`timeline.compose@1.0.0` 只消费 `HIGHLIGHT_SET`，生成版本 1 的声明式视频轨道。每个 Clip 保存：

- `clipId`、`shotId`、`assetId`；
- `sourceProxyArtifactId`；
- 源 Shot 边界与实际源裁剪范围；
- Timeline 入点和出点；
- `selectionRank`、`storyRole`、`selectionReasons`；
- 固定 `playbackRate=1.0`；
- 当前只允许零时长 `CUT`。

Timeline 不包含 Shell、FFmpeg 字符串、任意路径或任意执行参数。

`TimelineValidator` 在 Artifact 写入前检查：

- 画布尺寸、偶数宽高与 FPS；
- 单条 VIDEO 轨道；
- Clip ID 和 Shot ID 唯一；
- 源范围不越过源 Shot；
- 源时长与 Timeline 时长一致；
- Clip 从 0 开始并连续无空洞；
- Timeline 总时长等于轨道末尾；
- `storyRole` 白名单；
- 只允许零时长 `CUT`。

契约位于：

```text
contracts/story/story-plan.schema.json
contracts/timeline/timeline.schema.json
```

## 8. Scheduler 与 Java/Python Tool 边界

Java 按 `task_dependencies` 扫描 Task：

- 全部上游 `SUCCEEDED`：进入 `READY`，事务提交后异步分发；
- 任一上游 `FAILED/SKIPPED`：进入 `SKIPPED`；
- 全部 Task 成功：Workflow `SUCCEEDED`；
- 存在失败或跳过：Workflow `FAILED`；
- 进度按终态 Task 数量和 Tool 中间进度展示。

第四阶段最终可靠性收尾增加：

- Workflow 终态评估和 Tool 回调在同一数据库 `PESSIMISTIC_WRITE` 行锁下提交，不再依赖事务提交前释放的 JVM `synchronized`；
- `RETRY_WAIT` 状态、`retryCount` 和 `nextAttemptAt` 持久化到 `task_runs`；
- 分发结果不确定时复用同一 attempt 和幂等键，防止重复 Tool Execution；
- Tool 明确返回 `retryable=true` 或旧 Execution 连续不可达时，才创建新 attempt；
- 默认最多 3 次尝试，使用指数退避；
- 定时补偿扫描按单个 Workflow 事务恢复 `READY`、超时 `DISPATCHING` 和到期 `RETRY_WAIT`；
- Tool Execution 连续轮询失败达到上限后标记 `LOST`，Task 进入有限重试或最终失败；
- 快照和前端任务卡显示 `attempt` 与 `retryCount`，保留重试审计信息。

输入按 Tool 白名单和 Artifact 类型绑定：

```text
video.shot-detect          <- VIDEO_PROXY
vision.quality-score       <- VIDEO_PROXY + SHOT_LIST
decision.shot-rank         <- one or more SHOT_QUALITY
planning.story-template    <- SHOT_RANKING
decision.highlight-select  <- STORY_PLAN
timeline.compose           <- HIGHLIGHT_SET
```

Python Registry 当前显式注册 8 个 Tool：

```text
video.probe@1.0.0
video.proxy-generate@1.0.0
video.shot-detect@1.0.0
vision.quality-score@1.0.0
decision.shot-rank@1.0.0
planning.story-template@1.0.0
decision.highlight-select@1.0.0
timeline.compose@1.0.0
```

## 9. API 与历史记录

当前 Java API：

```text
POST /api/v1/projects
GET  /api/v1/projects
POST /api/v1/projects/{projectId}/assets
POST /api/v1/projects/{projectId}/assets/batch
GET  /api/v1/projects/{projectId}/assets
POST /api/v1/projects/{projectId}/video-proxy-runs
POST /api/v1/projects/{projectId}/multi-asset-analysis-runs
GET  /api/v1/projects/{projectId}/workflow-runs
GET  /api/v1/workflow-runs/{workflowRunId}
GET  /api/v1/artifacts/{artifactId}/content
POST /internal/tool-callbacks
```

`GET /projects/{projectId}/workflow-runs` 返回按创建时间倒序的历史摘要，包括：

- Workflow ID、类型、定义版本与清晰度；
- 状态、进度和错误；
- Asset 数、Task 数；
- 创建、开始和完成时间。

选择某条记录后，前端继续使用 Workflow Snapshot API 恢复 Asset、Task、Artifact 元数据、质量分、Ranking、Story、Highlight 和 Timeline。

## 10. 前端

当前 Spring Boot 静态页面支持：

1. 启动时读取数据库中的历史项目；
2. 选择历史项目并恢复其素材列表；
3. 在历史项目中继续上传素材或直接启动新 Workflow；
4. 选择项目下的历史 Workflow 并恢复完整页面；
5. 按素材和 Workflow 作用域动态展示 12 个实际 Task；
6. 展示全部 Shot 关键帧、质量分和 Ranking 名次；
7. 标记进入 Highlight/Timeline 的 Shot；
8. 展示五个 Story Beat 的目标/实际时长和 Shot 数；
9. 展示 Timeline Clip 的素材、角色、源范围和 Ranking；
10. 长视频关键帧使用懒加载，避免初始页面一次请求数百张图片。

页面仍是无构建的静态 HTML/CSS/JavaScript，不是独立 Vue/React 工程。

## 11. 第五阶段 LLM 安全契约准备

第四阶段没有读取或调用用户准备的 DeepSeek API Key，但已经冻结首个受约束输出契约：

```text
contracts/llm/story-plan-proposal.schema.json
```

未来 LLM 只允许输出：

- 固定 `TRAVEL_JOURNEY_V1` 模板；
- 五个固定 Beat 与每段目标时长；
- 服务端提供的候选 `shotId`；
- 受限原因码；
- 假设和置信度。

契约没有以下字段：

```text
Shell
FFmpeg
Tool name/version
URI/path
SQL
任意 parameters
```

`LlmStoryProposalValidator` 还会做 Schema 之外的语义检查：未知 Shot、跨 Beat 重复、时长预算、固定顺序、原因码白名单和 `maxShots`。LLM Proposal 不能直接执行，必须先通过确定性 Validator，再由服务端编译为 `STORY_PLAN`。

## 12. 自动化测试

最终测试结果：

```text
Java:   14 passed
Python: 19 passed
```

Java 覆盖：

- ProxyQuality；
- Task 状态机；
- Tool HTTP 契约序列化；
- WorkflowDefinition 的节点、Tool、参数、作用域和 DAG 校验；
- v3 模板包含 Story 节点并按正确边连接。

Python 覆盖：

- Probe 与代理参数；
- Shot Detection；
- 质量评分与低质量原因；
- 64 位视觉指纹；
- 阈值拒绝与近重复惩罚；
- 跨素材 Ranking 和 Story 素材均衡；
- 五段 Story 精确时长；
- Highlight 编译；
- Timeline 连续性与越界拒绝；
- LLM Proposal 的未知/重复 Shot、预算与原因码校验。
- Task 同 attempt 幂等重放、新 attempt 重试和重试上限；
- Python 将参数/契约错误标记为不可重试，将运行时和 I/O 错误标记为可重试。

受限执行环境仍可能提示无法创建 `.pytest_cache`，不影响 19 个测试通过。

## 12.1 失败收敛与恢复 E2E

历史卡住 Workflow：

```text
Workflow: a614311d-73ae-4d41-8dbd-718cb01f8333
Before: RUNNING 66%，一条 Probe FAILED，另一条分支停在 DISPATCHING/PENDING
After recovery scan: FAILED 100%
Final error: ClosedChannelException
```

补偿扫描恢复了另一条分支的幂等执行并让所有 Task 进入终态，Workflow 不再永久停留在 `RUNNING`。

云南长视频停服/恢复故障注入：

```text
Workflow: 10fa1858-570f-4aa5-9f80-f0476ab6c4d5
Initial condition: Tool Service intentionally stopped
Probe state: RETRY_WAIT, attempt=1, retryCount=1
Recovery: start Tool Service before retry budget is exhausted
Final status: SUCCEEDED
Tasks: 12/12 SUCCEEDED
Root Probe audit: attempt=1, retryCount=2
```

最终 Artifact 数量与正常云南 E2E 相同：257 个 `KEYFRAME_IMAGE`，并包含 2 个 `SHOT_QUALITY` 以及各 1 个 `SHOT_RANKING`、`STORY_PLAN`、`HIGHLIGHT_SET` 和 `TIMELINE`。这证明服务暂时不可用时同一幂等 attempt 能自动恢复，且不会重复建立逻辑 attempt。

## 13. 真实云南长视频 E2E

项目：

```text
Project: 7e6aaec2-1bd5-4dcf-b240-f534cbe6b87e
Name:    云南长视频第四阶段回归
```

素材：

| 素材 | 时长 | Shot 数 |
|---|---:|---:|
| `社恐的一个人旅行vlog_3雨崩神瀑徒步.mp4` | 399.104 秒 | 104 |
| `社恐的一个人旅行vlog_2报团拼房居然拼到了大爷...日照金山_雨崩进村_丽江古城.mp4` | 688.774 秒 | 153 |

最终修正版 Workflow：

```text
Workflow: 2e215678-b336-49e7-8854-3ad99c953f18
Definition: MULTI_ASSET_ANALYSIS v3
Quality: 720P
Status: SUCCEEDED
Tasks: 12/12 SUCCEEDED
```

Artifact 数量：

```text
VIDEO_METADATA=2
VIDEO_PROXY=2
SHOT_LIST=2
KEYFRAME_IMAGE=257
SHOT_QUALITY=2
SHOT_RANKING=1
STORY_PLAN=1
HIGHLIGHT_SET=1
TIMELINE=1
```

Ranking 结果：

```text
总候选 Shot: 257
eligible: 219
rejected: 38
Top 20 素材分布: 10 / 10
nearDuplicatePenalty > 0: 90
assetBalancePenalty > 0: 97
temporalProximityPenalty > 0: 186
```

Story 与 Timeline：

```text
Beat: HOOK -> INTRO -> JOURNEY -> CLIMAX -> ENDING
Story 素材分布: 3 / 2
Story Shot: 5 unique shots
Story duration: 30,000 ms
Highlight duration: 30,000 ms
Timeline duration: 30,000 ms
Canvas: 1280 x 720 @ 30 FPS
Source ranges valid: true
Timeline validation.valid: true
```

修正前的 v3 Workflow `24aab242-f833-4dc1-b5c1-c9e35065ca04` 也成功产出 12 个 Task 和全部 Artifact，但 Story 的素材分布为 4/1。根据真实结果，本阶段增加了 Story 层的最少使用素材优先规则；最终 Workflow 已验证为 3/2。

此前 v2 基线 Workflow `054efacb-060a-4431-87ff-91511c18d8e2` 产出 257 个候选、9 个 Clip 和 30 秒 Timeline，用于确认 Ranking v2 与 Story Plan 升级没有丢失长视频 Shot 分析能力。

## 14. 浏览器历史恢复 E2E

浏览器从数据库选择“云南长视频第四阶段回归”，再选择最终成功 Workflow，实际恢复：

```text
Project assets: 2
Workflow status: SUCCEEDED
Task cards: 12
Shot cards: 257
Story beat cards: 5
Timeline clips: 5
Timeline roles: HOOK, INTRO, JOURNEY, CLIMAX, ENDING
Console warnings/errors: 0
```

因此用户无需每次新建项目或重新上传长视频，可以读取数据库中的历史项目、继续使用其素材，并回看历史 Workflow 的完整决策快照。

## 15. 凭据与本地数据处理

- MySQL 密码只从用户指定的仓库外文件读入 Java 进程环境；
- 密码没有输出、复制或写入源码、配置、测试和文档；
- DeepSeek API Key 未读取、未调用；
- 仓库扫描未发现真实密码或 API Key；
- E2E 使用已上传的数据库 Asset，没有复制长视频素材到仓库。

## 16. 当前技术债与未解决问题

### 16.1 调度与恢复

- Workflow 终态已使用数据库行锁串行化，但 Task claim 仍没有跨实例 lease/owner，多个控制面实例同时扫描时仍依赖 Tool 幂等键防止重复执行；
- 已有有限自动重试和补偿扫描，但尚无人工重试、取消、优先级、死信队列和管理员恢复 API；
- Python Tool Execution 状态仍主要在内存中，Tool Service 重启后由 Java 将丢失 Execution 标记为 `LOST` 并重试，不能恢复其原进度；
- 当前 Pydantic/Python 组合在 Tool Service 启动时仍会对部分 alias 字段输出 `UnsupportedFieldAttributeWarning`；请求解析和 19 个测试均正常，但应在升级依赖或模型声明后消除日志噪声；
- 当前重试分类以 Python `ValueError` 和 Tool `retryable` 标志为边界，后续应细分 FFmpeg 错误码、存储错误、资源不足和永久媒体损坏。

### 16.2 评分与语义

- 质量模型仍是启发式 CPU 基线，没有人工标注集、相关性指标和可调权重版本管理；
- 运动信号使用灰度帧差，不能区分镜头运动、主体运动和抖动；
- 视觉指纹只能识别近似构图，不能做语义去重；
- 没有 OCR、ASR、人物、地点、镜头类型和场景标签；
- Story 使用质量、运动和粗粒度时间位置，不理解“雪山、古城、人物、徒步”等语义；
- 当前每个 9 秒 Beat 可能由一个较长 Shot 填满，节奏变化仍有限。

### 16.3 Timeline 与成片

- 当前只有单 VIDEO 轨道、`CUT` 和 1.0 播放速率；
- 没有音频、字幕、音乐、转场计划、色彩、画幅适配和标题卡；
- 尚未实现受控 Renderer，因此 Timeline 还没有编译为最终 30 秒成片；
- Artifact 使用本地 `file://`，Java 与 Python 仍需共享同一文件系统；
- 缺少 Timeline Schema 的跨语言 CI 校验和版本迁移策略。

### 16.4 数据库与前端

- `ddl-auto=update` 尚未迁移到 Flyway；
- 历史 Workflow 列表会显示异常中断的运行，尚无重试、取消或归档操作；
- 页面仍是静态实现，257 张 Shot 卡全部进入 DOM，未来需要分页、虚拟列表、筛选和 Ranking 解释面板；
- 代理视频预览仍主要展示一个可用 Proxy。

## 17. 第五阶段建议

第五阶段可以开始接入 LLM，但应保持“小权限 Story 决策”，不要立即开放通用 Planner。

推荐顺序：

1. 建立 50-100 个代表性 Shot 的人工质量与精彩度标注集，校准 Ranking 权重和阈值；
2. 增加场景/人物/地点/镜头类型等确定性或受控视觉标签，给 LLM 提供结构化证据；
3. 实现 LLM Provider 抽象，只允许 LLM 生成 `story-plan-proposal.schema.json`；
4. 服务端对候选 Shot ID、预算、原因码和重复引用做语义校验；
5. 校验失败时使用确定性 Story Plan 作为 fallback，不执行模型原始输出；
6. 保存 Prompt 版本、模型、温度、输入候选摘要、原始 Proposal、校验错误和最终编译结果；
7. 在 Story Proposal 稳定后，再实现受控 Timeline Renderer，Renderer 只编译已验证 Timeline IR；
8. 最后再扩展字幕、音乐、转场与受约束的 Timeline 二次编辑。

第五阶段仍必须遵守：

```text
LLM 不生成 Shell
LLM 不生成 FFmpeg
LLM 不选择未注册 Tool
LLM 不提供本地路径或 Artifact URI
LLM 不绕过 Ranking/Story/Timeline Validator
LLM 输出失败时使用确定性 fallback
```

## 18. 本机运行方式

Python Tool Service：

```powershell
scripts\start-tool-service.cmd
```

成功标志：

```text
Uvicorn running on http://127.0.0.1:8090
```

Java Control Plane：

```powershell
$env:MYSQL_USER = "root"
$env:MYSQL_PASSWORD = "你的 MySQL 密码"
scripts\start-control-plane.cmd
```

成功标志：

```text
Tomcat started on port 8080
```

访问：

```text
http://127.0.0.1:8080
```

测试：

```powershell
scripts\run-java-tests.cmd
scripts\run-python-tests.cmd
```

## 19. 新窗口提示词

```text
这是 Agent-Driven Intelligent Video Production Pipeline 项目。

开始前必须阅读：
1. docs/fourth-stage-handoff.md
2. docs/third-stage-handoff.md
3. docs/second-vertical-slice.md
4. docs/first-vertical-slice.md
5. docs/Agent-Driven智能视频制作流水线-系统设计文档.md
6. README.md
7. docs/modules/control-plane/README.md

第四阶段已经完成确定性的多素材 Shot 决策链：每个素材并行执行 video.probe -> video.proxy-generate -> video.shot-detect -> vision.quality-score，再在 Workflow 级执行 decision.shot-rank -> planning.story-template -> decision.highlight-select -> timeline.compose。WorkflowDefinition 为 v3，双素材展开 12 个 Task。新增 SHOT_QUALITY、SHOT_RANKING、STORY_PLAN、HIGHLIGHT_SET 和 TIMELINE Artifact，全部不可变并保留血缘。

Ranking v2 包含质量阈值、运动兴趣、时长适配、64 位视觉指纹、近重复惩罚、素材均衡惩罚和同素材时间邻近惩罚，并输出完整解释。Story 固定为 HOOK -> INTRO -> JOURNEY -> CLIMAX -> ENDING，精确填满目标时长，保证 Shot 唯一、源范围合法，并优先平衡多素材。Timeline 是受约束的声明式 IR，只允许 CUT，不包含 Shell/FFmpeg。

用户可以从数据库选择历史项目和历史 Workflow，无需每次新建项目。浏览器可恢复素材、12 个 Task、257 个 Shot、五段 Story 和 Timeline。

最终云南长视频正常 E2E：Project 7e6aaec2-1bd5-4dcf-b240-f534cbe6b87e，Workflow 2e215678-b336-49e7-8854-3ad99c953f18，720P，SUCCEEDED，2 个 Asset、12 个 Task、257 个 Shot、219 个 eligible、38 个 rejected、5 个 Story/Highlight/Timeline Shot、Timeline 30,000 ms，Story 素材分布 3/2，Timeline validation.valid=true。停服恢复 E2E Workflow 10fa1858-570f-4aa5-9f80-f0476ab6c4d5 也最终 SUCCEEDED，12/12 Task，根 Probe attempt=1、retryCount=2。历史卡住 Workflow a614311d-73ae-4d41-8dbd-718cb01f8333 已由补偿扫描收敛为 FAILED 100%。浏览器恢复 12 Task、257 Shot、5 Beat、5 Clip，控制台无 warning/error。Java 14 tests passed，Python 19 tests passed。

第五阶段可以接入 LLM，但先只实现受约束的 Story Proposal。使用 contracts/llm/story-plan-proposal.schema.json 和 LlmStoryProposalValidator；LLM 只能引用服务端候选 Shot、分配固定 Beat 时长、返回原因码和置信度。不要让 LLM 生成 Shell、FFmpeg、Tool、路径、URI、SQL 或任意参数。校验失败时必须回退到确定性 Story Plan。

开始改动前检查 Git 状态并保留用户已有修改。MySQL 密码和 LLM API Key 只允许从仓库外文件读入进程环境，不得输出或写入仓库。完成后更新文档、运行 Java/Python 测试并做真实多素材 E2E。
```
