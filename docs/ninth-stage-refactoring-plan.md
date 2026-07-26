 # 第九阶段：前端重构、人在回路与字幕后置
 
 > 文档日期：2026-07-26
 > 当前阶段：方案设计阶段，尚未开始实施
 > 阶段性质：架构重构（前端全量重写 + Java Workflow 引擎扩展），Python Tool Service 核心算法不变
 
 ## 目录
 
 1. [背景与动机](#1-背景与动机)
 2. [重构目标](#2-重构目标)
 3. [不做的事](#3-不做的事)
 4. [总体架构变更](#4-总体架构变更)
 5. [Workflow 引擎设计：Gate 人在回路系统](#5-workflow-引擎设计gate-人在回路系统)
 6. [字幕后置流程设计](#6-字幕后置流程设计)
 7. [前端架构设计](#7-前端架构设计)
 8. [API 变更清单](#8-api-变更清单)
 9. [分阶段实施计划](#9-分阶段实施计划)
 10. [旧代码处理策略](#10-旧代码处理策略)
 11. [风险与缓解](#11-风险与缓解)
 12. [设计决策记录](#12-设计决策记录)
 
 ## 1. 背景与动机
 
 ### 1.1 当前痛点
 
 经过第八阶段的完成，项目已具备完整链路：多素材上传 → 镜头检测 → 评分排名 → Story Plan → Timeline → BGM/转场/字幕 → 渲染成片。但存在两个结构性不足：
 
 **前端维护困难**
 
 现有前端由 3 个原生文件组成（`index.html` 166 行、`app.js` 1203 行、`styles.css` 185 行），没有框架、没有构建工具、没有模块系统。随着功能持续增长——
 - 257 个镜头的列表渲染
 - Story Plan 编辑模式（替换、排序、锁定、增删）
 - 版本管理（保存、加载、Diff 对比、恢复、删除）
 - LLM 审计面板
 - Timeline 可视化
 - 成片预览与下载
 
 ——单文件原生 JS 模式已严重制约开发效率和用户体验。新增任何功能都需要在一个千行文件中谨慎操作，状态管理靠全局 `state` 对象，DOM 操作靠 `el()` 辅助函数，缺乏类型安全和组件隔离。
 
 **Workflow 缺乏用户介入能力**
 
 当前 Workflow 是完全自动化的流水线：启动后一口气跑到成片渲染，中间没有任何暂停点。用户只能在 Workflow 跑完后通过 Edit Mode 进行事后修改，然后重新渲染。这种模式有两个问题：
 
 1. 用户对中间产物（镜头切分、评分排名）没有审查机会，等发现问题时已经跑到了渲染阶段，浪费计算资源；
 2. 字幕生成（`audio.speech-transcribe`）基于原始素材音频，而非最终成片的混音结果，导致字幕时间轴与实际成片不匹配。
 
 ### 1.2 驱动重构的核心决策
 
 | 决策 | 内容 |
 |------|------|
 | D1 | 前端从零重写，使用 Vue 3 + TypeScript + Vite |
 | D2 | Workflow 增加"人在回路"（Human-in-the-Loop）Gate 系统 |
 | D3 | 字幕生成从主 DAG 中移出，改为成片渲染后的后置流程（ASR on 成片 → 二次渲染烧录） |
 | D4 | Python Tool Service 核心算法不动，仅新增 2 个后置字幕工具 |
 | D5 | 旧前端保留副本，新前端独立开发并渐进替换 |
 
 ## 2. 重构目标
 
 ### 2.1 前端目标
 
 1. **可维护性**：组件化、类型安全、模块化，新开发者能快速定位和修改代码
 2. **人在回路支持**：每个 Gate 对应专属审核视图，用户可审查和编辑中间产物
 3. **增量更新**：组件内部状态变更不触发全局 re-render，避免页面闪烁
 4. **性能**：大量镜头卡片使用虚拟滚动，分层轮询替代全局轮询
 5. **产品化体验**：加载态、空态、错误处理、响应式适配
 
 ### 2.2 Workflow 目标
 
 1. **Gate 暂停机制**：在关键节点自动暂停，等待用户审核确认后继续
 2. **Auto 模式**：提供一键代理开关，跳过所有 Gate 全自动运行（兼容原有行为）
 3. **字幕后置**：字幕基于最终成片音频生成，时间轴精度更高
 
 ## 3. 不做的事
 
 - **不修改** Python Tool Service 现有的视频分析、评分、排名、Story Plan、Timeline 算法
 - **不修改** Java ↔ Python 之间的 HTTP 通信协议和 Tool Manifest 注册机制
 - **不修改** 数据库核心表结构（`workflow_runs`、`task_runs`、`artifacts` 等），仅在 `RunStatus` 枚举中新增一个值
 - **不引入** 新的深度学习框架或模型
 - **不改变** 现有的失败重试、幂等执行、定时补偿扫描机制
 - **不引入** 前端 SSR / SSG，保持 SPA 模式
 
 ## 4. 总体架构变更
 
 ### 4.1 重构前（第八阶段末）架构
 
 ```
 ┌──────────────────────────────────────────┐
 │  浏览器 (index.html + app.js + styles.css) │
 │  无框架 · 无构建 · 静态资源由 Spring Boot 提供  │
 └──────────────────┬───────────────────────┘
                    │ HTTP REST
                    ▼
 ┌──────────────────────────────────────────┐
 │  Java Spring Boot (:8080)                 │
 │  ┌────────────────────────────────────┐   │
 │  │ WorkflowDefinition (12 节点 DAG)    │   │
 │  │ WorkflowExecutionService            │   │
 │  │ RunStatus: CREATED→RUNNING→        │   │
 │  │           SUCCEEDED/FAILED          │   │
 │  └────────────────────────────────────┘   │
 └──────────────────┬───────────────────────┘
                    │ HTTP
                    ▼
 ┌──────────────────────────────────────────┐
 │  Python FastAPI (:8090)                   │
 │  12 个 Tool · 核心算法不动                 │
 └──────────────────────────────────────────┘
 ```
 
 ### 4.2 重构后架构
 
 ```
 ┌─────────────────────────────────────────────┐
 │  浏览器 (Vite Dev Server :5173)               │
 │  Vue 3 + TypeScript + Pinia + Vue Router     │
 │  Tailwind CSS · 组件化 · 分层轮询              │
 │  ┌───────────────────────────────────────┐   │
 │  │ Gate 审核视图 (人在回路)                 │   │
 │  │ ShotReview / RankingReview /          │   │
 │  │ StoryEditor / TimelinePreview /       │   │
 │  │ FinalReview                           │   │
 │  └───────────────────────────────────────┘   │
 └──────────────────┬──────────────────────────┘
                    │ HTTP REST (Vite proxy → :8080)
                    ▼
 ┌──────────────────────────────────────────────┐
 │  Java Spring Boot (:8080)                     │
 │  ┌────────────────────────────────────────┐   │
 │  │ WorkflowDefinition (11 节点 + 5 Gate)   │   │
 │  │ WorkflowExecutionService (Gate 暂停逻辑) │   │
 │  │ RunStatus: CREATED→RUNNING⇄PAUSED→     │   │
 │  │           SUCCEEDED/FAILED              │   │
 │  │ POST /continue (Gate 通过后恢复)         │   │
 │  └────────────────────────────────────────┘   │
 └──────────────────┬──────────────────────────┘
                    │ HTTP (不变)
                    ▼
 ┌──────────────────────────────────────────────┐
 │  Python FastAPI (:8090)                       │
 │  12 个现有 Tool（不变）                        │
 │  + audio.transcribe-final    (新增)          │
 │  + video.render-subtitles    (新增)          │
 └──────────────────────────────────────────────┘
 ```
 
 ### 4.3 Workflow DAG 变更对比
 
 **重构前（12 节点，全自动）**：
 
 ```
 ASSET 级（每素材独立）:
   video.probe → video.proxy-generate → video.shot-detect
                                      → vision.quality-score
                                      → vision.vlm-analyze
 
 WORKFLOW 级（跨素材汇总）:
   decision.shot-rank → planning.story-template → decision.highlight-select
                                                 → timeline.compose
                                                       → audio.bgm-select
                                                       → audio.speech-transcribe  ← 移除
                                                       → video.render
 ```
 
 **重构后（11 节点 + 5 Gate）**：
 
 ```
 ASSET 级（不变，自动跑）:
   video.probe → video.proxy-generate → video.shot-detect
                                      → vision.quality-score
                                      → vision.vlm-analyze
 
 WORKFLOW 级:
   decision.shot-rank ───── 完成后触发 [Gate 1: 镜头排序审核]
          │
   planning.story-template ─ 完成后触发 [Gate 2: 故事安排编辑]
          │
   decision.highlight-select → timeline.compose → audio.bgm-select
                                                       │
                                               完成后触发 [Gate 3: 时间线预览]
                                                       │
                                                 video.render (第一遍：无字幕成片)
                                                       │
                                               完成后触发 [Gate 4: 成片预览与字幕配置]
                                                       │
         ┌─────────────────────────────────────────────┘
         │  (用户确认字幕配置后，触发 Post-Render 流程)
         ▼
   audio.transcribe-final (ASR on 成片 → SRT)
         │
   video.render-subtitles (烧录字幕 → 最终成片)
         │
   [Gate 5: 最终成片下载]
 ```
 
 ## 5. Workflow 引擎设计：Gate 人在回路系统
 
 ### 5.1 核心概念
 
 **Gate（关卡）** 是 Workflow DAG 中的一个逻辑暂停点。当某个 Node 执行完成后，如果该 Node 关联了一个 Gate，则 Workflow 暂停，等待用户在前端审核/编辑后发送 "继续" 信号。
 
 Gate 不是 Task，不产生 Tool Execution，不调用 Python。它是纯 Java 端的调度层概念。
 
 ### 5.2 数据模型变更
 
 #### 5.2.1 RunStatus 新增 PAUSED
 
 ```java
 // 文件：control-plane/.../execution/RunStatus.java
 public enum RunStatus {
     CREATED,
     RUNNING,
     PAUSED,    // ← 新增
     SUCCEEDED,
     FAILED
 }
 ```
 
 状态转换规则：
 
 ```
 CREATED → RUNNING → PAUSED → RUNNING → PAUSED → ... → SUCCEEDED
                   ↘ FAILED
 ```
 
 - `RUNNING → PAUSED`：当任务完成后检查到 Gate 且非 auto 模式
 - `PAUSED → RUNNING`：用户调用 `/continue` 恢复执行
 - `PAUSED` 状态下，定时补偿扫描（`ToolExecutionPoller`）不触发新调度
 - `PAUSED` 状态下，回调仍正常处理（已完成的任务可以正常回调）
 
 #### 5.2.2 TaskStatus 不变
 
 Task 本身不感知 Gate。Gate 暂停时，Gate 之前的 Task 状态为 `SUCCEEDED`，Gate 之后的 Task 保持 `PENDING`（不标记为 `READY`），直到用户继续。
 
 #### 5.2.3 WorkflowDefinition 新增 Gate 记录
 
 ```java
 // 文件：control-plane/.../workflow/WorkflowDefinition.java
 public record WorkflowDefinition(
     String definitionKey,
     int definitionVersion,
     List<Node> nodes,
     List<Edge> edges,
     List<Gate> gates         // ← 新增
 ) {
     // ... 现有 Node、Edge、InputBinding、NodeScope 不变 ...
 
     public record Gate(
         String gateKey,        // 唯一标识，如 "gate_shot_ranking"
         String afterNodeKey,   // 关联的 Node.nodeKey，该 Node 完成后触发
         String label,          // UI 显示名，如 "镜头排序审核"
         String description     // 提示文案，如 "请检查系统排序，可手动调整评分或排除镜头"
     ) {}
 }
 ```
 
 ### 5.3 Gate 定义清单
 
 | # | gateKey | afterNodeKey | label | 用户可操作 |
 |---|---------|-------------|-------|-----------|
 | 1 | `gate_shot_ranking` | `shot_ranking` | 镜头排序审核 | 查看排名，调整评分权重，强制入选/排除镜头 |
 | 2 | `gate_story_edit` | `story_plan` | 故事安排编辑 | 替换 shot、排序、锁定、增删（复用现有 Edit Mode） |
 | 3 | `gate_timeline_preview` | `bgm_select` | 时间线与音乐预览 | 预览时间线 + BGM 搭配，拖拽调整片段 |
 | 4 | `gate_render_review` | `video_render` | 成片预览与字幕配置 | 预览无字幕成片，配置字幕样式（字号/位置/颜色） |
 | 5 | `gate_final_download` | `video_render_subtitles` | 最终成片下载 | 预览带字幕成片，下载 |
 
 **注**：Gate 2（故事安排编辑）直接复用现有的 `CustomStoryPlanController` 和 `custom_story_plans` 表，前端的 StoryEditor 视图就是现有 Edit Mode 的增强版。
 
 ### 5.4 调度逻辑变更
 
 #### 5.4.1 WorkflowExecutionService 变更点
 
 位置：`onTaskSucceeded()` 或等效的 task 完成回调方法。
 
 **现有逻辑**（伪代码）：
 
 ```java
 void onTaskSucceeded(TaskRunEntity task) {
     // 找出所有依赖此 task 的下游 task
     var downstreamTasks = findDownstreamTasks(task);
     for (var dt : downstreamTasks) {
         if (allDependenciesSucceeded(dt)) {
             dt.markReady();
             eventPublisher.publishEvent(new WorkflowDispatchRequested(runId, dt.getId()));
         }
     }
     // 检查是否全部完成
     if (allTasksTerminal()) {
         workflow.markSucceeded();
     }
 }
 ```
 
 **新逻辑**（伪代码）：
 
 ```java
 void onTaskSucceeded(TaskRunEntity task) {
     // 1. 检查是否有 Gate 关联在当前 task 的 nodeKey 上
     var gate = definition.findGateByNodeKey(task.getNodeKey());
 
     if (gate != null && !workflow.isAutoMode()) {
         // 2. 暂停：不 dispatch 下游 task，workflow 进入 PAUSED
         workflow.markPaused(gate.getGateKey());
         workflowRepository.save(workflow);
         // 3. 前端轮询时会看到 PAUSED + currentGate，展示审核 UI
         return;
     }
 
     // 4. 无 Gate 或 auto 模式：走现有逻辑，dispatch 下游
     dispatchDownstream(task);
 
     if (allTasksTerminal()) {
         workflow.markSucceeded();
     }
 }
 ```
 
 #### 5.4.2 新增 /continue API
 
 ```
 POST /api/v1/workflow-runs/{workflowRunId}/continue
 ```
 
 - 仅当 `runStatus == PAUSED` 时有效
 - 从当前 Gate 的下游 task 开始 dispatch（标记为 READY，发布事件）
 - workflow 状态从 `PAUSED` 切回 `RUNNING`
 - 响应：`{ "workflowRunId": "...", "status": "RUNNING", "nextGate": "gate_story_edit" }`
 
 #### 5.4.3 Auto 模式
 
 `WorkflowRunEntity` 新增字段：
 
 ```java
 @Column(name = "auto_mode", nullable = false)
 private boolean autoMode = false;  // 默认 false（人在回路开启）
 ```
 
 创建 Workflow 时由前端传入：
 
 ```
 POST /api/v1/projects/{projectId}/multi-asset-analysis-runs
 Body: {
     "assetIds": [...],
     "proxyQuality": "1080P",
     "durationPrompt": "快节奏15秒",
     "autoMode": false        // ← 新增字段
 }
 ```
 
 当 `autoMode = true` 时，所有 Gate 检查被跳过，Workflow 行为与重构前完全一致。
 
 ### 5.5 WorkflowRunEntity 变更
 
 新增字段：
 
 | 字段 | 类型 | 说明 |
 |------|------|------|
 | `autoMode` | `boolean` | 是否跳过所有 Gate 自动运行 |
 | `currentGateKey` | `String` (nullable) | 当前暂停的 Gate 标识，RUNNING 时为 null |
 
 `RunStatus.PAUSED` 时，`currentGateKey` 必须有值；其他状态时为 null。
 
 ## 6. 字幕后置流程设计
 
 ### 6.1 动机
 
 第八阶段中，`audio.speech-transcribe` 在渲染前基于原始素材音频做 ASR。问题：
 
 - BGM 混音后，原始素材音频的时间轴与最终成片的音频轨不完全一致
 - 转场（FADE/CROSS_DISSOLVE）会改变音频重叠区域
 - 字幕时间轴精度受影响
 
 **解决方案**：对最终渲染成片（已含 BGM 混音和转场）做 ASR，生成 SRT 字幕，再二次渲染烧录。
 
 ### 6.2 流程
 
 ```
 video.render (第一遍：无字幕成片)
     │
     ▼
 [Gate 4: 成片预览与字幕配置]
     │  用户确认字幕样式
     ▼
 audio.transcribe-final
     │  输入：第一遍成片 MP4
     │  输出：SRT 字幕文件 + 字幕元数据 JSON
     │
     ▼
 video.render-subtitles
     │  输入：第一遍成片 MP4 + SRT + 样式参数
     │  输出：带字幕的最终 MP4
     │
     ▼
 [Gate 5: 最终成片下载]
 ```
 
 ### 6.3 新增 Python Tool
 
 #### 6.3.1 `audio.transcribe-final` v1.0.0
 
 | 属性 | 值 |
 |------|-----|
 | Tool Name | `audio.transcribe-final` |
 | 输入 Artifact | 渲染后的成片 MP4（`video.render` 的输出） |
 | 输出 Artifact | SRT 字幕文件 + 字幕元数据 JSON（含每段时间戳和文本） |
 | 实现 | 基于 faster-whisper（已有依赖），对视频音轨做 ASR |
 
 #### 6.3.2 `video.render-subtitles` v1.0.0
 
 | 属性 | 值 |
 |------|-----|
 | Tool Name | `video.render-subtitles` |
 | 输入 | 第一遍成片 MP4 + SRT 字幕文件 + 样式参数 |
 | 样式参数 | `fontSize` (默认 24), `fontColor` (默认 "white"), `position` (默认 "bottom"), `outlineColor` (默认 "black") |
 | 输出 | 带烧录字幕的最终 MP4 |
 | 实现 | FFmpeg `subtitles` filter，复用 `VideoRenderTool` 中的滤镜链逻辑 |
 
 ### 6.4 Post-Render 流程的调度方式
 
 Post-Render 的 `transcribe-final` → `render-subtitles` 不走主 Workflow DAG，而是：
 
 - 在 `WorkflowController`（或单独的 `PostRenderController`）上暴露端点
 - 端点内用 `WorkflowExecutionService` 创建一个 mini WorkflowRun（`POST_RENDER_SUBTITLE`，2 个节点）
 - 前端在 Gate 4 通过后调用此端点，并轮询 mini Workflow 的进度
 
 ## 7. 前端架构设计
 
 ### 7.1 技术选型
 
 | 层 | 选择 | 版本 | 理由 |
 |---|------|------|------|
 | 框架 | Vue 3 | ^3.5 | Composition API + `<script setup>`，响应式细粒度更新 |
 | 语言 | TypeScript | ^5.5 | 严格模式，类型安全 |
 | 构建 | Vite | ^5.4 | 快速 HMR，零配置起步 |
 | 路由 | Vue Router | ^4.4 | 多页面导航 |
 | 状态管理 | Pinia | ^2.2 | Vue 官方推荐，TS 友好，按 Feature 拆分 Store |
 | CSS | Tailwind CSS | ^3.4 | 加速开发，JIT 编译 |
 | HTTP | 原生 fetch + 轻量 wrapper | - | 无额外依赖负担 |
 | 视频播放 | 原生 `<video>` | - | 够用，不做专业剪辑器 |
 | 虚拟滚动 | vue-virtual-scroller | ^2.0 | 大量镜头卡片性能优化 |
 | 图标 | Lucide Vue | latest | 按钮和工具栏图标 |
 
 ### 7.2 目录结构
 
 ```
 web-app/
 ├── index.html
 ├── package.json
 ├── vite.config.ts
 ├── tsconfig.json
 ├── tailwind.config.js
 ├── postcss.config.js
 ├── public/
 │   └── favicon.ico
 └── src/
     ├── main.ts                       # 应用入口
     ├── App.vue                       # 根组件
     ├── router/
     │   └── index.ts                  # 路由配置
     ├── api/
     │   ├── client.ts                 # fetch 封装 + 错误处理
     │   ├── projects.ts               # 项目 CRUD API
     │   ├── assets.ts                 # 素材上传/列表 API
     │   ├── workflows.ts              # Workflow 启动/查询/继续 API
     │   ├── plans.ts                  # Story Plan / 版本管理 API
     │   ├── artifacts.ts              # Artifact 内容 API
     │   └── types.ts                  # API 请求/响应 TypeScript 类型
     ├── stores/
     │   ├── project.ts                # 项目列表、当前项目
     │   ├── workflow.ts               # Workflow 状态、task 列表、Gate 信息
     │   ├── review.ts                 # 人在回路审核状态（当前 Gate、编辑数据）
     │   └── ui.ts                     # 全局 UI 状态（auto mode、加载态等）
     ├── shared/
     │   ├── types.ts                  # 共享领域类型（Shot、StoryPlan、Timeline 等）
     │   ├── constants.ts              # 常量（Gate 列表、Beat 名称等）
     │   └── composables/
     │       ├── usePolling.ts         # 通用轮询 hook（支持动态 interval）
     │       └── useVideoPlayer.ts     # 视频播放控制 hook
     ├── components/
     │   ├── AppShell.vue              # 全局布局（侧边栏 + 内容区）
     │   ├── TaskGrid.vue              # Workflow DAG 任务卡片网格
     │   ├── ShotCard.vue              # 单个镜头卡片
     │   ├── VideoPreview.vue          # 视频播放器（含自定义控制条）
     │   ├── StatusBadge.vue           # 状态标签（CREATED/RUNNING/PAUSED/...）
     │   ├── ProgressBar.vue           # 进度条
     │   └── EmptyState.vue            # 空状态占位
     └── features/
         ├── projects/
         │   ├── ProjectListPage.vue   # 首页：项目列表 + 创建
         │   └── ProjectCard.vue       # 项目卡片
         ├── assets/
         │   ├── AssetUpload.vue       # 视频上传（拖拽 + 批量）
         │   └── AssetList.vue         # 素材列表
         ├── workflow/
         │   ├── WorkflowLauncher.vue   # 启动面板（清晰度、时长、auto 开关）
         │   └── WorkflowMonitor.vue    # 运行监控页（DAG + 进度）
         ├── review/
         │   ├── ShotRankingReview.vue  # Gate 1：排序审核
         │   ├── StoryEditor.vue        # Gate 2：故事安排编辑（增强版 Edit Mode）
         │   ├── TimelinePreview.vue    # Gate 3：时间线预览
         │   ├── FinalReview.vue        # Gate 4：成片预览 + 字幕配置
         │   └── FinalDownload.vue      # Gate 5：最终成片下载
         ├── versions/
         │   ├── VersionList.vue        # 版本列表
         │   └── VersionDiff.vue        # 版本 Diff 对比
         ├── render/
         │   └── RenderProgress.vue     # 渲染进度（第一遍 + 字幕烧录）
         └── audit/
             └── LlmAuditPanel.vue     # LLM 审计面板
 ```
 
 ### 7.3 路由设计
 
 | 路径 | 组件 | 说明 |
 |------|------|------|
 | `/` | `ProjectListPage` | 项目列表 + 创建新项目 |
 | `/projects/:id` | 项目详情（内嵌 `AssetUpload` + `AssetList` + `WorkflowLauncher`） | 素材管理 + 启动 Workflow |
 | `/projects/:id/runs/:runId` | `WorkflowMonitor`（内嵌 Gate 审核视图） | Workflow 监控 + 人在回路 |
 | `/projects/:id/runs/:runId/versions` | `VersionList` + `VersionDiff` | 版本管理 |
 | `/projects/:id/audit` | `LlmAuditPanel` | LLM 审计 |
 
 ### 7.4 状态管理与增量更新策略
 
 #### 7.4.1 Store 拆分原则
 
 ```
 useProjectStore    — 项目列表、当前项目 ID
 useWorkflowStore   — 当前 Workflow 状态（runStatus、tasks、currentGate）
 useReviewStore     — 审核模式下的编辑数据（修改的 shot、调整的排名等）
 useUiStore         — auto mode、loading、全局通知
 ```
 
 每个 Store 独立，组件只订阅自己需要的 Store：
 
 ```typescript
 // ShotRankingReview.vue 只订阅 workflow + review
 const workflow = useWorkflowStore()
 const review = useReviewStore()
 // 不引入 useProjectStore，project 变更不会触发此组件 re-render
 
 // ProjectListPage.vue 只订阅 project
 const project = useProjectStore()
 // 不引入 useWorkflowStore
 ```
 
 #### 7.4.2 分层轮询
 
 | 轮询目标 | 所属组件 | 频率 | 说明 |
 |---------|---------|------|------|
 | Workflow 状态 + Task 列表 | `WorkflowMonitor` | 1.5s | 检测 runStatus 变化和 task 进度 |
 | 渲染进度 | `RenderProgress` | 3s | 渲染较慢，低频足够 |
 | Shot 列表 | `ShotRankingReview` | 不轮询 | Gate 激活时一次性加载，数据不变 |
 
 轮询 hook 示例：
 
 ```typescript
 // shared/composables/usePolling.ts
 export function usePolling(fn: () => Promise<void>, intervalMs: number) {
     let timer: ReturnType<typeof setInterval> | null = null
 
     const start = () => {
         stop()
         fn() // 立即执行一次
         timer = setInterval(fn, intervalMs)
     }
 
     const stop = () => {
         if (timer) { clearInterval(timer); timer = null }
     }
 
     onUnmounted(stop) // 组件卸载自动清理
     return { start, stop }
 }
 ```
 
 #### 7.4.3 增量更新原则
 
 1. **数据 diff 后更新**：轮询拿到新数据后，与当前 Store 中的数据进行浅比较，仅更新变化的字段
 2. **CSS containment**：TaskGrid、ShotCard、Timeline track 设置 `contain: layout style`，避免文字更新触发全局 layout
 3. **固定尺寸**：卡片、按钮、状态标签设固定高度/宽度，状态切换只改变颜色和文字
 4. **key 稳定**：`v-for` 使用稳定的 `:key`（数据库 ID），确保 Vue 复用 DOM 而非重建
 
 ### 7.5 Vite 代理配置
 
 ```typescript
 // vite.config.ts
 export default defineConfig({
     server: {
         port: 5173,
         proxy: {
             '/api': 'http://localhost:8080',
             '/internal': 'http://localhost:8080'
         }
     }
 })
 ```
 
 开发时 `npm run dev` 启动 Vite dev server，API 请求自动转发到 Java 后端。生产构建后产物放到 `control-plane/src/main/resources/static/` 由 Spring Boot 直接提供。
 
 ## 8. API 变更清单
 
 ### 8.1 新增 API
 
 | 方法 | 路径 | 说明 |
 |------|------|------|
 | `POST` | `/api/v1/workflow-runs/{workflowRunId}/continue` | 从 PAUSED 状态恢复执行 |
 | `POST` | `/api/v1/workflow-runs/{workflowRunId}/post-render/subtitle` | 触发字幕后置渲染流程 |
 
 ### 8.2 修改 API
 
 | 方法 | 路径 | 变更 |
 |------|------|------|
 | `POST` | `/api/v1/projects/{projectId}/multi-asset-analysis-runs` | 请求体新增 `autoMode: boolean` |
 | `GET` | `/api/v1/workflow-runs/{workflowRunId}` | 响应体新增 `autoMode`、`currentGateKey`、`gates: [{gateKey, label, description}]` |
 
 ### 8.3 不变 API
 
 | 方法 | 路径 | 说明 |
 |------|------|------|
 | `GET/POST` | `/api/v1/projects` | 项目 CRUD，不变 |
 | `POST` | `/api/v1/projects/{id}/assets` | 素材上传，不变 |
 | `GET` | `/api/v1/projects/{id}/assets` | 素材列表，不变 |
 | `GET` | `/api/v1/projects/{id}/workflow-runs` | 历史 Workflow 列表，不变 |
 | `GET/PUT/POST/DELETE` | `/api/v1/workflow-runs/{id}/custom-story-plan/*` | Story Plan 编辑与版本管理，不变 |
 | `GET` | `/api/v1/artifacts/{id}/content` | Artifact 内容，不变 |
 | `POST` | `/internal/tool-callbacks` | Python 回调，不变 |
 
 ## 9. 分阶段实施计划
 
 ### P0：前端项目骨架搭建
 
 - [ ] 在 `web-app/` 下初始化 Vite + Vue 3 + TypeScript 项目
 - [ ] 配置 Tailwind CSS、PostCSS
 - [ ] 配置 Vue Router、Pinia
 - [ ] 搭建 `api/client.ts` fetch 封装层
 - [ ] 搭建 `AppShell.vue` 全局布局
 - [ ] 搭建 `shared/types.ts` 领域类型
 - [ ] 配置 Vite proxy 指向 Java `:8080`
 - [ ] 验证：`npm run dev` 启动，能调通 `GET /api/v1/projects`
 
 ### P1：项目管理与素材上传
 
 - [ ] `ProjectListPage.vue`：项目列表 + 创建项目
 - [ ] `AssetUpload.vue`：视频上传（拖拽 + 批量 + 进度）
 - [ ] `AssetList.vue`：素材列表展示
 - [ ] `useProjectStore`：项目状态管理
 - [ ] API 对接：projects、assets 全套 CRUD
 - [ ] 验证：能创建项目、上传视频、查看素材列表
 
 ### P2：Workflow 启动与监控
 
 - [ ] `WorkflowLauncher.vue`：清晰度选择、时长输入、auto 模式开关
 - [ ] `WorkflowMonitor.vue`：DAG 可视化、Task 状态卡片、进度条
 - [ ] `TaskGrid.vue`、`StatusBadge.vue`、`ProgressBar.vue` 通用组件
 - [ ] `useWorkflowStore`：Workflow 状态 + 分层轮询
 - [ ] `usePolling` composable
 - [ ] 验证：能启动 Workflow，实时看到 Task 状态变化
 
 ### P3：Java 端 Gate 系统
 
 - [ ] `RunStatus.PAUSED` 新增
 - [ ] `WorkflowDefinition.Gate` 记录类
 - [ ] `WorkflowRunEntity` 新增 `autoMode`、`currentGateKey` 字段 + 数据库迁移
 - [ ] `MultiAssetAnalysisTemplate` 重构：移除 `speech_transcribe`，新增 Gates，DAG 调整
 - [ ] `WorkflowExecutionService` 新增 Gate 暂停逻辑
 - [ ] `POST /continue` API 实现
 - [ ] 单元测试：Gate 暂停/恢复 + Auto 模式跳过
 - [ ] 验证：启动 Workflow → 跑到 Gate 暂停 → 调 `/continue` → 继续执行
 
 ### P4：人在回路审核视图
 
 - [ ] `ShotRankingReview.vue`：Gate 1 审核界面
   - 展示排名列表（分数、扣分项、入选/落选原因）
   - 强制入选/排除操作
   - 确认按钮
 - [ ] `StoryEditor.vue`：Gate 2 故事编辑界面
   - 基于现有 Edit Mode 逻辑增强
   - 替换 shot、排序、锁定、增删
   - 版本保存（复用 `custom_story_plans` API）
 - [ ] `TimelinePreview.vue`：Gate 3 时间线预览界面
   - 可视化时间线轨道
   - BGM 名称展示
   - 成片时长确认
 - [ ] `FinalReview.vue`：Gate 4 成片预览 + 字幕配置界面
   - 视频播放器预览第一遍成片
   - 字幕样式配置：字号、颜色、位置
   - "生成字幕并渲染" 按钮
 - [ ] `FinalDownload.vue`：Gate 5 最终下载界面
   - 带字幕成片预览
   - 下载按钮
 - [ ] `useReviewStore`：审核状态管理
 - [ ] 验证：每个 Gate 的界面能正确展示数据，确认后 Workflow 继续
 
 ### P5：字幕后置流程
 
 - [ ] Python 端新增 `audio.transcribe-final` Tool
   - 输入：视频 MP4
   - 输出：SRT + 字幕元数据 JSON
 - [ ] Python 端新增 `video.render-subtitles` Tool
   - 输入：视频 MP4 + SRT + 样式参数
   - 输出：带字幕 MP4
 - [ ] Java 端：Post-Render 调度端点
   - `POST /api/v1/workflow-runs/{id}/post-render/subtitle`
   - 创建 `POST_RENDER_SUBTITLE` mini Workflow
 - [ ] 前端：`RenderProgress.vue` 渲染进度展示
 - [ ] 集成测试：第一遍渲染 → Gate 4 审核 → 字幕烧录 → Gate 5 下载
 
 ### P6：版本管理与 LLM 审计
 
 - [ ] `VersionList.vue`：版本列表
 - [ ] `VersionDiff.vue`：版本 Diff 对比（复用现有 Diff 算法）
 - [ ] `LlmAuditPanel.vue`：LLM 审计面板
 - [ ] 验证：版本管理功能与旧版行为一致
 
 ### P7：打磨与收尾
 
 - [ ] 全量响应式适配（桌面优先，移动端可用）
 - [ ] 加载态骨架屏（Skeleton）
 - [ ] 空状态引导文案
 - [ ] 错误边界与全局错误提示
 - [ ] 键盘快捷键（空格播放/暂停、Enter 确认等）
 - [ ] 构建产物接入 Spring Boot static 目录
 - [ ] 端到端验证：完整跑通 "创建项目 → 上传视频 → 启动 Workflow → 5 个 Gate 逐一审核 → 字幕渲染 → 下载"
 - [ ] 更新 `项目现状与上手指南.md` 和 `README.md`
 
 ## 10. 旧代码处理策略
 
 ### 10.1 旧前端
 
 旧前端文件（`control-plane/src/main/resources/static/` 下的 `index.html`、`app.js`、`styles.css`）：
 
 - 在 `docs/` 下创建 `legacy-frontend/` 目录，保留旧版副本
 - 旧文件保留在原位置不动，直到新前端全部完成并通过验收
 - 验收通过后，删除原位置的旧文件，替换为新前端的构建产物
 
 ### 10.2 Java 端
 
 以下文件需要修改（不删除，在原文件上改）：
 
 | 文件 | 变更类型 |
 |------|---------|
 | `RunStatus.java` | 新增 `PAUSED` 枚举值 |
 | `WorkflowRunEntity.java` | 新增 `autoMode`、`currentGateKey` 字段 |
 | `WorkflowDefinition.java` | 新增 `Gate` 记录类、`gates` 字段 |
 | `MultiAssetAnalysisTemplate.java` | 调整 DAG（移除 speech 节点、新增 Gates） |
 | `WorkflowExecutionService.java` | 新增 Gate 暂停/恢复逻辑 |
 | `WorkflowController.java` | 新增 `/continue` 端点，修改创建接口 |
 
 以下文件不变：
 
 | 文件 | 原因 |
 |------|------|
 | `TaskRunEntity.java` | Task 不感知 Gate |
 | `TaskStatus.java` | 状态模型不变 |
 | `ToolServiceClient.java` | Java-Python 通信协议不变 |
 | `CustomStoryPlanController.java` | 版本管理接口不变 |
 | `WorkflowDispatchListener.java` | 事件监听逻辑不变 |
 | `ToolExecutionPoller.java` | 补偿扫描不变（PAUSED 时跳过） |
 
 ### 10.3 Python 端
 
 所有现有 Tool 文件不变。仅新增 2 个文件：
 
 | 文件 | 说明 |
 |------|------|
 | `app/tools/audio_transcribe_final.py` | `audio.transcribe-final` Tool 实现 |
 | `app/tools/video_render_subtitles.py` | `video.render-subtitles` Tool 实现 |
 
 同时更新 `app/registry/registry.py` 注册两个新 Tool，以及对应的测试文件。
 
 ### 10.4 Contracts
 
 新增以下 contract 文件：
 
 | 文件 | 说明 |
 |------|------|
 | `contracts/gate-definition.schema.json` | Gate 定义的 JSON Schema |
 | `contracts/transcribe-final-request.schema.json` | 字幕转写请求格式 |
 | `contracts/render-subtitles-request.schema.json` | 字幕渲染请求格式 |
 
 ## 11. 风险与缓解
 
 | 风险 | 影响 | 缓解 |
 |------|------|------|
 | Gate 暂停后服务重启导致状态丢失 | Workflow 卡在 PAUSED | `currentGateKey` 持久化到数据库，重启后前端可继续审核 |
 | 用户在 Gate 暂停时修改了上游数据 | 数据不一致 | Gate 通过后重新加载最新数据再 dispatch |
 | Auto 模式下的回归 Bug | 原有全自动流程失败 | P3 阶段为 Auto 模式编写专门测试，确保行为与重构前一致 |
 | 旧前端仍在提供服务时新前端已部署 | 用户混淆 | 新前端用不同路径（如 `/app/`），旧前端保留在 `/`，灰度切换 |
 | Vue 3 学习曲线 | 开发效率暂时下降 | P0 输出完整项目模板，团队成员可快速克隆启动 |
 | 虚拟滚动与视频播放交互 Bug | 用户体验差 | 虚拟滚动组件仅在 Shot 数量 > 50 时启用，少量镜头直接渲染 |
 
 ## 12. 设计决策记录
 
 本节记录重构方案中关键决策及其理由，供后续开发和 Code Review 参考。
 
 ### ADR-001：Gate 放在 Workflow 层而非 Task 层
 
 **决策**：Gate 作为 `WorkflowRun` 的状态（`PAUSED`）而非新增 `TaskStatus`。
 
 **理由**：
 - Gate 暂停的是整体流程，不是单个 Task 的执行
 - Task 状态模型已经稳定（8 种状态），新增 `WAITING_REVIEW` 会污染 Task 语义
 - `PAUSED` 与 `RUNNING` 之间切换不影响已完成 Task 的记录
 
 ### ADR-002：Post-Render 字幕不走主 DAG
 
 **决策**：字幕转写和烧录不放入 `MULTI_ASSET_ANALYSIS` Workflow，而是独立 mini Workflow。
 
 **理由**：
 - 主 DAG 在第一次 `video.render` 后已经完成主要使命
 - 字幕是可选功能（用户可能不需要），不应强制阻塞主流程
 - mini Workflow 复用了现有的 `WorkflowExecutionService` 调度能力，无需新建调度器
 
 ### ADR-003：前端不引入组件库
 
 **决策**：不引入 Element Plus、Ant Design Vue 等组件库。
 
 **理由**：
 - 项目面向视频编辑场景，UI 风格需要定制化，通用组件库反而不够贴合
 - 组件库的 Tree-shaking 不彻底，会增加构建体积
 - 核心交互（Timeline 轨道、Shot 卡片、DAG 图）没有现成组件可用，需自行实现
 
 ### ADR-004：Pinia Store 按 Feature 拆分
 
 **决策**：使用多个小型 Pinia Store 而非单一巨型 Store。
 
 **理由**：
 - 满足"组件内部更新不波及其他组件"的增量更新要求
 - Vue 的响应式跟踪是 Store 级别：如果所有状态在一个 Store 里，任何字段变化都可能触发订阅该 Store 的组件 re-render
 - 按 Feature 拆分后，ShotReview 更新 reviewStore 不影响 ProjectList 组件
 
 ### ADR-005：轮询而非 WebSocket
 
 **决策**：前端使用 HTTP 轮询获取 Workflow 状态，不引入 WebSocket。
 
 **理由**：
 - 当前 Task 粒度较大（单 Task 执行时间在秒级到分钟级），1.5s 轮询足够
 - 轮询实现简单，不需要维护 WebSocket 连接和重连逻辑
 - Spring Boot 内嵌 Tomcat 对 WebSocket 的支持需要额外配置
 - 如果后续需要实时推送，可以在不改变前端 Store 结构的情况下将轮询替换为 WebSocket/SSE
 
 ---
 
 > **下一步**：请评审本方案，确认后从 P0 开始实施。
 > 方案中标记为 `[ ]` 的条目为待实施的任务清单。
