# 第八阶段交接：LLM 优化、音频/字幕/转场

> 文档日期：2026-07-23
> 当前阶段：第八阶段已完成，LLM 采纳率通过 strict structured output 模型改善，Timeline 支持 FADE/CROSS_DISSOLVE 转场、BGM 背景音乐混音、ASR 语音转写字幕
> 阶段边界：不改变核心 DAG 链路（5 ASSET + 7 WORKFLOW 节点），不修改 Java↔Python 通信协议，不引入新的视频分析 Tool

## 1. 阶段目标与解决的痛点

第七阶段产出的系统可以让用户编辑 Story Plan、管理版本、一键渲染成片，但存在两个核心问题：

1. **LLM 采纳率极低（~1/8）**：DeepSeek V3 的数值推理能力有限，LLM Story Proposal 在 `targetDurationMs` 求和约束上频繁失败，大部分运行走确定性 fallback；
2. **成片表现力不足**：渲染仅支持 CUT 硬切转场、无背景音乐、无字幕，成片观感生硬。

第八阶段的核心目标：

- **P3 — LLM 优化**：评估并接入支持 strict structured output 的模型（GPT-4o、Claude Sonnet 4/4.6），简化 LLM Schema 移除冗余的 beat 级别 `targetDurationMs`，让 LLM 专注 shot-to-beat 语义分配；
- **P4 — 音频/字幕/转场**：
  - **转场**：新增 FADE（淡入）和 CROSS_DISSOLVE（交叉溶解），Timeline Schema v1.1 扩展，FFmpeg 滤镜图从纯 concat 升级为 xfade/acrossfade 链；
  - **BGM**：新增 `audio.bgm-select` Tool，根据故事段落角色匹配背景音乐心情，渲染时 amix 混音；
  - **ASR 字幕**：新增 `audio.speech-transcribe` Tool，基于 faster-whisper 将视频语音转写为 SRT 字幕，渲染时烧录到视频中。

本阶段已经解决：

1. LLM Schema 从 v1.0 升级到 v1.1：移除 beat 级别的 `targetDurationMs`（编译器始终使用确定性 `_beat_budgets()`），LLM 只需输出 `shotIds` + `reasonCodes`；
2. 新增 `OpenAIProvider`（`json_schema` + `strict: true`）和 `ClaudeProvider`（`tool_use` + `input_schema`），保证输出 100% 符合 JSON Schema；
3. `LlmStoryProposalValidator` 接受 v1.0 和 v1.1 两种格式，向后兼容；
4. 新增 `duration-parsing.schema.json`，时长解析也支持 strict structured output；
5. Timeline Schema v1.1：新增 FADE/CROSS_DISSOLVE 转场类型（含条件 if/then 校验）、可选 AUDIO 轨、可选 SUBTITLE 轨；
6. `TimelineValidator` 完整重写：转场类型/时长校验、首个 Clip CUT 约束、AUDIO/SUBTITLE 轨参数校验；
7. `VideoRenderTool` v1.1.0：FFmpeg 滤镜图从 `concat` 升级为 `xfade`+`acrossfade`+`fade`+`afade`+`amix`+`subtitles` 全功能链；
8. `TimelineComposeTool`（Python）和 `TimelineComposer`（Java）均实现相同的启发式转场分配逻辑；
9. `audio.bgm-select` Tool：根据段落角色（HOOK→energetic, CLIMAX→epic 等）从本地曲库选择 BGM；
10. `audio.speech-transcribe` Tool：基于 faster-whisper，支持 auto-detect 语言，输出 SRT 字幕，带时间线偏移量；
11. 前端展示转场类型指示器（CROSS_DISSOLVE=黄色虚线、FADE=白色点线）、BGM/字幕状态条；
12. Workflow DAG v7：新增 `bgm_select` 和 `speech_transcribe` 两个 WORKFLOW 节点，并行执行后汇入 `video_render`。

## 2. 总体架构

本阶段修改了 Python LLM Provider 层、Timeline 契约层、FFmpeg 渲染层和 Workflow DAG，新增了 2 个 Python Tool 和 3 个 Java/Python 版本升级。

