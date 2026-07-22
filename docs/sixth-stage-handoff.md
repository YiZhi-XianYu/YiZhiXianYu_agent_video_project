# 第六阶段交接：CLIP 语义理解增强

> 文档日期：2026-07-21
> 当前阶段：第六阶段已完成，3 个 CLIP 视觉语义 Tool 已接入 DAG，语义标签注入 LLM Prompt
> 阶段边界：CLIP 仅用于零样本图像分类，不生成 Shell/FFmpeg/URI/任意参数

## 1. 阶段目标与解决的痛点

第五阶段接入的 LLM 在做 Story Plan 的 shot-to-beat 选择时，只能看到数值评分（qualityScore、motionInterest、durationFitness）和 rankingReasons，无法理解画面内容。LLM 不知道一个 shot 是雪山还是古城、有没有人物、画面中是建筑还是食物。

第六阶段的核心目标：

- 在不增加外部 API 依赖的前提下，使用开源 CLIP 模型为每个 Shot 的关键帧打上结构化语义标签；
- 将语义标签注入 LLM Story Proposal Prompt，使 LLM 能根据画面内容做出更智能的叙事选择；
- 保持确定性 fallback 路径不受影响；
- 新增 3 个 Tool，全部注册到 Python/Java 两侧，DAG 节点数从 8 增至 11。

本阶段已经解决：

1. CLIP ViT-B-32 模型的本机离线加载与三 Tool 复用；
2. 15 类场景分类（雪山、古城、城市、乡村、水边、森林、室内、集市、寺庙、公路、天空为主、夜景、人物近景、徒步小径、开阔地）；
3. 15 类物体检测（建筑、车辆、食物、招牌、动物、植物、路标、桥梁、雕塑、船只、帐篷、摊位、旗帜、路灯、台阶）；
4. 11 类人物检测（有人/无人 + 人数 + 景别 + 活动姿态）；
5. 中英文标签分离：CLIP 推理使用英文标签（CLIP 英文嵌入空间要求），前端展示使用中文标签；
6. 语义标签经 `_build_semantic_map()` 聚合后注入 LLM user prompt 的每个候选 shot 行；
7. WorkflowDefinition 从 v3 升级至 v4（11 节点）；
8. 项目清理至可上传 GitLab 的状态（缓存清理、API Key 脱敏、硬编码路径修复、.gitignore 补齐）。

## 2. 新增视觉语义 Tool 架构

```
┌──────────────────────────────────────────────────────────┐
│                    Java Control Plane                     │
│  WorkflowDefinition v4: 11 nodes                         │
│  ASSET 分支新增 3 个 Tool 并行节点                          │
└──────────────────────────────────────────────────────────┘
                            │
                            ▼
┌──────────────────────────────────────────────────────────┐
│                  Python Tool Service                      │
│                                                          │
│  video.shot-detect 产出 SHOT_LIST + KEYFRAME_IMAGE        │
│    │                                                     │
│    ├─ vision.scene-classify  (SCENE_TAGS)                │
│    ├─ vision.object-detect   (OBJECT_TAGS)               │
│    └─ vision.person-detect   (PERSON_TAGS)               │
│              │                                           │
│              ▼                                           │
│  planning.story-template 消费 SCENE/OBJECT/PERSON_TAGS    │
│    └─ _build_semantic_map() 聚合所有标签                   │
│    └─ StoryProposalPrompt.build_user_prompt() 注入 prompt │
│                                                          │
│  共享 CLIP 模型 (vision_models.py, lazy-load)              │
└──────────────────────────────────────────────────────────┘
```

三个视觉 Tool 共享同一个 CLIP ViT-B-32 模型实例，由 `vision_models.py` 懒加载并缓存在内存中。每个 Tool 只需定义自己的标签列表和中文映射，推理调用 `classify_batch()` 一次前向传播处理所有 Shot。

### 2.1 新增文件

