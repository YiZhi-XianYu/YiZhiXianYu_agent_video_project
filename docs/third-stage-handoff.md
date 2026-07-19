# 第三阶段交接：多素材 DAG 与 Shot Knowledge

> 文档日期：2026-07-19  
> 当前阶段：第三阶段已完成，并通过双素材 API 与浏览器端到端验证  
> 基线提交：`3684832`（第二阶段）；本文件记录其上的第三阶段增量

## 1. 阶段目标与解决痛点

第二阶段只能为单个素材执行固定两节点串行链路，无法表达未来多素材剪辑需要的并行分析、通用依赖和镜头级知识。第三阶段将其升级为确定性的小型 DAG 内核，并打通第一项镜头理解能力。

本阶段解决：

- 单次 Workflow 只能绑定一个 Asset；
- Task 只有一个可选前置字段，无法表达通用 DAG；
- 前端任务卡片固定写死为 Probe/Proxy；
- 视频只有文件级元数据，没有 Shot 和关键帧；
- 上游失败后下游缺少明确的 `SKIPPED` 传播语义；
- 多根任务并发启动时可能同时更新 Workflow，触发 JPA 乐观锁冲突。

## 2. 完成的主链路

```text
浏览器批量选择多个视频
  -> POST /projects/{projectId}/assets/batch
  -> POST /projects/{projectId}/multi-asset-analysis-runs
  -> Java 校验 WorkflowDefinition
  -> Workflow 与有序 Asset 集合持久化
  -> 每个素材展开独立 DAG 分支

     video.probe
       -> video.proxy-generate
            -> video.shot-detect

  -> VIDEO_METADATA + VIDEO_PROXY + SHOT_LIST + KEYFRAME_IMAGE
  -> 前端按素材动态展示 6 个 Task、Shot 与关键帧
```

不同素材的根 Task 可并行执行；同一素材内部严格根据依赖表解锁。

## 3. 数据模型与兼容策略

新增：

- `workflow_assets`：保存 Workflow 与多个 Asset 的有序关联；
- `task_dependencies`：保存任意 Task 依赖边；
- `TaskRun.assetId`：标识展开后的素材分支；
- `TaskRun.instanceKey`：使用 `assetId:nodeKey` 唯一定位实例；
- `TaskRun.inputBinding` 与 `parametersJson`：保存节点输入绑定和参数快照；
- `WorkflowRun.definitionKey`、`definitionVersion`、`definitionJson`：保存执行定义快照；
- `TaskStatus.SKIPPED`：表达上游失败后的非执行终态。

旧数据库中的 `workflow_runs.asset_id` 仍可能是 `NOT NULL`，Hibernate `ddl-auto=update` 不会自动放宽约束。因此多素材 Workflow 暂时把第一个 Asset 写入旧字段以兼容历史表结构，权威完整集合以 `workflow_assets` 为准。不要手工破坏性修改数据库。

## 4. WorkflowDefinition 与 Validator

契约位于 `contracts/workflow/workflow-definition.schema.json`，Java 模型位于 `control-plane/.../workflow/WorkflowDefinition.java`。

当前定义包含：

- `definitionKey` 与 `definitionVersion`；
- 节点键、Tool 名称/版本、输入绑定和参数；
- 有向边集合；
- `PROJECT_ASSET` 和 `UPSTREAM_ARTIFACT` 两种输入绑定。

Validator 确定性检查节点唯一、边端点存在、禁止自环和拓扑无环。当前定义由服务端 `MultiAssetAnalysisTemplate` 生成，尚未接受客户端或 LLM 提交任意 DAG。

## 5. Scheduler 行为

`WorkflowExecutionService` 根据 `task_dependencies` 扫描 `PENDING` Task：

- 所有上游 `SUCCEEDED`：进入 `READY` 并在事务提交后分发；
- 任一上游 `FAILED` 或 `SKIPPED`：进入 `SKIPPED`；
- 全部 Task 终态且全部成功：Workflow `SUCCEEDED`；
- 存在失败或跳过：Workflow `FAILED`；
- 进度按终态 Task 数量计算。

为避免多个根 Task 同时调用 `workflow.start()` 造成乐观锁冲突，Workflow 在创建事务中立即进入 `RUNNING`，并对单 JVM 内的 Workflow 评估使用同步保护。该锁是当前可接受的本机实现，后续需要升级为按 Workflow 粒度锁或数据库协调方案。

## 6. Shot Detection

新增 `video.shot-detect@1.0.0`：

- 输入上游 `VIDEO_PROXY`；
- 使用 FFmpeg scene score 检测边界；
- 支持 `sceneThreshold` 与 `minShotDurationMs`；
- 输出不可变 `SHOT_LIST` JSON；
- 每个 Shot 输出一张中点 `KEYFRAME_IMAGE`；
- 无显著切镜时输出覆盖全片的单 Shot；
- Shot 保存源 Asset、源 Proxy、时间范围、置信度和关键帧血缘。

## 7. API

第三阶段新增：

```text
POST /api/v1/projects/{projectId}/assets/batch
POST /api/v1/projects/{projectId}/multi-asset-analysis-runs
```

启动请求示例：

```json
{
  "assetIds": ["asset-1", "asset-2"],
  "quality": "1080P"
}
```