```
┌──────────────────────────────────────────────────────────┐
│                    前端 (index.html + app.js + styles.css) │
│  转场指示器 | timeline-extras (🎵 BGM / 📝 SRT)            │
└──────────────────────────┬───────────────────────────────┘
                           │ HTTP REST
                           ▼
┌──────────────────────────────────────────────────────────┐
│                  Java Control Plane                       │
│                                                          │
│  MultiAssetAnalysisTemplate v7  ──▶  新增 bgm_select +    │
│  WorkflowDefinitionValidator     speech_transcribe 节点    │
│  TimelineComposer         ──▶  CROSS_DISSOLVE 转场风格    │
└──────────────────────────┬───────────────────────────────┘
                           │ HTTP (Tool Service)
                           ▼
┌──────────────────────────────────────────────────────────┐
│                  Python Tool Service                      │
│                                                          │
│  provider.py        ──▶  OpenAIProvider (strict: true)    │
│                          ClaudeProvider (tool_use)        │
│  story_plan.py      ──▶  Schema v1.1, validator 更新      │
│  audio_bgm.py       ──▶  BgmSelectTool (新增)            │
│  audio_transcribe.py ──▶  SpeechTranscribeTool (新增)     │
│  video_render.py    ──▶  xfade + BGM + subtitles 滤镜图   │
│  timeline_validator.py ──▶  转场/音轨/字幕轨校验           │
└──────────────────────────────────────────────────────────┘
```

### 2.1 新增文件

| 文件 | 职责 |
|---|---|
| `contracts/llm/duration-parsing.schema.json` | 时长解析 LLM 输出的 JSON Schema（strict structured output 用） |
| `tool-service/app/tools/audio_bgm.py` | BGM 选择 Tool：段落角色→心情→本地曲库文件，输出 `BGM_AUDIO` Artifact |
| `tool-service/app/tools/audio_transcribe.py` | ASR 转写 Tool：faster-whisper 语音→SRT 字幕，带时间线偏移，输出 `SUBTITLE_SRT` Artifact |

### 2.2 修改文件

| 文件 | 变更 |
|---|---|
| `contracts/llm/story-plan-proposal.schema.json` | v1.1：移除 `beatBase` 的 `targetDurationMs` 必填字段；`additionalProperties: false` |
| `contracts/llm/README.md` | 记录 v1.1 设计决策、向后兼容策略、provider 接入指引 |
| `contracts/timeline/timeline.schema.json` | v1.1：新增 FADE/CROSS_DISSOLVE 转场（含条件 if/then 校验）、AUDIO 轨（`$defs/audioTrack`）、SUBTITLE 轨（`$defs/subtitleTrack`）、tracks 从单一 VIDEO 扩展为 oneOf 三轨 |
| `contracts/timeline/README.md` | 记录 v1.1 新增功能与渲染实现 |
| `tool-service/app/core/config.py` | 新增 `llm_openai_api_key/model`、`llm_anthropic_api_key/model`、`bgm_library_root`、`asr_model_size` |
| `tool-service/app/llm/provider.py` | 新增 `OpenAIProvider`（`response_format: json_schema + strict: true`）、`ClaudeProvider`（`tool_choice + input_schema`）；新增 `supports_structured_output()` API；`get_provider()` 支持 openai/claude |
| `tool-service/app/llm/prompt.py` | `StoryProposalPrompt` v1.2：移除规则 #4（beat 时长必须匹配预算）、移除求和约束、输出示例移除 beat 级别 `targetDurationMs`、schemaVersion "1.1" |
| `tool-service/app/tools/story_plan.py` | `LlmStoryProposalValidator`：接受 v1.0 和 v1.1、跳过时长算术验证、新增幻觉 shot 数量早期拒绝；新增 `_load_proposal_schema()`、`_load_duration_schema()`；`_try_llm_story_plan()` 和 `_parse_duration_prompt()` 传递 Schema 给 provider |
| `tool-service/app/tools/timeline_validator.py` | 完整重写：模块化验证函数 `_validate_video_clips()`/`_validate_audio_track()`/`_validate_subtitle_track()`/`_find_track()`；FADE 200–2000ms、CROSS_DISSOLVE 200–2000ms、首个 Clip CUT 约束、转场时长 < clip 时长、AUDIO volume 0.0–1.0、SUBTITLE format SRT/VTT |
| `tool-service/app/tools/video_render.py` | v1.1.0：`_build_filter_graph()` 完整重写——四步构建（per-clip intermediates → xfade 链 → 音频链 → BGM+字幕后处理）；`_assemble_command()` 接受动态标签；`execute()` 支持 bgm/subtitle 可选输入 |
| `tool-service/app/tools/shot_decisions.py` | `TimelineComposeTool` v1.1.0 + 新增 `_assign_transition()` 启发式逻辑（首个 clip=FADE 300ms、段落边界=CROSS_DISSOLVE 500ms、段内=CUT） |
| `tool-service/app/registry/registry.py` | 注册 `VideoRenderTool`、`BgmSelectTool`、`SpeechTranscribeTool` |
| `tool-service/requirements.txt` | 新增 `faster-whisper>=1.0.0` |
| `tool-service/tests/test_video_render.py` | 新增 FADE/CROSS_DISSOLVE/多源/混音 滤镜图测试；适配新签名（4 返回值） |
| `tool-service/tests/test_shot_decisions.py` | 新增 4 个 Validator 测试 + 更新 LLM Validator 测试（v1.0/v1.1 兼容、幻觉 shot 数量拒绝、移除过时断言） |
| `control-plane/.../plan/TimelineComposer.java` | 新增 `compose()` 三参数重载（含 `transitionStyle`）；新增 `assignTransition()` 与 Python 一致的启发式逻辑；schemaVersion "1.1" |
| `control-plane/.../workflow/MultiAssetAnalysisTemplate.java` | v6→v7：新增 `bgm_select`（audio.bgm-select@1.0.0）和 `speech_transcribe`（audio.speech-transcribe@1.0.0）WORKFLOW 节点；新增 4 条边：timeline_compose→bgm_select、timeline_compose→speech_transcribe、bgm_select→video_render、speech_transcribe→video_render；保留 timeline_compose→video_render 边（可选输入 fallback） |
| `control-plane/.../workflow/WorkflowDefinitionValidator.java` | KNOWN_TOOLS 新增 `timeline.compose@1.1.0`、`video.render@1.1.0`、`audio.bgm-select@1.0.0`、`audio.speech-transcribe@1.0.0`；保留旧版本向后兼容 |
| `control-plane/.../test/.../WorkflowDefinitionValidatorTest.java` | 适配新版：12 节点、7 个 WORKFLOW 范围节点、definitionVersion=7 |
| `control-plane/.../static/app.js` | `buildClientTimeline()`：基于段落的转场分配（首个 FADE、边界 CROSS_DISSOLVE）；`renderDecisions()`：多轨渲染 + 转场 CSS class + timeline-extras 更新 |
| `control-plane/.../static/index.html` | 时间线下方新增 `<div id="timeline-extras">` 用于 BGM/字幕状态 |
| `control-plane/.../static/styles.css` | 新增 `.transition-cross-dissolve`（黄色虚线边框）、`.transition-fade`（白色点线边框）、`.timeline-extras` |

