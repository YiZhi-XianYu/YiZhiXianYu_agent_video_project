# 第七阶段交接：人工编辑、版本管理与自然语言时长

> 文档日期：2026-07-22
> 当前阶段：第七阶段已完成，用户可在前端编辑 Story Plan、保存多版本、对比差异、应用渲染，并支持自然语言输入成片时长
> 阶段边界：人工编辑和版本管理仅修改 Story Plan 的 shot-to-beat 分配，不改变 DAG 结构、Tool 实现或 LLM 安全契约

## 1. 阶段目标与解决的痛点

第六阶段产出的 Story Plan 由系统自动决定（LLM 或确定性算法），用户只能查看结果，无法干预。如果用户对某个 beat 的镜头选择不满意——例如想把 JOURNEY 段的某个 shot 换成另一个——需要重新运行整个工作流，且无法保存调整后的方案。

第七阶段的核心目标：

- 让用户在前端直接编辑 Story Plan 的 shot 分配（替换、排序、锁定、添加、删除）；
- 支持保存多个命名版本，支持版本列表、Diff 对比、切换加载、回退恢复、删除；
- 支持自然语言输入成片时长（如"快节奏15秒"），LLM 解析为目标毫秒数；
- 编辑后的方案可一键 Apply & Render，生成最终 MP4 成片；
- 所有编辑和版本管理不修改 DAG 和 Python Tool Service，纯 Java + 前端实现。

本阶段已经解决：

1. 前端编辑模式：每个 beat 下的 shot 行增加 lock/reorder/replace/remove 控件，beat 底部增加 "+" 添加镜头按钮；
2. Lock 锁定：锁定的 shot 不能被替换、移动或删除，lock 状态保存在前端内存中；
3. Save Plan：编辑后的 Story Plan 保存到 `custom_story_plans` 表，支持自定义版本名；
4. Apply & Render：Java 侧确定性构建 TIMELINE → 创建 mini WorkflowRun → 调度 video.render 渲染成片；
5. 多版本管理：版本列表、Diff 对比（Added/Removed/Modified/Unchanged）、切换加载、回退恢复（Restore）、删除；
6. 自然语言时长解析：用户在步骤 03 输入自然语言（如"快节奏15秒"、"1分钟慢旅行"），LLM 解析为目标毫秒数；
7. `_compile_shots` deficit 补偿 Bug 修复：非 30 秒默认时长下 `sourceOutMs` 可能超出 Shot 边界的 Bug；
8. Diff 位置检测修复：shot 调换顺序后正确识别为 Modified 而非 Unchanged。

## 2. 总体架构

本阶段未修改 DAG（仍为 11 节点 v4），未新增 Tool。所有改动集中在三个层面：

```
┌──────────────────────────────────────────────────────────┐
│                    前端 (index.html + app.js + styles.css) │
│  Edit Mode | Save Plan | Versions | Diff | Apply & Render │
└──────────────────────────┬───────────────────────────────┘
                           │ HTTP REST
                           ▼
┌──────────────────────────────────────────────────────────┐
│                  Java Control Plane                       │
│                                                          │
│  CustomStoryPlanController  ──▶  custom_story_plans 表    │
│  TimelineComposer           ──▶  确定性构建 TIMELINE JSON  │
│  WorkflowExecutionService   ──▶  createCustomRenderRun()  │
│  WorkflowController         ──▶  durationPrompt 参数       │
│  MultiAssetAnalysisTemplate ──▶  durationPrompt 注入 DAG  │
└──────────────────────────┬───────────────────────────────┘
                           │ HTTP (planning.story-template)
                           ▼
┌──────────────────────────────────────────────────────────┐
│                  Python Tool Service                      │
│                                                          │
│  story_plan.py   ──▶  _parse_duration_prompt() LLM 解析   │
│  shot_decisions.py ──▶  _compile_shots() deficit 补偿修复  │
│  prompt.py       ──▶  DurationParsingPrompt 新增          │
└──────────────────────────────────────────────────────────┘
```

### 2.1 新增文件

| 文件 | 职责 |
|---|---|
| `control-plane/.../plan/CustomStoryPlanEntity.java` | JPA Entity，表 `custom_story_plans`，字段 projectId/sourceWorkflowRunId/planJson(LONGTEXT)/status/versionName |
| `control-plane/.../plan/CustomStoryPlanRepository.java` | JPA Repository，按 workflowRunId+status、projectId、planId 查询 |
| `control-plane/.../plan/TimelineComposer.java` | 工具类，从 custom plan 确定性构建 TIMELINE JSON（复刻 Python `TimelineComposeTool` 逻辑） |
| `control-plane/.../api/CustomStoryPlanController.java` | REST Controller：GET/PUT custom plan、POST apply、GET version-list、GET/DELETE versions/{id}、POST restore/{id} |