| 文件 | 职责 |
|---|---|
| `tool-service/app/core/vision_models.py` | 共享 CLIP 模型加载器（懒加载、批量分类） |
| `tool-service/app/tools/vision_scene_classify.py` | 场景分类 Tool（15 类） |
| `tool-service/app/tools/vision_object_detect.py` | 物体检测 Tool（15 类） |
| `tool-service/app/tools/vision_person_detect.py` | 人物检测 Tool（11 类 + 汇总逻辑） |
| `tool-service/tests/test_vision_scene_classify.py` | 场景分类单元测试（4 tests） |
| `tool-service/tests/test_vision_object_detect.py` | 物体检测单元测试（3 tests） |
| `tool-service/tests/test_vision_person_detect.py` | 人物检测单元测试（4 tests） |

### 2.2 修改文件

| 文件 | 变更 |
|---|---|
| `tool-service/app/tools/__init__.py` | 无实质变更（仅为占位文件） |
| `tool-service/app/tools/story_plan.py` | 新增 `_build_semantic_map()` 函数，收集 SCENE/OBJECT/PERSON_TAGS 并按 shotId 聚合；`inputTypes` 从 `["SHOT_RANKING"]` 扩展为 `["SHOT_RANKING", "SCENE_TAGS", "OBJECT_TAGS", "PERSON_TAGS"]` |
| `tool-service/app/llm/prompt.py` | `build_user_prompt()` 新增 `semantic_by_shot` 参数；每个候选 shot 行注入 `scene=[...] objects=[...] person=[...]` 字段；System Prompt 新增语义标签使用指引（SNOW_MOUNTAIN→CLIMAX, HAS_PERSON→HOOK 等） |
| `tool-service/app/registry/registry.py` | 注册 3 个新 Tool |
| `tool-service/environment.yml` | 新增 `torch>=2.0.0`, `transformers>=4.30.0`, `pillow>=10.0.0` |
| `tool-service/requirements.txt` | 同上 |
| `tool-service/.env` | 新增 `HF_HUB_DISABLE_SSL_VERIFY` 和 `HF_ENDPOINT` 配置项 |
| `control-plane/.../MultiAssetAnalysisTemplate.java` | DAG 从 8 节点升级至 11 节点，definitionVersion 3→4，新增 3 个 vision 节点及 6 条边 |
| `control-plane/.../WorkflowDefinitionValidator.java` | KNOWN_TOOLS 注册 3 个新 Tool |
| `control-plane/.../WorkflowExecutionService.java` | `acceptedInputTypes()` 和 `inputKey()` 新增 SCENE_TAGS/OBJECT_TAGS/PERSON_TAGS |
| `control-plane/.../WorkflowDefinitionValidatorTest.java` | 版本断言 3→4，节点数断言 8→11 |
| `control-plane/.../static/app.js` | `renderShots()` 新增语义标签展示（sceneTags + objectTags + personTags，显示中文标签 + 置信度） |
| `.gitignore` | 新增 `.DS_Store`, `Thumbs.db`, `*.log`, `uploads/`, `node_modules/`, `dist/`，移除重复 `.env` 条目 |
| `scripts/*.cmd` | 移除硬编码 Python/Maven 绝对路径，改用 PATH 解析 |

## 3. CLIP 模型与本机部署

### 3.1 模型选择

选用 `openai/clip-vit-base-patch32`（ViT-B-32，约 600MB），原因：

- 开源、免费、无 API 调用限制；
- 零样本分类能力满足场景/物体/人物粗粒度标签需求；
- CPU 可推理（本机无 GPU），每个关键帧约 1-2 秒；
- 共享模型实例，三个 Tool 只加载一次。

### 3.2 离线加载策略

CLIP 模型首次运行时从 HuggingFace 下载。由于中国网络环境限制，直接访问 `huggingface.co` 不可用。解决方案：

1. 通过 ModelScope 镜像 `openai-mirror/clip-vit-base-patch32` 下载模型文件（13 个文件，含 `model.safetensors`）；
2. 复制到 HuggingFace 标准缓存路径 `~/.cache/huggingface/hub/models--openai--clip-vit-base-patch32/snapshots/<hash>/`；
3. 修正 `refs/main` 指向对应 snapshot hash；
4. `vision_models.py` 使用 `local_files_only=True` 参数，禁止联网，确保只从本地缓存加载。

```python
_CLIP_MODEL = CLIPModel.from_pretrained(_MODEL_NAME, local_files_only=True)
_CLIP_PROCESSOR = CLIPProcessor.from_pretrained(_MODEL_NAME, local_files_only=True)
```

### 3.3 中英文标签分离（关键设计决策）