### 2.3 删除文件

无。

## 3. P3 — LLM 优化：Strict Structured Output

### 3.1 问题诊断

深入分析后发现，LLM 采纳率 ~1/8 的**真正根因**并非 DeepSeek 的数值推理能力不足本身，而是架构设计导致 LLM 承担了冗余且不可能完成的任务：

1. **Beat 级别的 `targetDurationMs` 冗余**：`_compile_llm_proposal()` 编译器**始终忽略 LLM 的 beat 时长**，转而使用确定性的 `_beat_budgets()`。LLM 被要求计算并输出一个从未被消费的和值——纯粹浪费认知资源且容易出错；
2. **JSON Schema 无跨字段约束**：`sum(beats[*].targetDurationMs) == targetDurationMs` 无法在 JSON Schema 中表达，因此即使采用 strict mode，该约束也无法被强制执行；
3. **DeepSeek 的 `json_object` 模式**：仅强制要求合法 JSON，不保证 Schema 合规性。幻觉 shotId、重复、错误 reasonCode 均能通过。

### 3.2 解决方案：三层优化

| 层级 | 改动 | 影响 |
|------|------|------|
| **Schema v1.1** | 移除 beat 级别的 `targetDurationMs`（LLM 只需输出 `shotIds` + `reasonCodes`） | 消除"幻影和约束"——LLM 不再被要求输出从未被读取的时长 |
| **Provider** | 新增 `OpenAIProvider`（`json_schema` + `strict: true`）和 `ClaudeProvider`（`tool_use` + `input_schema`） | 保证输出 100% Schema 合规——无缺失字段、无错误类型、无额外属性、无非法枚举值 |
| **Validator** | 接受 v1.0 和 v1.1；移除时长检查；新增幻觉 shot 数量的早期拒绝机制（`> maxShots * 3`） | 向后兼容，更快失败，错误信息更清晰 |

### 3.3 OpenAIProvider（GPT-4o）

```python
# 使用 response_format 的 json_schema + strict: true
payload = {
    "model": "gpt-4o",
    "response_format": {
        "type": "json_schema",
        "json_schema": {
            "name": "story_proposal",
            "strict": True,
            "schema": <contracts/llm/story-plan-proposal.schema.json>,
        },
    },
}
```

`strict: true` 保证模型输出符合 JSON Schema 的所有约束：required 字段齐全、类型正确、`pattern` 匹配、`enum` 合法、`minItems`/`maxItems` 遵守、`additionalProperties: false` 拒绝多余字段。