`GET /api/v1/workflow-runs/{workflowRunId}` 现在返回 Asset 列表、动态 Task、依赖 Task ID 和 Artifact 外部 ID。第二阶段 `POST /video-proxy-runs` 继续保留。

## 8. 前端

当前 Spring Boot 静态页面支持：

- `<input multiple>` 批量选择视频；
- 批量上传与素材列表；
- 4K、2K、1080p、720p 清晰度选择；
- 按素材分组动态渲染实际 Task；
- 展示 Shot 时间范围、置信度和对应关键帧；
- 继续预览和下载第一个可用代理视频。

浏览器 E2E 期间发现并修复了一个状态覆盖问题：批量上传成功后无参数调用 `renderAssets()` 会把 `assetIds` 清空，导致启动请求校验失败。现在显式传入已上传 Asset 集合。

## 9. 测试与真实 E2E

自动化测试：

```text
Java:   11 passed
Python: 9 passed
```

API 双素材 E2E：

```text
Workflow ID: a1774205-0d71-4b0f-99c9-2d8dfb456382
Quality: 1080P
Status: SUCCEEDED
Tasks: 6/6 SUCCEEDED
SHOT_LIST: 2
KEYFRAME_IMAGE: 2
Total shots: 2
```

素材：

| 素材 | 输入特征 | 结果 |
|---|---|---|
| 水上乐园/横屏_游乐场俯瞰.mp4 | 1920x1080，约 17.66 秒，H.264 + AAC | Probe、1080p Proxy、1 Shot、1 关键帧成功 |
| 自然人文/横屏_古镇.mp4 | 3840x2160，约 14.71 秒，24 FPS，H.264，无音轨 | Probe、1080p Proxy、1 Shot、1 关键帧成功 |

浏览器 E2E：

```text
Workflow ID: fe8aa96e-70dd-4b15-9f61-d13bd93f1ab2
Quality: 720P
Status: SUCCEEDED
Asset items: 2
Task groups: 2
Task cards: 6
Shot cards: 2
Keyframe images: 2
Console errors: 0
```

## 10. 当前技术债

- WorkflowDefinition 仍由服务端模板产生，尚未持久化为可管理、可发布的定义资源；
- `evaluateWorkflow()` 使用 JVM 级粗粒度同步，不能支持多实例部署；
- Scheduler 仍由进程内事件和轮询驱动，尚无数据库 claim/lease；
- Python Tool Execution 和幂等状态仍在内存中，服务重启后丢失；
- Artifact 使用本地 `file://`，Java 与 Python 必须同机；
- `ddl-auto=update` 应迁移到 Flyway；
- 前端仍是无构建的静态页面，且代理预览只展示第一个 Proxy；
- Shot Detection 当前只基于画面切换，不包含质量评分、语义、人物或精彩度；
- 尚未实现自动重试策略、条件边、资源约束和取消传播。

## 11. 第四阶段建议

先保持确定性 DAG 和 Shot 数据稳定，不要立即接入 LLM Planner。推荐下一条链路：

```text
SHOT_LIST
  -> vision.quality-score
  -> shot-ranking
  -> highlight-detect
  -> story-plan
  -> timeline-compose
  -> final-render
```

第四阶段优先目标：

1. 为每个 Shot 计算清晰度、曝光、稳定性和构图等确定性质量分；
2. 建立可解释的 Shot Ranking，支持跨素材筛选候选镜头；
3. 生成第一个结构化 Timeline，而非直接拼 FFmpeg 命令；
4. 让前端按素材/Shot 展示评分、排序理由和是否入选；
5. 增加 Workflow 级重试、取消与按 Workflow 的并发锁。

## 12. 新窗口提示词

```text
这是 Agent-Driven Intelligent Video Production Pipeline 项目。

开始前必须阅读：
1. docs/third-stage-handoff.md
2. docs/second-vertical-slice.md
3. docs/first-vertical-slice.md
4. docs/Agent-Driven智能视频制作流水线-系统设计文档.md
5. README.md
6. docs/modules/control-plane/README.md

第三阶段已经完成真正的多素材分析链路：浏览器批量上传 -> Java WorkflowDefinition/DAG Validator -> 每个素材并行执行 video.probe -> video.proxy-generate -> video.shot-detect -> 输出 VIDEO_METADATA、VIDEO_PROXY、SHOT_LIST 和 KEYFRAME_IMAGE。多素材集合保存在 workflow_assets，Task 边保存在 task_dependencies。第二阶段单素材 API 保留兼容。

真实验证已经使用水上乐园与自然人文两组素材中的两个视频完成：API 1080P Workflow a1774205-0d71-4b0f-99c9-2d8dfb456382 和浏览器 720P Workflow fe8aa96e-70dd-4b15-9f61-d13bd93f1ab2 均 SUCCEEDED；每次都是 2 个 Asset、6 个 Task、2 个 SHOT_LIST、2 个 KEYFRAME_IMAGE。Java 11 tests passed，Python 9 tests passed。

下一阶段先实现确定性的 Shot Quality Score、Ranking、Highlight Selection 和结构化 Timeline，不要直接让 LLM 生成 Shell/FFmpeg，也不要先接入不受约束的 Planner。保持 Java/Python 通过 HTTP Tool API 解耦，Artifact 不可变且保留血缘，密码和本地凭据不得写入仓库。开始改动前检查 Git 状态并保留用户已有修改；完成后更新文档、运行 Java/Python 测试并用真实多素材做 E2E。
```