CLIP 在英文图文对上进行对比预训练，其嵌入空间为英文语义空间。早期版本使用中文标签（如"一张有人的照片"），CLIP 无法正确理解中文文本，导致所有 Shot 被误判为"无人"。

**最终方案**：推理使用英文标签，展示使用中文映射。

```python
# 推理标签（英文 — 匹配 CLIP 嵌入空间）
PERSON_LABELS = [
    "a photo with a person in it",
    "a photo of a single person",
    ...
]

# 展示映射（中文 — 前端显示）
PERSON_LABEL_ZH = {
    "a photo with a person in it": "有人",
    "a photo of a single person": "一个人",
    ...
}
```

Tool 输出同时包含 `label`（英文枚举）和 `labelZh`（中文展示名），前端优先使用 `labelZh`。

## 4. WorkflowDefinition v4 与 DAG 变更

### 4.1 版本升级

```text
definitionKey: MULTI_ASSET_ANALYSIS
definitionVersion: 4
nodes: 11  (v3: 8 节点)
```

### 4.2 新增 ASSET 分支节点

三个视觉 Tool 运行在 ASSET 作用域，与 `vision.quality-score` 并行，均依赖 `video.shot-detect` 产出的 SHOT_LIST：

```text
video.shot-detect
  ├─ vision.quality-score   (已有)
  ├─ vision.scene-classify  (新增)
  ├─ vision.object-detect   (新增)
  └─ vision.person-detect   (新增)
```

### 4.3 新增汇聚边

三个新 Tool 的输出与 `decision.shot-rank` 一同汇聚到 `planning.story-template`：

```text
vision.scene-classify  ──┐
vision.object-detect   ──┤
vision.person-detect   ──┤
decision.shot-rank     ──┼── planning.story-template
```

### 4.4 完整节点列表

| 节点 | Tool | 作用域 | 输入 |
|---|---|---|---|
| `video_probe` | `video.probe@1.0.0` | ASSET | PROJECT_ASSET |
| `video_proxy_generate` | `video.proxy-generate@1.0.0` | ASSET | VIDEO_METADATA/Asset 上下文 |
| `video_shot_detect` | `video.shot-detect@1.0.0` | ASSET | VIDEO_PROXY |
| `vision_quality_score` | `vision.quality-score@1.0.0` | ASSET | VIDEO_PROXY + SHOT_LIST |
| `vision_scene_classify` | `vision.scene-classify@1.0.0` | ASSET | SHOT_LIST |
| `vision_object_detect` | `vision.object-detect@1.0.0` | ASSET | SHOT_LIST |
| `vision_person_detect` | `vision.person-detect@1.0.0` | ASSET | SHOT_LIST |
| `shot_ranking` | `decision.shot-rank@1.0.0` | WORKFLOW | 全部 SHOT_QUALITY |
| `story_plan` | `planning.story-template@1.0.0` | WORKFLOW | SHOT_RANKING + 全部 SCENE_TAGS + OBJECT_TAGS + PERSON_TAGS |
| `highlight_selection` | `decision.highlight-select@1.0.0` | WORKFLOW | STORY_PLAN |
| `timeline_compose` | `timeline.compose@1.0.0` | WORKFLOW | HIGHLIGHT_SET |

双素材展开为：`2 * 7 个 ASSET Task + 4 个 WORKFLOW Task = 18 个 Task`（此前为 12 个）。

## 5. 语义标签注入 LLM Prompt

### 5.1 标签聚合

`_build_semantic_map()` 读取 SCENE_TAGS、OBJECT_TAGS、PERSON_TAGS 三个 Artifact，按 shotId 聚合为：

```python
sem[shotId] = {
    "scene":   ["SNOW_MOUNTAIN(0.85)", "WATERSIDE(0.12)", ...],
    "object":  ["BUILDING(0.72)", "VEHICLE(0.15)", ...],
    "person":  ["HAS_PERSON(0.88)", "SINGLE_PERSON(0.65)", "STANDING(0.42)", ...],
}
```

### 5.2 Prompt 注入

`StoryProposalPrompt.build_user_prompt()` 在每个候选 shot 行追加语义字段：