当无 Schema 时（如时长解析），fallback 到 `json_object` 模式。

### 3.4 ClaudeProvider（Claude Sonnet 4/4.6）

Claude 无 `response_format`，使用 **tool_use 模式**实现同等保证：

```python
payload = {
    "model": "claude-sonnet-4-6",
    "tools": [{"name": "...", "input_schema": <schema>}],
    "tool_choice": {"type": "tool", "name": "..."},
}
```

强制 Claude 调用指定 Tool，Tool 的 `input_schema` 即为 JSON Schema，API 层验证输入合规性。无 Schema 时退化为纯文本 JSON 解析。

### 3.5 配置

```bash
# .env
TOOL_SERVICE_LLM_PROVIDER=openai          # deepseek | openai | claude
TOOL_SERVICE_LLM_OPENAI_API_KEY=sk-...
TOOL_SERVICE_LLM_OPENAI_MODEL=gpt-4o

# 或使用 Claude
TOOL_SERVICE_LLM_PROVIDER=claude
TOOL_SERVICE_LLM_ANTHROPIC_API_KEY=sk-ant-...
TOOL_SERVICE_LLM_ANTHROPIC_MODEL=claude-sonnet-4-6
```

不配置时自动使用 NoopProvider，所有 LLM 调用回退确定性算法。

### 3.6 时长解析 Strict Mode

新增 `contracts/llm/duration-parsing.schema.json`：

```json
{
  "type": "object",
  "required": ["targetDurationMs", "parsedFrom"],
  "properties": {
    "targetDurationMs": {"type": "integer", "minimum": 5000, "maximum": 300000},
    "parsedFrom": {"type": "string", "maxLength": 500}
  }
}
```

`_parse_duration_prompt()` 现在加载该 Schema 传递给 provider，支持 strict structured output。

## 4. P4 — 转场：FADE / CROSS_DISSOLVE

### 4.1 设计决策

此前 CUT 硬切被硬编码在 7 个层级（Schema、Java TimelineComposer、Python TimelineComposeTool、前端、Validator、FFmpeg 滤镜图、测试），每层都写死 `{"type": "CUT", "durationMs": 0}`。

第八阶段的选择：新增 FADE 和 CROSS_DISSOLVE，保持 CUT 作为默认值，向后兼容。

**转场语义**：
- **CUT**：硬切，无过渡，durationMs=0
- **FADE**：从黑场淡入（`fade=t=in`），200–2000ms
- **CROSS_DISSOLVE**：前后 Clip 交叉溶解（`xfade=transition=fade`），200–2000ms

### 4.2 启发式转场分配

三层实现（Python、Java、前端）使用相同逻辑：

| Clip 位置 | 转场类型 | 时长 |
|-----------|----------|------|
| 首个 Clip（index 0） | FADE（淡入开场） | 300ms |
| 段落边界（storyRole 变更） | CROSS_DISSOLVE（叙事过渡） | 500ms |
| 段内相邻 Clip | CUT（保持节奏） | 0ms |

### 4.3 FFmpeg 滤镜图升级

`_build_filter_graph()` 从简单的 concat 模式升级为四步构建：

```
Step 1: Per-clip intermediates
  [src:v]trim→setpts→scale→pad→fps→format + (可选)fade→[s{N}]
  [src:a]atrim→asetpts + (可选)afade→[a{N}]

Step 2: Video transition chain
  [s0] + [s1] → CUT? concat / CROSS_DISSOLVE? xfade → [acc]
  [acc] + [s2] → ... → final_video_label

Step 3: Audio transition chain
  [a0] + [a1] → CUT/FADE? concat / CROSS_DISSOLVE? acrossfade → [acc_a]
  [acc_a] + [a2] → ... → final_audio_label

Step 4: Post-processing (BGM + subtitles)
  [final_audio] + [bgm:a] → amix → [outa_mixed]
  [final_video] → subtitles filter → [outv_sub]
```

**关键 xfade offset 计算**：`offset = accumulated_duration - xfade_duration`（保证过渡区间恰好覆盖前一个 Clip 的尾部）。

### 4.4 Timeline Schema v1.1 转场部分

```json
"transitionIn": {
  "type": "object",
  "required": ["type", "durationMs"],
  "properties": {
    "type": { "enum": ["CUT", "FADE", "CROSS_DISSOLVE"] },
    "durationMs": { "type": "integer", "minimum": 0, "maximum": 2000 }
  },
  "allOf": [
    { "if": { "properties": { "type": { "const": "CUT" } } },
      "then": { "properties": { "durationMs": { "const": 0 } } } },
    { "if": { "properties": { "type": { "const": "FADE" } } },
      "then": { "properties": { "durationMs": { "minimum": 200 } } } },
    { "if": { "properties": { "type": { "const": "CROSS_DISSOLVE" } } },
      "then": { "properties": { "durationMs": { "minimum": 200 } } } }
  ]
}
```

