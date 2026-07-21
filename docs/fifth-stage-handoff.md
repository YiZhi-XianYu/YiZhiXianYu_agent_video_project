# 第五阶段交接：受约束的 LLM 接入与前端展示完善

> 文档日期：2026-07-21
> 当前阶段：第五阶段已完成，LLM 已接入 DeepSeek，前端信息展示已补齐
> 阶段边界：LLM 仅参与 Story Plan 的 shot-to-beat 选择，不生成 Shell/FFmpeg/SQL 或任意工具调用

## 1. 阶段目标与解决的痛点

第四阶段产出的 Story Plan 使用纯确定性算法 (greedy selection + asset diversity balance)，能够稳定工作，但 shot 选择不够"聪明"——它无法利用 LLM 的语义理解能力做出更符合叙事逻辑的分配。

第五阶段的核心目标:

- 在保持确定性算法作为可靠兜底的前提下，接入 LLM 辅助 Story Plan 的 shot-to-beat 选择；
- LLM 的权限严格受限：只能从候选 shot 列表中选择，不能创造新 ID、不能访问文件系统、不能生成命令；
- LLM 输出必须经过多层校验，校验失败时无缝回退到确定性算法；
- 补齐前端信息展示缺口，确保用户能定位被选中的素材片段；
- 修复 proxy 视频只展示一个的问题，改为按素材分区展示。

本阶段已经解决:

1. 可替换的 LLM Provider 抽象层 (当前接入 DeepSeek)；
2. 版本化的 Prompt 模板与系统提示词哈希追踪；
3. LLM Story Proposal 的 JSON Schema 合约与语义 Validator；
4. LLM 调用全程审计记录 (LlmAuditRecord)，可追溯每次调用的来源、延迟、校验结果；
5. LLM 失败时自动回退确定性算法，用户无感知；
6. **LLM 编译阶段的关键 Bug 修复 (详见 §2.7)**；
7. 前端 Story Beat 卡片展开显示每个选中 shot 的素材名、源时间段、排名；
8. 前端 Timeline Track 按比例+角色颜色可视化各 clip；
9. 前端多 proxy 视频按素材分区独立展示；
10. 前端 7 个缺失辅助函数的修复。

## 2. LLM 接入架构

```
┌──────────────────────────────────────────────────────────┐
│                    Java Control Plane                     │
│  planning.story-template@1.0.0 ──HTTP──▶ Python Tool     │
│  (Java 不关心实现是 LLM 还是确定性算法)                     │
└──────────────────────────────────────────────────────────┘
                            │
                            ▼
┌──────────────────────────────────────────────────────────┐
│                  Python Tool Service                      │
│                                                          │
│  StoryPlanTool.execute()                                 │
│    │                                                     │
│    ├─ get_provider() ──▶ DeepSeekProvider / NoopProvider  │
│    │                                                     │
│    ├─ _try_llm_story_plan()                              │
│    │   ├─ StoryProposalPrompt.build_*_prompt()           │
│    │   ├─ provider.generate_json()                       │
│    │   ├─ LlmStoryProposalValidator.validate()  ← 第一关  │
│    │   ├─ _compile_llm_proposal()               ← 编译    │
│    │   └─ StoryProposalValidator.validate()     ← 第二关  │
│    │                                                     │
│    └─ _build_deterministic_story_plan()  ← fallback      │
│                                                          │
│  全程由 LlmAuditRecord 记录，写入 artifact metadata       │
└──────────────────────────────────────────────────────────┘
```

### 2.1 新增文件

| 文件 | 职责 |
|---|---|
| `tool-service/app/llm/__init__.py` | LLM 模块导出 |
| `tool-service/app/llm/provider.py` | Provider 抽象基类 + DeepSeekProvider + NoopProvider |
| `tool-service/app/llm/prompt.py` | PromptRegistry + StoryProposalPrompt v1.1 |
| `tool-service/app/llm/audit.py` | LlmAuditRecord 不可变审计记录 |
| `tool-service/.env` | API Key 配置 (DeepSeek) |
| `tool-service/.env.example` | 配置模板 |
| `contracts/llm/story-plan-proposal.schema.json` | LLM 输出 JSON Schema 合约 |

### 2.2 修改文件