```text
  shot_042 | asset_a3b0a637 | rank=3 | finalScore=0.782 ...
  scene=[SNOW_MOUNTAIN(0.85), WATERSIDE(0.12)] objects=[BUILDING(0.72)] person=[HAS_PERSON(0.88), SINGLE_PERSON(0.65)] | reasons: HIGH_CLARITY, INTERESTING_MOTION
```

### 5.3 System Prompt 语义引导

System Prompt 新增每个 Beat 的语义偏好：

```text
HOOK: preferably with HAS_PERSON or CLOSE_UP
INTRO: OLD_TOWN/MODERN_CITY/COUNTRYSIDE preferred
JOURNEY: WATERSIDE/FOREST/HIKING_TRAIL preferred
CLIMAX: SNOW_MOUNTAIN/TEMPLE/PERSON_CLOSEUP preferred
ENDING: SKY_DOMINANT/OPEN_FIELD/NIGHT_SCENE preferred
```

### 5.4 确定性 Fallback 不受影响

`_build_deterministic_story_plan()` 不消费语义标签，仅使用 qualityScore、motionInterest 和时间位置。语义标签仅在 LLM 路径中生效，fallback 安全边界不变。

## 6. 信任度阈值与标签质量控制

### 6.1 场景/物体检测阈值

标签输出前过滤：`confidence > 0.10`（早期版本为 0.05，过低导致大量噪声标签）。每 Shot 最多输出 3 个标签（top-3）。

### 6.2 人物检测阈值

`_summarize_person()` 使用两级"无人"判断：

```python
# 第一级：no_person 置信度 > 0.7 → 直接判定"无人"
if no_person_conf > 0.7:
    tags = [{"label": "NO_PERSON", ...}]

# 第二级：has_person 置信度 < 0.3 → 也判定"无人"
elif has_person_conf < 0.3:
    tags = [{"label": "NO_PERSON", ...}]

# 否则输出人物详情（人数 > 0.3, 景别 > 0.4, 活动 > 0.3）
```

### 6.3 "天空为主"防误标

早期标签 `"a photo dominated by the sky"` 过于宽泛，导致大量非天空镜头被误标。已收紧为：

```python
"a photo where the sky fills most of the frame with little else visible"
```

配合 0.10 阈值，显著减少误标。

## 7. 已知限制

### 7.1 CLIP 零样本分类精度天花板

CLIP ViT-B-32 在 ImageNet 上的 zero-shot top-1 准确率约 63%，在细粒度人物检测/场景分类上精度有限。以下问题属于模型能力瓶颈，非代码 Bug：

- **人物检测召回率**：部分明显有人物的镜头仍可能被判为"无人"。CLIP 对远景小人、侧脸、遮挡人物的检测能力弱于专用目标检测模型。
- **"天空为主"仍有误标**：threshold 调至 0.10 后改善明显但未根治。CLIP 对天空占比的判断与人类主观感受有差距。

### 7.2 大模型依赖

`torch`（~2GB）+ `transformers`（~500MB）+ CLIP 模型权重（~600MB）使 tool-service 依赖体积显著增大。纯 CPU 推理 257 个 Shot 关键帧，每个 Tool 约需 2-4 分钟（3 个 Tool 串行约 10 分钟）。

### 7.3 LLM 采纳率未改善

语义标签注入改善了 LLM 选择的信息基础，但 LLM 的 duration 求和约束仍然难以满足（DeepSeek V3 数值推理能力有限）。LLM 采纳率仍然较低（~1/8），大部分运行仍走确定性 fallback。这是第五阶段已知问题的延续，属于 P3 优化项。

## 8. 未完成的工作

以下内容不在第六阶段范围内，留待后续：

1. **人物检测精度提升**：CLIP 零样本分类的人物检测召回率不理想，可考虑接入专用目标检测模型（如 YOLO）或换用更大的 CLIP 变体（ViT-L-14）；
2. **最终视频渲染（P1）**：`timeline.compose` 仍只输出声明式 JSON TIMELINE，未接入受控 FFmpeg Renderer 生成最终 MP4 成片；
3. **人工编辑与方案版本管理（P2）**：用户仍不能在页面中手动调整 shot 分配或保存多版本 Story Plan；
4. **LLM 模型与 Prompt 优化（P3）**：评估支持 strict structured output 的模型，改善 Prompt 中 duration 求和约束，提高 LLM 采纳率；
5. **音频/字幕/转场（P4）**：BGM、ASR 转写、字幕计划、FADE/CROSS_DISSOLVE 转场；
6. **基础设施（P5）**：Flyway 替代 ddl-auto=update、Celery/Redis 替代内存队列、WebSocket、Docker Compose、Git 提交；
7. **图像预处理**：当前直接使用 Shot 边界关键帧，未做人脸/主体居中的智能帧选择；
8. **视觉工具并行化**：三个 CLIP Tool 当前各走一次前向传播，可合并为一次推理以节省 2/3 推理时间。