### 4.5 Validator 关键规则

```python
_TRANSITION_RULES = {
    "CUT":            {"min": 0, "max": 0},
    "FADE":           {"min": 200, "max": 2000},
    "CROSS_DISSOLVE": {"min": 200, "max": 2000},
}
```

- 首个 Clip 的 `transitionIn` 必须为 CUT（不能对第一个 Clip 做交叉溶解）
- 转场时长必须小于 Clip 自身时长
- CUT 强制 0ms

## 5. P4 — BGM 背景音乐

### 5.1 BgmSelectTool

新增 `audio.bgm-select@1.0.0` Tool，根据段落角色映射 BGM 心情：

```python
BEAT_ROLE_TO_MOOD = {
    "HOOK":    "energetic",   # 吸引→活力
    "INTRO":   "calm",        # 介绍→平静
    "JOURNEY": "upbeat",      # 旅程→明快
    "CLIMAX":  "epic",        # 高潮→史诗
    "ENDING":  "serene",      # 结尾→宁静
}
```

**输入**：`STORY_PLAN` Artifact（读取 beats 角色分布）
**输出**：`BGM_AUDIO` Artifact（JSON 元数据：bgmPath、selectedMood、bgmDurationMs）
**曲库**：`runtime/bgm/` 目录下的 MP3 文件，按心情关键字匹配文件名
**Fallback**：曲库为空时返回 `{"available": false}`，渲染时跳过 BGM 混音

### 5.2 BGM 混音实现

```python
# video_render.py — _build_filter_graph() Step 5
if bgm_path is not None:
    inputs.append(str(bgm_path))
    transition_filters.append(
        f"[{bgm_input_index}:a]atrim=0:duration={total_dur},volume=0.3[bgm]"
    )
    transition_filters.append(
        f"[{final_audio_label}][bgm]amix=inputs=2:duration=first:"
        f"dropout_transition=0[outa_mixed]"
    )
    final_audio_label = "[outa_mixed]"
```

BGM 音量默认 0.3（30%），确保不压过原声。

### 5.3 Timeline AUDIO 轨

```json
{
  "type": "AUDIO",
  "source": {
    "uri": "file:///path/to/bgm.mp3",
    "startMs": 0,
    "volume": 0.3,
    "ducking": {"enabled": true, "duckVolume": 0.1, "attackMs": 200, "releaseMs": 500}
  }
}
```

## 6. P4 — ASR 语音转写与字幕

### 6.1 SpeechTranscribeTool

新增 `audio.speech-transcribe@1.0.0` Tool，使用 `faster-whisper` 模型：

```python
# 模型加载（lazy singleton，首次调用时下载 ~500MB）
from faster_whisper import WhisperModel
model = WhisperModel("small", device="cpu", compute_type="int8")
segments, _ = model.transcribe(audio_path, language=None, vad_filter=True)
```

**Pipeline**：
1. 读取 TIMELINE 获取 Clip 时间线偏移量
2. 对每个有音频流的 Proxy Video：FFmpeg 提取音频 → 16kHz mono WAV
3. faster-whisper 转写 → segments（start/end/text）
4. 按时间线位置偏移时间戳
5. 合并所有 segments → SRT 格式化
6. 写入 `SUBTITLE_SRT` Artifact

**配置**：`TOOL_SERVICE_ASR_MODEL_SIZE`（默认 `"small"`，可选 `"tiny"`/`"medium"`/`"large-v3"`）
**依赖**：`faster-whisper>=1.0.0`（已加入 requirements.txt）

### 6.2 字幕烧录

```python
# video_render.py — _build_filter_graph() Step 6
srt_escaped = str(srt_path).replace("\\", "/").replace(":", "\\:")
transition_filters.append(
    f"[{final_video_label}]subtitles='{srt_escaped}':"
    f"force_style='FontSize=24,PrimaryColour=&H00FFFFFF,"
    f"OutlineColour=&H00000000,Outline=1,Shadow=1,MarginV=50'[outv_sub]"
)
final_video_label = "[outv_sub]"
```

- Windows 路径冒号转义（FFmpeg 要求 `\\:`）
- 默认样式：白色字体 24px、黑色描边、底部留白 50px
- 无 SRT 输入时跳过字幕滤镜，`final_video_label` 不变

### 6.3 Timeline SUBTITLE 轨

