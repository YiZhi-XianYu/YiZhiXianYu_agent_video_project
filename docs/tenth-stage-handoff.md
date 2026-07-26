# 第十阶段交接：Workflow 收敛与跨服务契约修复

> 文档日期：2026-07-27  
> 工作基线：`C:\Users\XRZ\Desktop\ninth\WwDa3B884n8dj`  
> 阶段状态：代码、自动测试和 Docker 三服务启动已验证；真实多素材/真实 FFmpeg 端到端尚未执行

## 1. 本阶段目标

第十阶段不继续扩张功能，优先修复第九阶段 Vue、Gate 和字幕后处理重构造成的架构漂移：

- 统一 Java `WorkflowDefinition`、Python Registry/Manifest、Artifact 输入和 Tool 版本；
- 恢复失败、可选增强、重试、Gate 和服务重启后的 Workflow 收敛语义；
- 把字幕改为“素材源时间转写 -> Timeline 映射 -> Render 一次完成”；
- 清除旧的成片后置 ASR/二次渲染 mini Workflow；
- 让 Vue Gate 页面真正读取后端不可变 Artifact，而不是停留在空白占位状态；
- 清理误提交的 TypeScript/Vue 编译产物和过期静态 bundle。

## 2. 当前主 Workflow

`MULTI_ASSET_ANALYSIS` v9：13 个逻辑节点，19 条逻辑边，3 条可选边。

```text
ASSET:
video.probe -> video.proxy-generate
video.proxy-generate -> video.shot-detect
video.proxy-generate + video.shot-detect -> vision.quality-score
video.shot-detect -> vision.vlm-analyze
video.proxy-generate -> audio.source-transcribe

WORKFLOW:
vision.quality-score -> decision.shot-rank
decision.shot-rank + vision.vlm-analyze -> planning.story-template
planning.story-template + decision.shot-rank -> decision.highlight-select
decision.highlight-select -> timeline.compose
planning.story-template + timeline.compose -> audio.bgm-select
timeline.compose + audio.source-transcribe(optional) -> subtitle.compose
timeline.compose + audio.bgm-select(optional) + subtitle.compose(optional) -> video.render
```

四个 Gate 位于 Ranking、Story Plan、Timeline 和最终 Render 之后。终点 Render Gate 现在可以触发；已确认 Gate 会持久化，不会在继续执行时重复暂停。

## 3. 已完成修复

### 3.1 Java Control Plane

- `WorkflowDefinition.Edge` 增加 `REQUIRED/OPTIONAL`，依赖类型写入 `task_dependencies`。
- `workflow_runs` 持久化 `auto_mode`、当前 Gate、Gate 定义和已完成 Gate。
- `continueWorkflow()` 增加事务边界，兼容悲观锁查询。
- 必需上游失败会使下游 `SKIPPED`；可选增强失败不会跳过 Render。
- Workflow 终态忽略 BGM/字幕/源转写增强失败，但仍严格要求核心链路成功。
- Render 输入实际绑定为 `TIMELINE`、`BGM_AUDIO`、`SUBTITLE_SRT`。
- BGM 输入实际绑定为 `STORY_PLAN` 和 `TIMELINE`。
- 字幕输入实际绑定为 `TIMELINE` 和零到多个 `SOURCE_TRANSCRIPT`。
- 自定义 Story Plan Render 继续使用 `timeline.compose@1.1.0` 和 `video.render@1.1.0`。
- Java Timeline 的 CROSS_DISSOLVE 改为重叠时间轴语义，并在源镜头没有余量时降级 CUT。

### 3.2 Python Tool Service

- 注册 `vision.vlm-analyze@1.0.0`、`audio.source-transcribe@1.0.0` 和 `subtitle.compose@1.0.0`。
- `timeline.compose@1.1.0` 与 `video.render@1.1.0` 和 Java 版本一致。
- `audio.source-transcribe` 输出不可变 `SOURCE_TRANSCRIPT`，无音轨时返回零输出。
- `subtitle.compose` 使用 Timeline 的源/目标时间映射生成 SRT；无转写内容时返回零输出。
- `audio.bgm-select` 无曲库时返回零输出；有曲目时复制 MP3 到不可变 Artifact 目录。
- `BGM_AUDIO` 的 URI、媒体类型、大小和哈希描述真实 MP3，不再指向元数据 JSON。
- Render 可在无 BGM、无字幕和源视频无音轨时构造降级滤镜图。
- 修正 `decision.highlight-select` Manifest，声明实现实际读取的 `SHOT_RANKING`。
- 删除 `audio.transcribe-final` 与 `video.render-subtitles` 后置 Tool。

### 3.3 Vue 前端与构建