### 2.2 修改文件

| 文件 | 变更 |
|---|---|
| `tool-service/app/llm/prompt.py` | 新增 `DurationParsingPrompt`（中英文、快慢节奏→ms） |
| `tool-service/app/tools/story_plan.py` | `execute()` 读取 `durationPrompt` → `_parse_duration_prompt()` LLM 解析；`_select_for_beat()` slack distribution 扩展；`_build_deterministic_story_plan` / `_compile_llm_proposal` 新增全局两轮 balance pass；`targetDurationMs` 上限 60000ms |
| `tool-service/app/tools/shot_decisions.py` | `_compile_shots()` deficit 补偿：capacity 从 `sourceInMs` 计算而非 `startMs`，修复 `sourceOutMs > endMs` Bug |
| `control-plane/.../workflow/MultiAssetAnalysisTemplate.java` | `create()` 新增 `durationPrompt` 重载，非空时注入 `planning.story-template` 节点参数 |
| `control-plane/.../workflow/WorkflowDefinitionValidator.java` | `durationPrompt` 加入 `planning.story-template` 参数白名单 |
| `control-plane/.../execution/WorkflowExecutionService.java` | 新增 `createMultiAssetAnalysisRun()` durationPrompt 重载；新增 `createCustomRenderRun()`：写 TIMELINE artifact → 创建虚拟 producer task → 创建 video.render task → evaluateWorkflow 调度渲染 |
| `control-plane/.../api/WorkflowController.java` | `StartMultiAssetAnalysisRequest` 新增 `durationPrompt` 可选字段 |
| `control-plane/.../resources/application.yml` | 新增 `app.artifact-root` 配置项 |
| `control-plane/.../static/index.html` | 步骤 03 新增时长输入框；edit toolbar（Edit Shots / Save Plan / 版本名输入 / Versions / Apply & Render）；version panel；diff panel |
| `control-plane/.../static/app.js` | 新增 ~500 行：edit mode 状态机、shot CRUD 控件、lock 机制、Save/Apply、版本管理（load/compare/diff/restore/delete）、pollRenderWorkflow、buildClientTimeline |
| `control-plane/.../static/styles.css` | 新增 ~200 行：edit toolbar、shot editing 控件、lock 样式、version panel、diff panel（colored diff rows）、scrollbar 美化 |

### 2.3 删除文件

| 文件 | 原因 |
|---|---|
| `tool-service/tools/` (19 子目录) | 早期占位脚手架，每个目录仅含 README.md，实际 Tool 均在 `app/tools/` |
| `tool-service/workers/` | 早期占位脚手架，仅含 README.md |
| `web-app/` | 早期 React 前端脚手架，所有目录仅含 README.md，实际前端在 `control-plane/.../static/` |

## 3. P1 — 自然语言时长输入

### 3.1 功能

用户在步骤 03 的输入框中输入自然语言描述（如"快节奏15秒"、"1分钟慢旅行"、"30 seconds"），LLM 解析为目标毫秒数，覆盖默认 30 秒。

### 3.2 实现链路

```
用户输入 "快节奏15秒"
  → app.js: startWorkflow() 发送 durationPrompt
  → WorkflowController: StartMultiAssetAnalysisRequest.durationPrompt
  → WorkflowExecutionService.createMultiAssetAnalysisRun(projectId, assetIds, proxyQuality, durationPrompt)
  → MultiAssetAnalysisTemplate.create(assetIds, proxyQuality, definitionVersion, durationPrompt)
     → 注入 planning.story-template 节点参数 {"durationPrompt": "快节奏15秒"}
  → WorkflowDefinitionValidator: durationPrompt ∈ 参数白名单 ✓
  → Python StoryPlanTool.execute()
     → 检测到 durationPrompt 非空
     → _parse_duration_prompt() → DurationParsingPrompt → LLM
     → 返回 {"targetDurationMs": 15000, "parsingReason": "快节奏→较短时长, 15秒→15000ms"}
     → 覆盖默认 30000ms
```

### 3.3 DurationParsingPrompt 设计

System Prompt 要求 LLM 输出严格的 JSON：
```json
{"targetDurationMs": 15000, "parsingReason": "..."}
```