## 9. 第七阶段建议

按优先级排列：

1. **P1 — 最终视频渲染**：这是从"分析系统"到"视频生产系统"的关键一跃。实现受控 FFmpeg Renderer，将已验证的声明式 Timeline 编译为最终 MP4 成片。Renderer 只接受 `TimelineValidator` 通过的 TIMELINE Artifact，不自行生成 Shell 命令；
2. **P2 — 人工编辑与方案版本管理**：用户在前端手动调整 shot 分配（替换、排序、锁定片段），保存多版本 Story Plan，支持版本对比与回退；
3. **P3 — LLM 优化**：评估 `gpt-4o` 或 `claude-sonnet-4-6` 的 structured output 能力，改善 duration 求和约束的 LLM 采纳率；
4. **P4 — 音频/字幕/转场**：BGM 选择、ASR 转写生成字幕、FADE/CROSS_DISSOLVE 转场支持；
5. **P5 — 基础设施完善**：Flyway 数据库迁移、Celery/Redis 分布式任务队列、Docker Compose 一键部署。

后续阶段仍必须遵守安全边界：

```text
LLM 不生成 Shell
LLM 不生成 FFmpeg
LLM 不选择未注册 Tool
LLM 不提供本地路径或 Artifact URI
LLM 不绕过 Ranking/Story/Timeline Validator
LLM 输出失败时使用确定性 fallback
Renderer 只编译已验证的声明式 Timeline IR
Renderer 不接受任意 Shell/FFmpeg 参数
```

## 10. Python Tool Registry（11 个 Tool）

```text
video.probe@1.0.0
video.proxy-generate@1.0.0
video.shot-detect@1.0.0
vision.quality-score@1.0.0
vision.scene-classify@1.0.0    ← 新增
vision.object-detect@1.0.0     ← 新增
vision.person-detect@1.0.0     ← 新增
decision.shot-rank@1.0.0
planning.story-template@1.0.0
decision.highlight-select@1.0.0
timeline.compose@1.0.0
```

## 11. 自动化测试

```text
Java:   14 passed
Python: 30 passed (19 已有 + 11 新增)
```

Java 覆盖：

- ProxyQuality、Task 状态机、Tool HTTP 契约序列化；
- WorkflowDefinition v4 校验（11 节点、3 个新 Tool、DAG 无环）。

Python 覆盖：

- Probe、代理、Shot Detection、质量评分、视觉指纹；
- 跨素材 Ranking、Story 素材均衡、Highlight 编译、Timeline 校验；
- LLM Proposal 校验；
- 重试分类与幂等；
- 场景分类标签映射与阈值（4 tests）；
- 物体检测标签映射与阈值（3 tests）；
- 人物检测汇总逻辑、无人判定、人数/景别/活动阈值（4 tests）。

## 12. 项目清理

第六阶段完成时对项目做了全面清理，确保可上传至 GitLab：

- 删除 `tool-service/runtime/artifacts/`（322MB 历史 Artifact 数据）；
- 删除 `control-plane/runtime/storage/`（132MB 上传素材文件）；
- 删除 `control-plane/target/`（Java 构建产物）；
- 删除全部 `__pycache__/` 目录；
- 清空 `tool-service/.env` 中的真实 API Key（已替换为空值）；
- 修复 `scripts/*.cmd` 中硬编码的 `C:\software\Anaconda\...` 和 `C:\software\IDEA\...` 绝对路径；
- `.gitignore` 补齐 `.DS_Store`、`Thumbs.db`、`*.log`、`uploads/`、`node_modules/`、`dist/`；
- WorkflowDefinition 版本号 3→4，测试同步更新。

## 13. 修改文件清单

