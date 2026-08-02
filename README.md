# Agent-Driven Intelligent Video Production Pipeline

面向多素材视频创作的智能生产流水线。用户上传多段视频并设置成片目标后，系统完成素材分析、镜头理解、质量评分、全局排序、故事编排、时间线生成、BGM/字幕处理和最终渲染；用户可以在关键阶段审核、编辑并继续执行。

当前正式开发基线：

```text
C:\Users\XRZ\Desktop\ninth\WwDa3B884n8dj
```

## 当前状态

答辩后的固定主流程和第十三阶段执行前动态编排已经形成可操作闭环：

- Vue 3 + TypeScript + Vite 前端工作台已完成重写；
- 注册、登录、服务端 Session、退出和项目所有权隔离已完成；
- 多素材 Workflow、并行素材分析、任务进度和历史 Workflow 已完成；
- 镜头预览、镜头画廊、质量评分、VLM 分析和跨素材 Ranking 已完成；
- 五段式 Story Plan、LLM 审计、人工编辑、版本保存/载入和应用已完成；
- Timeline 预览与编辑已支持 CUT、FADE、CROSS_DISSOLVE；
- BGM Provider 抽象、Jamendo 候选、本地曲库回退、本地音频上传、试听、换一批和无 BGM 模式已完成；
- 源音频转写、Timeline 映射字幕、SRT 烧录、最终视频预览和下载已完成；
- 素材软删除、不可变 Artifact、完整血缘和原 Workflow 内的下游重执行已完成；
- Python SQLite Execution Journal、幂等执行和重启恢复已完成；
- 资源感知调度、模型释放、Render 资源限制和服务器部署配置已完成；
- 前端左侧栏已集成动态助手“初雪”，可感知 Workflow 完成并提示用户；
- 第十三阶段执行前动态 DAG 已完成：LLM 自然语言规划、中文拓扑画布、受控编辑、默认 DAG 回退、服务端校验和半自动/全自动选择均已落地；
- 多素材镜头画廊、故事编辑和时间线中的关键帧、时长及代理视频映射已完成修复。

## 主 Workflow

`MULTI_ASSET_ANALYSIS` 当前为 13 个逻辑节点、19 条依赖边，其中 3 条为可选依赖。每个素材先展开独立分析分支，随后汇入工作流级决策和渲染任务：

```text
video.probe
  -> video.proxy-generate
  -> video.shot-detect
  -> vision.quality-score
  -> vision.vlm-analyze
  -> audio.source-transcribe

vision.quality-score + vision.vlm-analyze
  -> decision.shot-rank
  -> planning.story-template
  -> decision.highlight-select
  -> timeline.compose

planning.story-template + timeline.compose
  -> audio.bgm-select

timeline.compose + audio.source-transcribe
  -> subtitle.compose

timeline.compose + audio.bgm-select(optional) + subtitle.compose(optional)
  -> video.render
```

从产品流程看，系统共包含 6 个用户确认 Gate：1 个执行前编排 Gate，以及 5 个运行中业务审核 Gate。

1. 动态 DAG 编排与确认（执行前）；
2. 镜头排序审核；
3. 故事安排编辑；
4. 时间线预览与调整；
5. BGM 候选试听与选择；
6. 最终成片预览。

其中后 5 个 Gate 记录在 `WorkflowDefinition.gates` 中，并由运行时状态机暂停和恢复；动态 DAG Gate 位于 Workflow 创建之前，因此不属于运行时 Definition 内的 Gate 数组，但在产品交互和阶段统计中按第 1 个 Gate 计算。

故事和时间线的人工修改会在原 Workflow 内写入新的不可变 Artifact，重置尚未执行的下游 Task 后继续执行，不创建脱离原血缘的临时 Workflow。已完成 Artifact 不会被覆盖或删除。

## 动态 DAG 编排

第十三阶段在固定模板之上增加了执行前动态规划层。用户先用自然语言描述成片目标和时长，LLM 只返回受控能力意图，Java Planner 再基于默认模板生成候选 `WorkflowDefinition`：

```text
自然语言需求
  -> 受控 Workflow Intent
  -> 候选 DAG / 默认 DAG
  -> 素材级拓扑展开
  -> 用户拖拽、增删节点与连线
  -> 服务端 DAG 校验
  -> 半自动 / 全自动确认
  -> 既有 WorkflowExecutionService
```

当前交互包括：

- 时长直接写在自然语言需求中，支持秒、分钟、半分钟和英文时间表达；
- LLM 生成初版 DAG，失败时确定性回退默认 DAG；
- 中文流程图按依赖分层，多素材任务并行展开；
- 支持缩放、画布高度调整、节点拖动、手动连线和两次点击删除节点/连线；
- 可选能力卡片直接拖入画布，拖入后不自动连线；
- 已选能力只读展示，当前受控能力为 VLM、源音频转写、字幕和 BGM；
- 字幕强制依赖源音频转写，禁用转写时后端同步禁用字幕；
- 非法 DAG 无法确认，最终结构由服务端重新校验；
- 用户可以随时恢复系统默认 DAG，并在确认时选择半自动（保留 5 个运行中 Gate）或全自动（跳过运行中 Gate）执行。