支持中英文混合、快慢节奏修饰词。`targetDurationMs` 限制在 5000–60000ms（5–60 秒）。解析失败时 fallback 到默认 30 秒。

### 3.4 Slack Distribution 扩展

`_select_for_beat()` 和全局 balance pass 从仅处理 30 秒扩展到支持任意时长（5000–60000ms）。非 30 秒默认时长下，LLM 审计偶发 `beats duration deviates too far` 或 `Story Plan does not exactly fill targetDurationMs`——LLM 提案验证失败后回退到确定性算法，成片时间和内容正常，不影响使用（已知限制，暂缓处理）。

## 4. P2 — 人工编辑 Shot 分配

### 4.1 Edit Mode 状态机

```
[Edit Shots] 按钮点击
  → state.editMode = true
  → 显示 Save Plan / Version Name Input / Versions / Apply & Render 按钮
  → renderDecisions() 重新渲染，每个 shot row 增加编辑控件
  → 加载已有 custom plan（如有 DRAFT）
  → 加载版本列表

[Edit Shots] 再次点击（退出编辑）
  → 如 state.dirty，confirm 确认丢弃未保存修改
  → state.editMode = false
  → 隐藏编辑控件，恢复原始 Story Plan 展示
```

### 4.2 Shot 编辑控件

每个 shot row 在编辑模式下展示：

| 控件 | 功能 | 锁定后行为 |
|------|------|-----------|
| 🔒 Lock | 锁定 shot，防止被替换/移动/删除 | — |
| ▲ Up | shot 在 beat 内上移一位 | 禁用 |
| ▼ Down | shot 在 beat 内下移一位 | 禁用 |
| Replace 下拉 | 从可用 shot 池中选择替换 | 禁用 |
| ✕ Remove | 从 beat 中移除该 shot | 禁用 |

每个 beat 底部有 **+** 按钮，居中显示。点击后弹出可用 shot 下拉列表，选择后添加到 beat 末尾。

Lock 状态保存在 `state.lockedShotIds` (Set) 中，仅在当前编辑会话有效，不会持久化到数据库。

### 4.3 Save Plan

```
[Save Plan] 按钮点击
  → 收集当前编辑后的 plan JSON
  → 读取 #save-version-name 输入（可选）
  → PUT /api/v1/workflow-runs/{id}/custom-story-plan
     Body: {"plan": {...}, "versionName": "快剪风格v1"}
  → Java: 旧 DRAFT → SUPERSEDED, 新 DRAFT 写入
  → state.dirty = false
  → 刷新版本列表
```

### 4.4 Apply & Render

```
[Apply & Render] 按钮点击
  → 自动保存当前 plan（如 dirty）
  → POST /api/v1/workflow-runs/{id}/custom-story-plan/apply
  → Java TimelineComposer 确定性构建 TIMELINE JSON
     - 从 custom plan 的 beats[].shots[] 读取 shotId/sourceAssetId/sourceInMs/sourceOutMs
     - 构建单轨道 CUT 转场 Timeline
     - 通过 TimelineValidator 校验
  → 写入 TIMELINE Artifact（producer: 虚拟 task，预 SUCCEEDED）
  → 创建 video.render Task
  → evaluateWorkflow() 调度渲染
  → 前端 pollRenderWorkflow() 轮询渲染进度
  → 渲染完成后展示 <video> 播放器 + 下载链接
```

**架构决策**：不修改 DAG 和 Python Tool Service。Apply 时 Java 侧确定性构建 TIMELINE → 创建 mini WorkflowRun → 复用现有 dispatch/poll/callback 基础设施完成渲染。

## 5. P3 — 方案版本管理

### 5.1 数据模型

`custom_story_plans` 表新增 `version_name` 列（VARCHAR 200, nullable）：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(36) PK | UUID |
| project_id | VARCHAR(36) | 所属项目 |
| source_workflow_run_id | VARCHAR(36) | 来源 WorkflowRun |
| plan_json | LONGTEXT | Story Plan JSON |
| status | VARCHAR(20) | DRAFT / APPLIED / SUPERSEDED |
| version_name | VARCHAR(200) | 用户自定义版本名（nullable） |
| created_at | DATETIME | 创建时间 |

状态流转：
```
新建 → DRAFT
DRAFT → SUPERSEDED (被新版本取代)
DRAFT → APPLIED (已应用渲染)
```