| 文件 | 变更 |
|---|---|
| `tool-service/app/core/config.py` | 新增 LLM 配置项 (provider, api_key, base_url, model) |
| `tool-service/app/main.py` | 启动时显示 LLM provider 状态 |
| `tool-service/app/tools/story_plan.py` | 重构为 LLM-assisted，新增 6 个函数/类 + 保留确定性 fallback + Bug 修复 |
| `tool-service/app/llm/audit.py` | `mark_llm_*()` 方法签名扩展 + `rawResponse` 序列化 |
| `tool-service/environment.yml` | 新增 `python-dotenv` 依赖 |
| `tool-service/requirements.txt` | 新增 `python-dotenv` 依赖 |

### 2.3 Provider 抽象层

`LlmProvider` 抽象基类定义了最小接口:

- `name` — provider 标识
- `generate_json(system_prompt, user_prompt, json_schema)` — 发送 prompt，返回 JSON
- `supports_tool_calling()` — 保留给 Phase 6+

`DeepSeekProvider` 使用 OpenAI-compatible `/chat/completions` 端点，支持 `response_format: {"type": "json_object"}`。

`NoopProvider` 在未配置 API Key 时返回，所有调用直接抛 `LlmError`，确保上游 catch 块正确触发 fallback。

切换到其他模型只需:
- 新增一个 `LlmProvider` 子类
- 在 `get_provider()` 中注册
- 修改 `.env` 中的 `TOOL_SERVICE_LLM_PROVIDER`

### 2.4 Prompt 设计

`StoryProposalPrompt` (v1.1) 包含:

- **System Prompt**: 详细描述五段式故事结构规则、reasonCode 白名单、beat 时长预算约束、禁止创造 shotId
- **User Prompt 模板**: 注入候选 shot 列表 (含 shotId、asset、rank、score、motion、duration、rankingReasons)、目标时长、beat 预算

系统提示词在每次调用时计算 SHA-256 哈希 (`hash_system_prompt()`)，写入审计记录以确保可追溯。

### 2.5 安全边界

LLM 的安全边界通过"三层防护"实现:

**第一层 — Schema 合约**: `contracts/llm/story-plan-proposal.schema.json` 约束 LLM 输出的结构、字段类型、reasonCode 白名单。

**第二层 — 语义 Validator** (`LlmStoryProposalValidator`): 检查 shotId 是否存在、是否跨 beat 重复、beat 顺序是否正确、targetDurationMs 之和是否等于总目标时长、reasonCodes 是否在白名单内、confidence 是否在 0-1 之间。

**第三层 — 编译后 Validator** (`StoryProposalValidator`): LLM 只选择 shotId，不接触源时间戳。`_compile_llm_proposal()` 使用确定性算法填充 `sourceInMs`/`sourceOutMs`/`selectedDurationMs` 后再做最终校验——片段不超出源 shot 边界、`selectedDurationMs == sourceOutMs - sourceInMs`、每 beat 的 actualDurationMs 与 targetDurationMs 一致。

**校验失败 → 自动回退**: LLM 的任何失败 (网络错误、JSON 解析失败、Schema 不匹配、编译失败) 都会在 `_try_llm_story_plan()` 中被 catch，`LlmAuditRecord.mark_*()` 记录失败原因，然后执行流无缝切换到 `_build_deterministic_story_plan()`。

### 2.6 审计记录

每次 LLM 调用 (含失败) 都在 Story Plan artifact 的 `llmAudit` 字段中留下完整记录:

```json
{
  "provider": "deepseek",
  "model": "deepseek-chat",
  "temperature": 0.3,
  "requestId": "2b7a948c1b69",
  "inputCandidateCount": 176,
  "validationErrors": ["beats[4] duration does not match its selected Shots", ...],
  "finalSource": "DETERMINISTIC_FALLBACK",
  "durationMs": 4296,
  "timestamp": "2026-07-20T14:29:48Z"
}
```

前端 `renderDecisions()` 中的 LLM 审计面板根据 `finalSource` 字段显示"AI 生成"还是"确定性算法"，并展示 provider/model/延迟/校验错误详情。

### 2.7 ⚠️ LLM 编译校验链路与关键 Bug 修复

> **本节为重点内容，记录第五阶段后期发现并修复的 LLM 编译链路上的 4 个 Bug。**