阶段交接和边界说明见 [`docs/thirteenth-stage-handoff.md`](docs/thirteenth-stage-handoff.md)。动态 DAG Gate 确认后，本次 Workflow 拓扑冻结；运行中的 5 个 Gate 只允许编辑镜头、故事、时间线、BGM 等业务 Artifact，不允许增删节点或修改依赖。

## 主要产品能力

### 项目与素材

- 按用户隔离项目、素材、Workflow、Artifact 和 Story Plan；
- 批量上传视频，显示进度、素材状态、时长和媒体信息；
- 原始视频和镜头代理视频预览；
- 素材软删除：新 Workflow 不再使用 `REMOVED` 素材，历史记录仍可恢复。

### AI 分析与故事编排

- `video.probe`、代理视频生成、镜头检测、质量评分、VLM 场景理解；
- Shot 级清晰度、曝光、稳定性、构图和综合质量评分；
- CLIP 场景/物体标签、人物检测和语义特征；
- Ranking 保存评分分解、运动兴趣、时长适配、重复惩罚、素材均衡和时间邻近惩罚；
- LLM 支持 DeepSeek、OpenAI 和 Claude 的结构化输出，不可用时使用确定性回退；
- 固定 `HOOK -> INTRO -> JOURNEY -> CLIMAX -> ENDING` 故事结构；
- 少于五个镜头时允许空故事段，但整份方案仍必须至少包含一个合法镜头；
- LLM 建议、实际采纳、拒绝原因和审计信息分开保存。

### 人工编辑与版本

- Story Plan 支持替换、添加、删除、排序、锁定和历史版本载入；
- 保存版本只创建 Story Plan 数据快照，不复制素材或渲染结果；
- “应用方案并继续执行”会重新执行当前 Workflow 的 Highlight、Timeline、BGM、字幕和 Render 下游；
- Timeline 支持拖动排序、片段间转场选择、源区间预览和总时长重算；
- Story Plan 和 Timeline 在 Service 层再次进行结构化校验。

### 音乐、字幕与渲染

- BGM Provider 当前支持 Jamendo 和本地曲库回退；
- 候选按情绪、时长、标签、器乐属性、作者多样性和近期展示历史排序；
- 用户可试听候选、换一批、上传 MP3/WAV/M4A/AAC/OGG/FLAC，或选择无 BGM；
- 短音乐可选择循环到视频结束或只播放一次，模式仅允许 `ONCE/LOOP`；
- 源音频转写生成 `SOURCE_TRANSCRIPT`，字幕根据 Timeline 源/目标时间映射生成 SRT；
- `video.render@1.1.0` 接收 `TIMELINE`、可选 `BGM_AUDIO` 和可选 `SUBTITLE_SRT`；
- 无 BGM、无字幕、素材无音轨时安全降级；
- FFmpeg 滤镜图使用受控的 `fade`、`xfade`、`acrossfade`、`amix` 和 `subtitles` 链；
- 已修复混合转场时间基、BGM 标签、中文字体和 Render OOM 相关问题。

## 架构

```text
Vue 3 + TypeScript + Vite
          |
          | REST / Session / Artifact Content API
          v
Java 21 + Spring Boot Control Plane
  用户、项目、Workflow、动态 DAG Planner、服务端校验、Task 状态机、Gate、Artifact 血缘、LLM 审计
          |
          | 版本化 HTTP Tool API + 内部回调
          v
Python 3.11 + FastAPI Tool Service
  Tool Registry、Manifest、异步执行、SQLite Journal、模型适配、媒体处理、FFmpeg
          |
          +--> MySQL：业务状态与用户数据
          +--> shared runtime volume：Artifact、执行记录、音乐缓存
          +--> Hugging Face cache：模型缓存
```

关键约束：

- Java 不启动 Python 脚本，双方只通过 HTTP Tool API 解耦；
- Tool 必须通过版本化 Manifest 注册，Java Workflow 与 Python Registry 保持契约一致；
- LLM 输出只是候选结构化数据，必须经过 Schema、类型、DAG、权限和参数校验；
- LLM 不得生成 Shell、FFmpeg、SQL、本地路径或任意工具调用；
- Artifact 不可变，选择、替换、重渲染和失败恢复均保留完整生产血缘；
- 失败、重试、Gate 暂停、服务重启和可选增强失败必须收敛到明确状态。

## Tool Manifest

当前 Python Registry 已注册：

```text
video.probe
video.proxy-generate
video.shot-detect
vision.quality-score
vision.scene-classify
vision.object-detect
vision.person-detect
vision.vlm-analyze
audio.source-transcribe
decision.shot-rank
planning.story-template
decision.highlight-select
timeline.compose
audio.bgm-select
subtitle.compose
video.render
```

具体版本和输入/输出 Artifact 契约以 `tool-service/app/registry/`、各 Tool 的 `manifest()` 实现以及 `contracts/` 为准。