### 5.2 REST API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/{workflowRunId}/custom-story-plan` | 获取当前 DRAFT（或原始 ORIGINAL） |
| PUT | `/{workflowRunId}/custom-story-plan` | 保存/覆盖 DRAFT |
| POST | `/{workflowRunId}/custom-story-plan/apply` | 应用 DRAFT 并启动渲染 |
| GET | `/{workflowRunId}/custom-story-plan/version-list` | 所有版本摘要列表（不含完整 planJson） |
| GET | `/{workflowRunId}/custom-story-plan/versions/{planId}` | 获取指定版本的完整 planJson |
| POST | `/{workflowRunId}/custom-story-plan/restore/{planId}` | 回退：clone 旧版本 → 新 DRAFT |
| DELETE | `/{workflowRunId}/custom-story-plan/versions/{planId}` | 删除非 DRAFT 版本 |

路径使用 `version-list` 而非 `versions`，避免 Spring MVC `AntPathMatcher` 将 `/versions` 与 `/{planId}` 模式歧义匹配。

### 5.3 VersionSummary

版本列表 API 返回轻量摘要，不含完整 `planJson`：

```json
{
  "id": "uuid",
  "versionName": "快剪风格v1",
  "status": "SUPERSEDED",
  "createdAt": "2026-07-22T12:00:00",
  "beatCount": 5,
  "shotCount": 12,
  "totalDurationMs": 30000
}
```

### 5.4 Diff 对比

```
[Compare] 按钮点击
  → GET /versions/{planId} 获取完整 planJson
  → 按 beat role (HOOK→INTRO→JOURNEY→CLIMAX→ENDING) 对齐
  → 每个 beat 内按 shotId 匹配：
     - 仅在 saved 中 → ADDED
     - 仅在 current 中 → REMOVED
     - 两方都有但 sourceInMs/sourceOutMs/rank/index 不同 → MODIFIED
     - 完全相同 → UNCHANGED
  → 渲染 diff panel：绿色=Added, 红色=Removed, 黄色=Modified, 灰色=Unchanged
```

### 5.5 Restore（回退）

```
[Restore] 按钮点击
  → confirm 确认（如有未保存修改）
  → POST /restore/{planId}
     Body: {"versionName": "快剪风格v1 (restored)"}  (可选)
  → Java: clone 旧版本 planJson → 新 DRAFT, 旧 DRAFT → SUPERSEDED
  → 前端: 加载新 DRAFT, state.dirty = true, 刷新版本列表
```

### 5.6 Delete

```
[Delete] 按钮点击
  → confirm 确认
  → DELETE /versions/{planId}
  → 当前 DRAFT 不可删除（返回 400）
  → 刷新版本列表
```

## 6. Bug 修复

### 6.1 `_compile_shots` deficit 补偿 — `sourceOutMs` 超出 Shot 边界

**严重程度**: 🔴 高

**现象**: 非 30 秒默认时长下，`timeline.compose` 报错 "Highlight clip is outside its Shot range"，工作流失败。

**根因**: `_compile_shots()` 的 deficit 补偿循环中，capacity 计算使用了 `startMs`（在 Timeline 中的位置），但 `sourceOutMs` 是基于 `sourceInMs` 的。当 `sourceInMs > startMs`（即高光片段不从 shot 起始位置开始），capacity 被高估，导致 `sourceOutMs > endMs`，违反 `TimelineComposeTool` 的边界校验。

**修复** (`shot_decisions.py`): capacity 改为 `endMs - sourceInMs - selectedDurationMs`。

### 6.2 Diff 位置检测 — 调换顺序误判为 Unchanged

**严重程度**: 🟡 中

**现象**: 在编辑模式中调整 shot 顺序后，Diff 将该 beat 标记为 "Unchanged"。

**根因**: `renderDiff()` 的 MODIFIED 判定仅检查 `sourceInMs`/`sourceOutMs`/`rank`，不检查 shot 在 beat 数组中的位置（index）。

**修复** (`app.js`): MODIFIED 条件新增 `curIdx !== savIdx` 比较。

### 6.3 Remove 按钮（✕）被遮挡

**严重程度**: 🟢 低

**现象**: 编辑模式下 Replace 下拉框太宽（max-width: 140px），遮挡右侧 ✕ 按钮。

**修复** (`styles.css`): Replace 下拉 max-width 缩小至 80px，beat-shot-row 右侧 padding 增大。

## 7. 已知限制

### 7.1 非 30 秒默认时长下 LLM 审计偶发警告