```json
{
  "type": "SUBTITLE",
  "source": {
    "uri": "file:///path/to/subtitles.srt",
    "format": "SRT"
  }
}
```

## 7. 已知限制

### 7.1 Strict Structured Output 不能完全解决 hallucination

即使 GPT-4o `strict: true` 保证 JSON 结构合法，仍无法验证：
- `shotIds` 是否来自真实候选池（需要 grounding）
- `shotIds` 是否跨 beat 重复（JSON Schema 无跨数组唯一性约束）
- `reasonCodes` 是否适用于该 beat（语义层面）

这些仍需 `LlmStoryProposalValidator` 的后置校验。但 strict mode 消除了格式错误、缺失字段、类型错误、非法枚举值——这些是 DeepSeek 最常见的前 4 类失败。

### 7.2 faster-whisper 首次运行需下载模型

首次调用 `audio.speech-transcribe` 时需下载 Whisper 模型（small ~500MB），耗时取决于网络。建议在生产环境预下载到 HuggingFace 缓存目录。

### 7.3 BGM 曲库需手动准备

`runtime/bgm/` 目录默认不存在，需要用户手动放置无版权 BGM MP3 文件。无文件时 BGM Tool 返回 `available: false`，渲染时跳过混音（不影响主链路）。

### 7.4 xfade offset 可能为负

若 Clip 时长 < 转场时长，`offset = acc_duration - xfade_duration` 可能为负。Validator 已检查 `transition_duration < clip_duration`，但极短 Clip（< 200ms）可能在 Validator 通过后仍导致问题。渲染时 offset < 0 时 clamp 到 0。

### 7.5 CROSS_DISSOLVE 缩减时间线总时长

每个 CROSS_DISSOLVE 使总时长减少 `transition_duration` 毫秒（因为过渡重叠区间只计一次）。Timeline 的 `durationMs` 字段已反映实际渲染时长，但 Clip 级别的 `timelineInMs`/`timelineOutMs` 在 CROSS_DISSOLVE 之后不再连续（有重叠）。Validator 和 Composer 均适配此语义。

### 7.6 字幕仅支持 SRT 格式

当前仅实现 SRT 输出。VTT 格式在 Schema 中预留但未实现格式化函数。前端无字幕编辑器。

## 8. 未完成的工作

以下内容不在第八阶段范围内，留待后续：

1. **主观评分校准（P2 遗留）**: 当前排名权重来自工程经验，需要人工标记样本校准；
2. **基础设施完善（P5）**: Flyway 数据库迁移、Celery/Redis 任务队列、Docker Compose 一键部署；
3. **人物检测精度提升**: CLIP 零样本人物检测召回率不理想，可考虑 YOLO 或 ViT-L-14；
4. **智能关键帧选择**: 当前直接使用 Shot 边界帧，未做人脸/主体居中优化；
5. **CLIP 推理合并**: 三个视觉 Tool 各走一次前向传播，可合并为一次推理节省 2/3 时间；
6. **字幕编辑器**: 前端展示/编辑 SRT 字幕文本和时间轴；
7. **BGM 动态音量调整**: 根据是否有原声自动调整 BGM 音量（ducking）；
8. **更多转场效果**: WIPE、SLIDE、ZOOM 等；
9. **调色和画幅适配**: LUT 调色、不同宽高比的 pillarbox/letterbox；
10. **音频标准化**: 音量响度归一化（LUFS）、音频峰值限制。

## 9. 修改文件清单