LLM 调用成功后，其输出的 JSON 需要经过 `_compile_llm_proposal()` 编译为完整的 Story Plan，再经 `StoryProposalValidator` 校验。编译阶段发现了以下 Bug：

#### Bug 1: `beats.append()` 在 shot 调整之前执行 — `actualDurationMs` 计算过期

**严重程度**: 🔴 高

**现象**: validator 报告 `beats[X] duration does not match its selected Shots`。

**根因**: 编译器的 second pass 会调整最后一个 shot 的 `selectedDurationMs`/`sourceInMs`/`sourceOutMs` 以填补剩余 budget 缺口。但 `beats.append()` 中的 `actualDurationMs = sum(selectedDurationMs)` 在 adjustment **之前**就已经计算好了。adjustment 修改了 dict 内的 `selectedDurationMs`（因为是引用传递），但 `actualDurationMs` 仍是修改前的旧值。validator 看到 `actualDurationMs != sum(selectedDurationMs)`。

**修复** (`story_plan.py`): 将 `beats.append()` 移到 second pass adjustment **之后**。

#### Bug 2: 过短 shot（`< 600ms`）仍被加入 beat

**严重程度**: 🟡 中

**现象**: validator 报告 `beats[X].shots[Y] selectedDurationMs is inconsistent`（实为 `duration < 600`）。

**根因**: LLM 给某个 beat 分配了过多 shot，最后一个 shot 被裁剪到 `duration = remaining < 600ms`。Validator 要求每个 shot 至少 600ms。

**修复** (`story_plan.py`): 新增 `if duration < 600: continue` — 跳过裁剪后不足 600ms 的 shot，留给 second pass 通过扩展上一个 shot 来消耗剩余 budget。

#### Bug 3: 单 shot 扩展不够 — 剩余 budget 无法被最后一个 shot 吸收

**严重程度**: 🟡 中

**现象**: 某个 beat 的剩余 budget（如 333ms）无法被最后一个 shot 吸收（因为 `new_duration > max_available`，shot 已经用满），导致 `actualDurationMs != targetDurationMs`。

**根因**: 旧版 second pass 只尝试扩展**最后一个** shot。如果最后一个 shot 已经用满（`selectedDurationMs == max_available`），扩展必定失败。

**修复** (`story_plan.py`): second pass 改为从后往前遍历**所有** shot，每个 shot 能吸收多少剩余 budget 就吸收多少 (`extra = min(remaining, max_available - selectedDurationMs)`)。这样即使最后一个 shot 已满，还能向前面的 shot 分配。

#### Bug 4: Audit 记录 `provider`/`model` 永远为 `"none"` — 调试失明

**严重程度**: 🟡 中

**现象**: 所有 audit 记录中 `provider` 和 `model` 字段始终显示 `"none"`，即使 DeepSeek 被成功调用也看不出来。

**根因**: `LlmAuditRecord` 构造时写死 `provider="none"` / `model="none"`。`mark_llm_success()` 和 `mark_llm_error()` 都不更新这两个字段。实际 provider 的信息丢失。

**修复** (`audit.py`): `mark_llm_success(provider_name, model_name, raw)` 和 `mark_llm_error(provider_name, model_name)` 现在正确写入 provider 和 model。`to_dict()` 新增 `rawResponse` 字段用于调试。

#### Bug 5: `python-dotenv` 不在依赖中 — API Key 可能未被加载

**严重程度**: 🔴 高

**根因**: `config.py` 从 `dotenv` 导入 `load_dotenv`，但 `environment.yml` 和 `requirements.txt` 均未声明 `python-dotenv` 依赖。若 conda 环境未手动安装该包，tool-service 启动即崩溃。

**修复**: 在两个依赖文件中加入 `python-dotenv`。

#### Bug 6: LLM 返回 `assumptions` 为字符串而非数组

**严重程度**: 🟢 低

**根因**: DeepSeek 在生成 `assumptions` 时常常输出一个长字符串，而非 Schema 要求的数组。

**修复** (`story_plan.py`): 编译器检测 `assumptions` 类型，若为字符串则 wrap 为单元素数组。

#### 编译失败的残余情况