非 30 秒默认时长下，LLM 审计偶发 `beats[3]/[4] duration deviates too far` 或 `Story Plan does not exactly fill targetDurationMs`。LLM 提案验证失败后会回退到确定性算法，成片时间和内容正常，不影响使用。这是第五阶段已知问题的延续（DeepSeek V3 数值推理能力有限）。

### 7.2 Lock 状态不持久化

shot lock 状态仅保存在前端 `state.lockedShotIds` 中，保存 plan 后重新加载时 lock 状态丢失。Lock 是编辑会话级别的临时保护，不是持久化属性。

### 7.3 Edit Mode 不支持撤销

编辑操作（替换、删除、排序、添加）直接修改内存中的 plan，无 undo/redo 栈。用户可通过 Save Plan 前切换版本（Load）来恢复。

### 7.4 Diff 不跨 beat role 匹配

Diff 按 beat role 严格对齐，不支持检测 shot 从一个 beat 移动到另一个 beat 的场景。跨 beat 移动会显示为原 beat 的 REMOVED + 新 beat 的 ADDED。

## 8. 未完成的工作

以下内容不在第七阶段范围内，留待后续：

1. **LLM 优化（P3）**: 评估支持 strict structured output 的模型（如 gpt-4o、claude-sonnet-4-6），改善 duration 求和约束的 LLM 采纳率（当前 ~1/8）；
2. **音频/字幕/转场（P4）**: BGM 背景音乐选择、ASR 语音转写生成字幕、FADE/CROSS_DISSOLVE 转场（当前仅 CUT）；
3. **基础设施完善（P5）**: Flyway 替代 ddl-auto=update、Celery/Redis 替代内存任务队列、Docker Compose 一键部署；
4. **人物检测精度提升**: CLIP 零样本人物检测召回率不理想，可考虑接入 YOLO 或换用 ViT-L-14；
5. **图像预处理**: 当前直接使用 Shot 边界关键帧，未做人脸/主体居中的智能帧选择；
6. **视觉工具并行化**: 三个 CLIP Tool 各走一次前向传播，可合并为一次推理节省 2/3 时间；
7. **Lock 持久化**: 将 lockedShotIds 保存到 custom plan 的 planJson 中；
8. **Undo/Redo**: 编辑操作的历史栈。

## 9. 修改文件清单

```
已修改 (vs 第六阶段):
  .gitignore                                         |   2 +- (新增 web-app 目录)
  tool-service/app/llm/prompt.py                     | ~40 行变更 (DurationParsingPrompt)
  tool-service/app/tools/story_plan.py               | ~80 行变更 (durationPrompt + balance pass 扩展)
  tool-service/app/tools/shot_decisions.py           |   4 行变更 (deficit 补偿 Bug 修复)
  control-plane/.../workflow/MultiAssetAnalysisTemplate.java |  12 + (durationPrompt 重载)
  control-plane/.../workflow/WorkflowDefinitionValidator.java |   1 + (durationPrompt 白名单)
  control-plane/.../execution/WorkflowExecutionService.java   | ~60 行变更 (durationPrompt + createCustomRenderRun)
  control-plane/.../api/WorkflowController.java              |   3 + (durationPrompt 字段)
  control-plane/.../resources/application.yml                |   1 + (app.artifact-root)
  control-plane/.../static/index.html                        | ~30 行变更 (时长输入 + edit toolbar + version/diff panels)
  control-plane/.../static/app.js                            | ~500 行变更 (edit mode + Save/Apply + version management + diff)
  control-plane/.../static/styles.css                        | ~200 行变更 (edit toolbar + version/diff panels + scrollbar)

新增:
  control-plane/.../plan/CustomStoryPlanEntity.java
  control-plane/.../plan/CustomStoryPlanRepository.java
  control-plane/.../plan/TimelineComposer.java
  control-plane/.../api/CustomStoryPlanController.java
  docs/seventh-stage-handoff.md  (本文件)

删除:
  tool-service/tools/              (19 个子目录，仅 README.md 占位)
  tool-service/workers/            (仅 README.md 占位)
  web-app/                         (全部目录仅 README.md 占位)
```

## 10. 自动化测试

```text
Java:   14 passed (无新增失败)
Python: 35 passed (2 预存失败，无新增)
```

Java 覆盖：
- ProxyQuality、Task 状态机、Tool HTTP 契约序列化
- WorkflowDefinition v4 校验（11 节点、DAG 无环、durationPrompt 白名单）

Python 覆盖：
- Probe、代理、Shot Detection、质量评分、视觉指纹
- 跨素材 Ranking、Story 素材均衡、Highlight 编译、Timeline 校验
- LLM Proposal 校验、Duration 解析
- 场景/物体/人物 CLIP 检测
- 重试分类与幂等