- 删除第五 Gate、最终下载页和后置字幕渲染进度组件。
- Task 列表改为 v9 的 `source_transcribe -> subtitle_compose -> video_render` 顺序。
- Workflow 快照类型补齐 Task Artifact、依赖、版本和进度字段。
- Gate 页面从后端 `ArtifactSnapshot.contentUrl` 只读加载 Ranking、Story Plan、Timeline 和 Rendered Video。
- 最终 Gate 直接预览已经包含可用 BGM/字幕的 Render 结果。
- 删除 `web-app/src` 下误提交的 `.js/.d.ts` 编译产物。
- 删除 Java static 目录中与 Vue 源码不一致的旧 hash bundle；Docker 构建时先构建 Vue，再复制到 Java JAR。
- Docker 中 Java/Python 共享同一个 runtime volume，Artifact URI 对两个服务可见。
- Java 容器入口会初始化共享 runtime 顶层目录权限，再降权为 `app` 用户运行，修复新建 Volume 后上传视频返回 500 的问题。
- 修复未配置 `CLIP_LOCAL_MODEL_PATH` 时空字符串被误判为当前目录、继而传给 Hugging Face 形成空 Repo ID 的问题；现在仅接受明确存在的本地目录，否则使用公开 CLIP 模型 ID。
- 修复字幕滤镜对已带方括号的视频标签重复包裹，避免生成 `[[vcN]]subtitles=...` 的非法 FFmpeg filter graph。
- FFmpeg 滤镜/参数解析错误现在明确标记为不可重试，临时资源错误仍可重试，避免确定性失败重复执行相同命令。
- Java 在实体边界把超过 2000 字符的 Tool 错误压缩为“首部 + 截断标记 + 尾部”，避免数据库事务因错误文本过长反复回滚。
- 前端基础导航改为真实 `RouterLink`：项目、Workflow 历史、LLM 审计均有独立有效路由，不再通过 404 兜底返回首页却错误高亮。
- `AppShell` 只负责布局并渲染 slot，根组件成为唯一 `RouterView` 所有者，消除重复路由出口。
- 新增跨项目 Workflow 历史页；LLM 审计页从不可变 `STORY_PLAN` Artifact 聚合真实 `llmAudit`，不再展示空数据骨架。
- Java 项目/素材 DTO 补齐创建与更新时间，前端日期契约与后端一致，不再显示 `Invalid Date`。

## 4. 测试与验证结果

- Java：`mvn test`，19 个测试全部通过。
- Python：专用 Conda 环境及 Docker 镜像环境运行 `pytest -q`，58 个测试全部通过。
- 新增 Java 回归覆盖：已完成 Gate 不重复暂停、终点 Gate、可选依赖失败仍放行 Render、Render 真实输入组装。
- 新增 Python 回归覆盖：v9 的 13 个 Tool 名称/版本和关键 Manifest 输入契约、CLIP 空/无效本地路径回退、字幕标签拼接和 FFmpeg 错误重试分类。
- 新增 Java 回归覆盖：超长 Tool 错误在 2000 字符字段边界内保留首尾诊断信息。
- `docker compose config` 成功解析，三个镜像构建成功；Vue 生产构建已在 Docker 镜像构建阶段完成。
- MySQL、Java Control Plane 和 Python Tool Service 三个容器启动成功；Flyway 校验通过，数据库保持 V1 且无需新增迁移。
- 重新创建 Java 容器后，PID 1 以 `app` 用户运行，且 `app` 用户可写 `/app/runtime/storage`；上传目录权限修复不再依赖手工 `docker exec`。
- 修复后真实 Workflow `a83f9955-7414-4b35-9b6f-028e693ec248` 已 `SUCCEEDED 100%`；`video.render@1.1.0` 首次执行成功、无重试，并生成 `RENDERED_VIDEO` Artifact。
- 浏览器回归通过：首页项目卡片、项目详情、Workflow 历史、Workflow 监控、全局 LLM 审计及深层路由刷新均可用；控制台无 warning/error。

## 5. 数据库变更

未发布基线直接更新 `V1__initial_schema.sql`：

- `workflow_runs.auto_mode`
- `workflow_runs.current_gate_key`
- `workflow_runs.gates_json`
- `workflow_runs.completed_gates_json`
- `task_dependencies.dependency_type`

如果后续改为保留已发布数据库，不应继续改 V1，而应新增增量 Flyway migration。

## 6. 仍需真实环境验证

以下内容没有在本阶段宣称完成：

- 真实多素材 Workflow 从上传到最终 Render 的完整收敛；
- LLM 在线成功路径和断网/Key 缺失回退；
- 最小媒体的 CUT/FADE/CROSS_DISSOLVE 混合渲染；
- 无音轨、多音轨、Windows 字幕路径转义、BGM 音量和最终时长；
- 服务在 RUNNING/RETRY_WAIT/PAUSED 状态重启后的数据库恢复；
- Vue 生产构建、浏览器控制台和真实 Gate 编辑/保存行为。

## 7. 下一步建议顺序

1. 使用最小无音轨视频验证 Render 无 BGM/无字幕降级。
2. 使用两段短素材验证 CUT/FADE/CROSS_DISSOLVE、音频映射和成片总时长。
3. 运行真实多素材 Workflow，并在四个 Gate 分别刷新页面/重启服务验证恢复。
4. 完成前端实际编辑提交 API；当前 Gate 组件展示和本地编辑状态已恢复，但编辑保存仍需单独验收。

## 8. 安全与仓库卫生

- 未把数据库密码、API Key 或本地凭据写入文档和仓库。
- 未安装或升级 Conda、Node、Maven、JDK 或系统依赖。
- `runtime/`、BGM、模型缓存、`.env`、`node_modules` 和构建产物均保持 Git 忽略。
- 用户主动删除的《项目现状与上手指南》、`src/.gitkeep`、`src/README.md` 和用户修改的 `.vscode/settings.json` 均未恢复或覆盖。