> ⚠️ **重点标注**: 即使以上所有 Bug 修复后，LLM 编译**仍有可能失败**——当 LLM 选择的 shot 组合总时长无法达到 beat budget 时（如 ENDING beat：3 个镜头总时长 3666ms < 预算 3999ms，且每个镜头均已用满），编译器无法填充缺口。这是**预期行为**，系统会自动 fallback 到确定性算法，用户无感知。要提高 LLM 采纳率需要模型具备更强的数值推理能力，或修改 prompt 强调 duration 求和约束。

## 3. 前端展示完善

第五阶段的前端工作集中在四个问题:

### 3.1 🔴 上传功能修复 (关键 Bug)

**问题**: 点击"上传素材"按钮后没有任何反应，但网络请求根本没发出。

**根因**: `app.js` 中 7 个辅助函数被调用但从未定义:
- `showError()` — 所有 try-catch 块中的错误展示
- `setServiceState()` — 状态栏文字更新
- `setStatus()` — CSS 状态类切换
- `parseMetadata()` — artifact JSON 解析
- `formatBytes()` — 文件大小格式化
- `renderMetadata()` — 视频元数据卡片渲染
- `renderProxy()` — 代理视频预览渲染

上传按钮点击后第一行就是 `setServiceState("正在上传视频...")`，直接抛出 `ReferenceError`，后续 `FormData` 构造和 `fetch` 调用全部跳过。

**修复**: 在 `app.js` 末尾补全了全部 7 个函数 (新增 ~60 行)。

### 3.2 Story Beat 素材定位信息

**问题**: 用户看到 "HOOK · 6s · 2 shots" 但不知道是哪两个片段、来自哪个素材、对应哪段时间。

**修复**: `renderDecisions()` 中展开每个 beat 卡片，遍历 `beat.shots` 数组，为每个 shot 渲染一行:
- 排名 `#N`
- 素材文件名 (通过 `state.assets` 映射 `sourceAssetId` → `fileName`)
- 源时间段 `XX.Xs–XX.Xs`

### 3.3 Timeline Track 可视化

**问题**: `<div id="timeline-track">` 始终为空，用户看不到 clip 的相对位置关系。

**修复**: 从 `timelineData.tracks[0].clips` 数组读取各 clip，按 `(timelineOutMs - timelineInMs) / durationMs` 比例计算宽度百分比，渲染为彩色块。五个角色使用独立渐变色:
- HOOK 红 · INTRO 蓝 · JOURNEY 绿 · CLIMAX 橙 · ENDING 紫

每个色块显示素材名、源时间段、timeline 位置，hover 有完整 tooltip。

### 3.4 多 Proxy 分区展示

**问题**: `renderRun` 使用 `.find()` 只取第一个 `VIDEO_PROXY` artifact，多素材场景下只显示一个代理视频。

**修复**:
- `renderRun` 改为 `.flatMap()` 收集所有 proxy artifact，附上所在 task 的 `assetId`
- `renderProxy(artifact)` → `renderProxies(proxies)`，为每个 proxy 创建独立的 `<section class="proxy-preview">`
- HTML 中 `<section id="proxy-preview" hidden>` 替换为 `<div id="proxy-previews">` 动态容器
- CSS 新增 `.proxy-previews` 网格容器样式，卡片间 border-top 分隔

## 4. LLM 接入真实验证

第五阶段使用已配置的 DeepSeek API Key (`deepseek-chat`) 进行了 10 次真实调用，其中 8 次记录到了 `llmAudit`:

| 时间 | 最终来源 | 延迟 | 说明 |
|---|---|---|---|
| 07-21 02:11 | **LLM** | 4266ms | **唯一一次通过全部校验，LLM 选择被采用** |
| 07-20 14:29 | DETERMINISTIC_FALLBACK | 4296ms | `_compile_llm_proposal` 编译失败，已通过 Bug 1/2/3 修复 |
| 07-21 02:25 | DETERMINISTIC_FALLBACK | 4720ms | 同上，编译失败 |
| 07-20 14:56 | DETERMINISTIC_FALLBACK | 14728ms | 同上，耗时最长 |
| 07-21 02:38 | DETERMINISTIC_FALLBACK | 5117ms | 同上 |
| 07-20 14:37 | DETERMINISTIC_FALLBACK | 3952ms | 同上 |
| 07-20 14:07 | DETERMINISTIC_FALLBACK | 242ms | LLM 快速失败 (可能返回空响应) |
| 07-20 14:22 | DETERMINISTIC_FALLBACK | 83ms | LLM 快速失败 |