## 11. 项目清理

第七阶段完成时对项目做了全面清理，确保可上传至 GitLab：

- 删除 `tool-service/runtime/artifacts/`（2.7GB 历史 Artifact 数据）；
- 删除 `control-plane/runtime/storage/`（397MB 上传素材文件）；
- 删除 `control-plane/runtime/artifacts/`（36KB 历史 Artifact）；
- 删除 `control-plane/target/`（705MB Java 构建产物）；
- 删除全部 `__pycache__/` 目录；
- 删除 `.pytest_cache/`；
- 清空 `tool-service/.env` 中的真实 API Key（已替换为空值）；
- 删除 `tool-service/tools/` 19 个占位脚手架目录；
- 删除 `tool-service/workers/` 占位脚手架目录；
- 删除 `web-app/` 占位脚手架目录（实际前端在 `control-plane/.../static/`）；
- `.gitignore` 已覆盖 `.vscode/`、`runtime/`、`target/`、`__pycache__/`、`.env`、`.DS_Store`、`Thumbs.db`、`*.log`。

## 12. 本机运行方式

**前置条件**：

1. MySQL 8.0 本机运行；
2. Conda 环境 `agent-video-pipeline`（Python 3.12 + torch + transformers + pillow + python-dotenv）；
3. CLIP 模型已下载至 HuggingFace 缓存 `~/.cache/huggingface/hub/models--openai--clip-vit-base-patch32/`；
4. `tool-service/.env` 中配置 LLM API Key（不配置则自动使用 NoopProvider，所有 Story Plan 走确定性算法）。

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

## 13. 新窗口提示词

```text
这是 Agent-Driven Intelligent Video Production Pipeline 项目。

开始前必须阅读：
1. docs/seventh-stage-handoff.md（本文件）
2. docs/sixth-stage-handoff.md
3. docs/fifth-stage-handoff.md
4. docs/fourth-stage-handoff.md
5. docs/third-stage-handoff.md
6. docs/second-vertical-slice.md
7. docs/first-vertical-slice.md
8. docs/Agent-Driven智能视频制作流水线-系统设计文档.md
9. README.md
10. docs/modules/control-plane/README.md

第七阶段已完成人工编辑、版本管理与自然语言时长输入：
- P1：用户在步骤 03 输入自然语言（如"快节奏15秒"），LLM 解析为目标毫秒数，覆盖默认 30 秒
- P2：前端编辑 Story Plan 的 shot 分配（替换、排序、锁定、添加、删除），Save Plan 保存到 custom_story_plans 表，Apply & Render 由 Java TimelineComposer 确定性构建 TIMELINE → video.render 渲染成片
- P3：多版本保存（自定义版本名）、版本列表、Diff 对比（side-by-side）、Load/Switch、Restore 回退、Delete 删除

WorkflowDefinition v4（11 节点）：
每个素材：video.probe → video.proxy-generate → video.shot-detect → (vision.quality-score + vision.scene-classify + vision.object-detect + vision.person-detect)
汇聚后：decision.shot-rank + 3 个视觉 Tool → planning.story-template → decision.highlight-select → timeline.compose

LLM 安全边界：LLM 不生成 Shell/FFmpeg/SQL/URI，不选择未注册 Tool，失败时自动 fallback 到确定性算法。
人工编辑不修改 DAG 和 Python Tool Service，纯 Java + 前端实现。

已知限制：
- 非 30 秒默认时长下 LLM 审计偶发警告（LLM 验证失败后 fallback，成片正常）
- Lock 状态仅编辑会话有效，不持久化
- Edit Mode 无 undo/redo
- Diff 不跨 beat role 匹配
- CLIP 零样本人物检测精度有限（模型能力天花板）
- LLM 采纳率仍低（~1/8，DeepSeek 数值推理能力有限）

项目已清理至可上传 GitLab 状态（缓存删除、API Key 脱敏、占位脚手架删除、.gitignore 齐全）。

Java 14 tests passed，Python 35 tests passed。

下一阶段建议按优先级：P3 LLM 优化 → P4 音频/字幕/转场 → P5 基础设施完善。

开始改动前检查 Git 状态并保留用户已有修改。MySQL 密码和 LLM API Key 只允许从仓库外文件读入进程环境，不得输出或写入仓库。完成后更新文档、运行 Java/Python 测试并做真实多素材 E2E。
```