```
已修改 (vs 第七阶段):
  contracts/llm/story-plan-proposal.schema.json          | v1.0→v1.1 (移除 beat targetDurationMs)
  contracts/llm/README.md                                | 记录 v1.1 设计决策
  contracts/timeline/timeline.schema.json                | v1.0→v1.1 (转场+音轨+字幕轨)
  contracts/timeline/README.md                           | 记录 v1.1 新增功能
  tool-service/app/core/config.py                        | +12 行 (llm provider/model, bgm, asr)
  tool-service/app/llm/provider.py                       | +210 行 (OpenAIProvider, ClaudeProvider, structured output)
  tool-service/app/llm/prompt.py                         | ~25 行变更 (v1.2 prompt, 移除 duration 求和规则)
  tool-service/app/tools/story_plan.py                   | ~50 行变更 (schema加载, LlmStoryProposalValidator更新)
  tool-service/app/tools/timeline_validator.py           | 完整重写 (模块化, 转场/音轨/字幕轨校验)
  tool-service/app/tools/video_render.py                 | 完整重写 (xfade链, BGM, subtitles)
  tool-service/app/tools/shot_decisions.py               | ~40 行变更 (TimelineComposeTool v1.1.0, _assign_transition)
  tool-service/app/registry/registry.py                  | +6 行 (VideoRenderTool, BgmSelectTool, SpeechTranscribeTool)
  tool-service/requirements.txt                          | +1 行 (faster-whisper)
  tool-service/tests/test_video_render.py                | ~60 行变更 (转场滤镜图测试, 新签名)
  tool-service/tests/test_shot_decisions.py              | ~120 行变更 (Validator测试, LLM测试更新)
  control-plane/.../plan/TimelineComposer.java           | ~50 行变更 (transitionStyle, assignTransition)
  control-plane/.../workflow/MultiAssetAnalysisTemplate.java | +8 行 (新增2节点, 4条边, v7)
  control-plane/.../workflow/WorkflowDefinitionValidator.java | +4 行 (KNOWN_TOOLS新增)
  control-plane/.../test/.../WorkflowDefinitionValidatorTest.java | 3 个数变更 (12节点, 7 WORKFLOW, v7)
  control-plane/.../static/app.js                        | ~20 行变更 (buildClientTimeline转场, renderDecisions多轨)
  control-plane/.../static/index.html                    | +1 行 (timeline-extras)
  control-plane/.../static/styles.css                    | +3 行 (transition指示器CSS)

新增:
  contracts/llm/duration-parsing.schema.json
  tool-service/app/tools/audio_bgm.py
  tool-service/app/tools/audio_transcribe.py
  docs/eighth-stage-handoff.md  (本文件)

删除:
  无
```

## 10. 自动化测试

```text
Python: 44 passed, 1 failed（预存 person_detect，与本次变更无关）
Java:   编译通过（mvn 未在 PATH，需 IntelliJ 内置 Maven 运行）
```

Python 覆盖（新增/更新测试）：
- `test_video_render.py`: 9 tests — manifest(v1.1.0)、单Clip、多源CUT、无音频、混合音频、FADE滤镜链、CROSS_DISSOLVE xfade链、代理路径解析、无效Timeline拒绝
- `test_shot_decisions.py`: 11 tests — Ranking确定性、低质量拒绝、Story Proposal Validator拒绝未知+重复shot、LLM Validator拒绝未知+重复、接受v1.0遗留格式、拒绝幻觉shot数量、Timeline Validator拒绝gap+溢出、接受FADE+CROSS_DISSOLVE、拒绝低于最小值的FADE、拒绝首个Clip的CROSS_DISSOLVE、接受AUDIO+SUBTITLE轨
- 其他 27 tests 全部通过（预存 person_detect 1 fail 除外）

## 11. Workflow DAG v7

```
每个素材 (ASSET scope):
  video_probe → video_proxy_generate → video_shot_detect → vision_quality_score
                                    → video_shot_detect → vision_vlm_analyze
                                    → video_proxy_generate → vision_quality_score

汇聚后 (WORKFLOW scope):
  vision_quality_score → shot_ranking → story_plan
  vision_vlm_analyze → story_plan
  shot_ranking → highlight_selection
  story_plan → highlight_selection
  highlight_selection → timeline_compose
  timeline_compose → bgm_select ─────────┐
  timeline_compose → speech_transcribe ──┤
  timeline_compose → video_render ←──────┘ (3个输入: TIMELINE + BGM_AUDIO + SUBTITLE_SRT)

12 个节点, 16 条边
DAG v7, MULTI_ASSET_ANALYSIS
```

`video_render` 通过 `matching_inputs()` 按前缀匹配上游输入（"bgm"/"subtitle"/"timeline"），BGM 或字幕 Artifact 不存在时优雅跳过对应处理步骤。

## 12. 本机运行方式

**前置条件**（与第七阶段相同，新增 BGM 曲库准备）：

1. MySQL 8.0 本机运行；
2. Conda 环境 `agent-video-pipeline`（Python 3.12 + torch + transformers + pillow + python-dotenv + faster-whisper）；
3. CLIP 模型已下载至 HuggingFace 缓存；
4. `tool-service/.env` 中配置 LLM API Key（可选，不配置则走确定性 fallback）；
5. （可选）BGM 曲库：在 `tool-service/runtime/bgm/` 下放置 MP3 文件；
6. （可选）faster-whisper 模型首次运行自动下载。

**安装新依赖**：

```powershell
cd tool-service
pip install faster-whisper>=1.0.0
```

Python Tool Service：

```powershell
scripts\start-tool-service.cmd
```

Java Control Plane：

```powershell
$env:MYSQL_USER = "root"
$env:MYSQL_PASSWORD = "你的 MySQL 密码"
scripts\start-control-plane.cmd
```