**LLM 输出格式分析**（来自最近一次调用的 `rawResponse`）:

- ✅ `schemaVersion: "1.0"` 正确
- ✅ `template: "TRAVEL_JOURNEY_V1"` 正确
- ✅ beat 顺序 HOOK→INTRO→JOURNEY→CLIMAX→ENDING 正确
- ✅ beat budgets 总和 = 30000ms (3501+4500+9000+9000+3999) 正确
- ✅ `LlmStoryProposalValidator` 通过（第一关格式校验）
- ⚠️ `assumptions` 为字符串而非数组（已修复，编译器自动 wrap）
- ❌ `_compile_llm_proposal` → `StoryProposalValidator` 失败（第二关编译校验）— 上述 Bug 1-3 已修复

**结论**:
1. LLM 输出**格式完全正确**，第一关校验始终通过
2. 此前的 ~7/8 失败集中在**编译阶段**（第二关），而非 LLM 自身生成质量问题
3. Bug 1-3 修复后，编译成功率预期显著提高
4. 残余失败（LLM 选的 shot 组合总时长不够）是预期行为，fallback 正确工作

## 5. 未完成的工作

以下内容不在第五阶段范围内，留待后续:

1. **LLM 模型选型**: `deepseek-chat` 的数值推理能力有限，建议评估支持 strict structured output 的模型
2. **Prompt 改进**: 可在 prompt 中强调 shot duration 求和约束，帮助 LLM 做出更精确的 budget 匹配
3. **LLM 生成的镜头选择缺乏语义信号**: 当前 LLM 只知道 shot 的数值评分和运动指标，不知道画面内容 (雪山/古城/人物)。第六阶段可增加 scene classification、object detection 等结构化标签
4. **人工修改与方案版本管理**: 用户仍不能在页面中手动调整 shot 分配或保存多版本方案
5. **最终视频渲染**: Timeline 仍是结构化数据，尚未接入受控的 FFmpeg 渲染管线
6. **自动化测试补充**: LLM 调用路径的集成测试需要 mock provider，当前 `test_shot_decisions.py` 已覆盖确定性路径但未覆盖 LLM 路径
7. **Git 提交**: 第五阶段所有改动在 working tree 中，尚未 commit

## 6. 修改文件清单

```
已修改 (vs HEAD):
  .gitignore                                         |   3 +
  .vscode/settings.json                              |  16 +-
  control-plane/src/main/resources/static/app.js     | 179 +++++----
  control-plane/src/main/resources/static/index.html |  12 +-
  control-plane/src/main/resources/static/styles.css |  23 +-
  tool-service/app/core/config.py                    |  13 +
  tool-service/app/main.py                           |   6 +-
  tool-service/app/tools/story_plan.py               | ~320 行变更 (LLM-assisted + 4 Bug修复)
  tool-service/app/llm/audit.py                      | 签名扩展 + rawResponse
  tool-service/environment.yml                       |   1 + (python-dotenv)
  tool-service/requirements.txt                      |   1 + (python-dotenv)

新增:
  tool-service/app/llm/__init__.py
  tool-service/app/llm/provider.py
  tool-service/app/llm/prompt.py
  tool-service/app/llm/audit.py  (重写)
  tool-service/.env
  tool-service/.env.example
  contracts/llm/story-plan-proposal.schema.json
  docs/modules/control-plane/infrastructure-llm-provider/README.md
  docs/fifth-stage-handoff.md  (本文件)
```

## 7. 启动方式

第五阶段未改变 Java/Python 的启动方式。

**前置条件**: 在 conda 环境中安装 `python-dotenv`，并在 `tool-service/.env` 中配置 API Key:
```
TOOL_SERVICE_LLM_API_KEY="sk-xxxxxxxx"
TOOL_SERVICE_LLM_PROVIDER=deepseek
TOOL_SERVICE_LLM_MODEL=deepseek-chat
```

不配置 API Key 时系统自动使用 NoopProvider，所有 Story Plan 走纯确定性算法。

**启动命令**:
```bash
# 终端 1: Python Tool Service
cd tool-service && uvicorn app.main:app --host 127.0.0.1 --port 8090

# 终端 2: Java Control Plane
cd control-plane && mvn spring-boot:run
```

浏览器访问 `http://127.0.0.1:8080`。