```
已修改 (vs 第五阶段):
  .gitignore                                         |   8 + (新增条目，去重)
  scripts/start-tool-service.cmd                     |   6 +- (移除硬编码路径)
  scripts/start-control-plane.cmd                    |   2 +- (移除硬编码路径)
  scripts/run-python-tests.cmd                       |   2 +- (移除硬编码路径)
  scripts/run-java-tests.cmd                         |   2 +- (移除硬编码路径)
  tool-service/.env                                  |   3 +- (API Key 脱敏 + 新增 HF 配置)
  tool-service/environment.yml                       |   3 + (torch, transformers, pillow)
  tool-service/requirements.txt                      |   3 + (同上)
  tool-service/app/registry/registry.py              |   3 + (注册 3 个新 Tool)
  tool-service/app/tools/story_plan.py               | ~80 行变更 (_build_semantic_map + inputTypes 扩展)
  tool-service/app/llm/prompt.py                     | ~30 行变更 (System Prompt 语义引导 + User Prompt 语义注入)
  control-plane/.../MultiAssetAnalysisTemplate.java  |  13 +- (3 节点 + 6 边 + version 3→4)
  control-plane/.../WorkflowDefinitionValidator.java |   3 + (3 个新 Tool 白名单)
  control-plane/.../WorkflowExecutionService.java    |   8 + (acceptedInputTypes + inputKey)
  control-plane/.../WorkflowDefinitionValidatorTest.java | 2 +- (版本 + 节点数断言)
  control-plane/.../static/app.js                    | ~20 行变更 (renderShots 语义标签展示)

新增:
  tool-service/app/core/vision_models.py
  tool-service/app/tools/vision_scene_classify.py
  tool-service/app/tools/vision_object_detect.py
  tool-service/app/tools/vision_person_detect.py
  tool-service/tests/test_vision_scene_classify.py
  tool-service/tests/test_vision_object_detect.py
  tool-service/tests/test_vision_person_detect.py
  docs/sixth-stage-handoff.md  (本文件)
```

## 14. 本机运行方式

**前置条件**：

1. MySQL 8.0 本机运行；
2. Conda 环境 `agent-video-pipeline`（Python 3.12 + torch + transformers + pillow）；
3. CLIP 模型已下载至 HuggingFace 缓存 `~/.cache/huggingface/hub/models--openai--clip-vit-base-patch32/`；
4. `tool-service/.env` 中配置 LLM API Key（不配置则自动使用 NoopProvider）。

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

## 15. 新窗口提示词

```text
这是 Agent-Driven Intelligent Video Production Pipeline 项目。

开始前必须阅读：
1. docs/sixth-stage-handoff.md
2. docs/fifth-stage-handoff.md
3. docs/fourth-stage-handoff.md
4. docs/third-stage-handoff.md
5. docs/second-vertical-slice.md
6. docs/first-vertical-slice.md
7. docs/Agent-Driven智能视频制作流水线-系统设计文档.md
8. README.md
9. docs/modules/control-plane/README.md

第六阶段已完成 CLIP 语义理解增强：新增 vision.scene-classify、vision.object-detect、vision.person-detect 三个视觉 Tool，共享 CLIP ViT-B-32 模型。WorkflowDefinition 升级至 v4（11 节点），语义标签通过 _build_semantic_map() 聚合后注入 LLM Story Proposal Prompt。

CLIP 推理使用英文标签（CLIP 英文嵌入空间要求），前端展示使用中文 *_LABEL_ZH 映射。模型需预先下载至 HuggingFace 缓存，vision_models.py 使用 local_files_only=True 离线加载。

已知限制：CLIP 零样本人物检测精度有限（非代码 Bug，属模型能力天花板），LLM 采纳率仍低（~1/8，DeepSeek 数值推理能力有限）。P0 已完成，下一优先级为 P1 最终视频渲染（受控 FFmpeg Renderer）。

项目已清理至可上传 GitLab 状态（缓存删除、API Key 脱敏、硬编码路径修复、.gitignore 补齐）。

Java 14 tests passed，Python 30 tests passed。

开始改动前检查 Git 状态并保留用户已有修改。MySQL 密码和 LLM API Key 只允许从仓库外文件读入进程环境，不得输出或写入仓库。完成后更新文档、运行 Java/Python 测试并做真实多素材 E2E。
```