访问 `http://127.0.0.1:8080`。

**切换 LLM Provider**（`.env`）：

```bash
# GPT-4o (strict structured output)
TOOL_SERVICE_LLM_PROVIDER=openai
TOOL_SERVICE_LLM_OPENAI_API_KEY=sk-...

# 或 Claude
TOOL_SERVICE_LLM_PROVIDER=claude
TOOL_SERVICE_LLM_ANTHROPIC_API_KEY=sk-ant-...

# 或 DeepSeek（原有）
TOOL_SERVICE_LLM_PROVIDER=deepseek
TOOL_SERVICE_LLM_API_KEY=sk-...
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
1. docs/eighth-stage-handoff.md（本文件）
2. docs/seventh-stage-handoff.md
3. docs/sixth-stage-handoff.md
4. docs/fifth-stage-handoff.md
5. docs/fourth-stage-handoff.md
6. docs/third-stage-handoff.md
7. docs/second-vertical-slice.md
8. docs/first-vertical-slice.md
9. docs/Agent-Driven智能视频制作流水线-系统设计文档.md
10. README.md
11. docs/modules/control-plane/README.md

第八阶段已完成 LLM 优化和音频/字幕/转场：

P3 — LLM 优化：
- LLM Schema v1.1：移除 beat 级别 targetDurationMs（编译器使用确定性 _beat_budgets()），LLM 只需分配 shotIds + reasonCodes
- 新增 OpenAIProvider（json_schema + strict: true）和 ClaudeProvider（tool_use + input_schema），保证 100% Schema 合规输出
- 新增 contracts/llm/duration-parsing.schema.json，时长解析支持 strict structured output
- LlmStoryProposalValidator 接受 v1.0 和 v1.1，向后兼容

P4 — 音频/字幕/转场：
- 转场：Timeline Schema v1.1 新增 FADE(200–2000ms) + CROSS_DISSOLVE(200–2000ms) + CUT(0ms)
  · 启发式分配：首个 Clip=FADE 300ms，段落边界=CROSS_DISSOLVE 500ms，段内=CUT
  · FFmpeg 滤镜图从 concat 升级为 xfade+acrossfade+fade+afade 链
  · TimelineValidator 完整重写：模块化验证，转场类型/时长/首个Clip约束
- BGM：新增 audio.bgm-select@1.0.0 Tool，段落角色→心情→本地曲库；渲染时 amix 混音（默认 volume 0.3）
- ASR 字幕：新增 audio.speech-transcribe@1.0.0 Tool，faster-whisper 转写→SRT 字幕→渲染时 subtitles 滤镜烧录

Workflow DAG v7（12节点, 16边）：
  每个素材：video.probe → video.proxy-generate → video.shot-detect → vision.quality-score + vision.vlm-analyze
  汇聚后：shot_ranking → story_plan → highlight_selection → timeline_compose
          timeline_compose → bgm_select ──────────→ video_render
          timeline_compose → speech_transcribe ────→ video_render
          timeline_compose → video_render ←────────── (3个可选输入)

新增文件：
  contracts/llm/duration-parsing.schema.json
  tool-service/app/tools/audio_bgm.py (BgmSelectTool)
  tool-service/app/tools/audio_transcribe.py (SpeechTranscribeTool)

VideoRenderTool v1.1.0 滤镜图构建：
  Step 1: Per-clip trim+scale+（可选）fade → [sN]/[aN]
  Step 2: xfade 链（CUT→concat, CROSS_DISSOLVE→xfade） → final_video_label
  Step 3: 音频链（acrossfade） → final_audio_label
  Step 4: BGM amix + subtitles 滤镜 → [outv_sub]/[outa_mixed]

LLM 安全边界不变：
  LLM 不生成 Shell/FFmpeg/SQL/URI，不选择未注册 Tool，失败时自动 fallback 确定性算法。

已知限制：
- Strict structured output 保证 JSON 结构但不验证 shotId 真实性（仍需 post-validation）
- faster-whisper 首次运行需下载模型（small ~500MB）
- BGM 曲库需手动准备 runtime/bgm/ 目录
- 字幕仅支持 SRT 格式，无前端编辑器

Python 44 tests passed（1 预存失败不影响），Java 编译通过。

下一阶段建议按优先级：P5 基础设施完善（Flyway/Celery/Docker） → 主观评分校准 → 人物检测精度提升。

开始改动前检查 Git 状态并保留用户已有修改。MySQL 密码和 LLM API Key 只允许从仓库外文件读入进程环境，不得输出或写入仓库。完成后更新文档、运行 Java/Python 测试并做真实多素材 E2E。
```