## 本地运行

### 无服务测试

```powershell
cd control-plane
mvn test

cd ..\tool-service
python -m pytest -q
```

### Docker Compose

在仓库根目录准备被 Git 忽略的 `.env`，至少设置本地 MySQL 密码；不要把真实凭据写入仓库。

```powershell
cd C:\Users\XRZ\Desktop\ninth\WwDa3B884n8dj
docker compose config
docker compose up -d --build
docker compose ps
```

默认入口：`http://127.0.0.1:8080`

### 前端开发

```powershell
cd web-app
npm ci
npm run build
npm run dev
```

生产环境由 `control-plane/Dockerfile` 在构建 Java 镜像时执行前端构建并复制静态资源；不要把 `node_modules` 或编译产物提交到 Git。

## 测试与验证基线

最近阶段交接记录的验证包括：

- Java Maven 全量测试通过；
- Python 全量测试达到 85 passed，Render 专项测试达到 19 passed；
- Vue `vue-tsc --noEmit` 和 Vite 生产构建通过；
- 本地 Docker Compose 配置、Control Plane 和 Tool Service 生产构建通过；
- 已验证 BGM、字幕、混合转场、无 BGM 降级、短素材故事计划、执行恢复和资源限制场景；
- 已完成真实浏览器工作台、项目隔离、素材预览、Workflow 监控、最终成片预览和下载验证；
- 第十三阶段已验证自然语言时长提取、LLM/默认 DAG、素材级拓扑展开、依赖校验和半自动/全自动创建；
- 本机 `control-plane` 生产镜像已重新构建并更新，动态编排前端和多素材镜头预览修复已实际生效。

服务器部署配置位于 `deploy/`，详细说明见 [`docs/deployment-aliyun.md`](docs/deployment-aliyun.md)。线上环境仍应在拉取最新提交后重新执行一次真实 Workflow 验收，确认模型下载、内存限制、字幕字体、Render 和重启恢复符合当前服务器配置。

## 项目结构

- `web-app/`：Vue 3 + TypeScript + Vite 前端工作台；
- `control-plane/`：Java 21 / Spring Boot 控制面、REST API、Workflow 和 Gate；
- `tool-service/`：Python FastAPI Tool Service、模型适配、媒体分析与 FFmpeg；
- `contracts/`：Java、Python、前端共享的 OpenAPI、Schema 和协议契约；
- `docs/`：阶段交接、模块说明、架构、部署和 API 文档；
- `deploy/`：Caddy、生产环境变量模板和部署配置；
- `scripts/`：本地启动、测试和数据库辅助脚本；
- `samples/`：最小测试素材或示例输入。

## 后续方向

第十三阶段已结束。第十四阶段已开始进行生产化加固，不再规划面向用户的运行中 Replan：

1. 第十四阶段：可观测性、压测基线与 Artifact Storage/阿里云 OSS 已完成交接；视频、音频、图片二进制上传 OSS，JSON/SRT/Manifest 继续使用本地共享卷，媒体支持预签名直连预览，后续只需补充隔离前缀下的写入、Range、multipart 和故障恢复压测；
2. 第十五阶段：引入 RabbitMQ、Transactional Outbox、分资源 Worker 横向扩展和 Redis 草稿/缓存；
3. 建立由 Tool Manifest 驱动的版本化 `Capability Catalog`，减少 Java/Python 双端能力声明；
4. 按实际审计需求持久化 Workflow Intent、Definition hash、确认人和幂等信息；
5. 增加浏览器级自动化回归并补齐限流、账号恢复、发布回滚等安全加固。

第十四阶段交接见 [`docs/fourteenth-stage-handoff.md`](docs/fourteenth-stage-handoff.md)（兼容保留 [`docs/fourteenth-stage-plan.md`](docs/fourteenth-stage-plan.md)），第十五阶段计划见 [`docs/fifteenth-stage-plan.md`](docs/fifteenth-stage-plan.md)。

动态编排的边界保持不变：Agent 负责理解意图，Planner/Validator 决定允许的实现方式，现有执行引擎负责可靠执行；动态规划失败时回退到经过验证的固定模板。Workflow 在执行前 Gate 确认后冻结拓扑，需求变化时创建新的 Workflow，而不是修改正在运行的实例。

## 备份目录说明

本 README 只对应正式目录 `C:\Users\XRZ\Desktop\ninth\WwDa3B884n8dj`。其他同名目录是历史备份或参考副本，更新文档和代码时不要混用：

- `C:\Users\XRZ\Desktop\third\WwDa3B884n8dj`：历史副本；
- `C:\Users\XRZ\Desktop\summer\WwDa3B884n8dj`：旧版/测试素材参考目录；
- `C:\Users\XRZ\Desktop\sbell2`：旧备份目录。

不要执行会覆盖、回退或清理用户工作区的 Git 操作。密码、API Key、`.env`、视频/音频二进制、模型缓存和本地运行数据均不得提交到 Git。
