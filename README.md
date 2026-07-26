# Agent-Driven Intelligent Video Production Pipeline

Agent 驱动的智能视频制作流水线暑期实训项目。

当前进入第十阶段：修复第九阶段重构造成的 Workflow、Gate、字幕和前端集成漂移。主链路使用 `WorkflowDefinition` v9（13 个逻辑节点、19 条边、3 条可选依赖），每个素材先执行代理、镜头、质量、VLM 和源时间语音转写；工作流级再执行 Ranking、Story Plan、Highlight、Timeline、BGM、字幕编排和 Render。BGM、源转写或字幕不可用时会安全降级，Render 只强制要求 `TIMELINE`。Java 与 Python 仍只通过 HTTP Tool API 解耦，所有产物保持不可变并保留生产 Task 血缘。当前修复详情和未完成的真实媒体验证见 [第十阶段交接](docs/tenth-stage-handoff.md)。

## 当前功能

- 创建视频项目。
- 批量上传本地视频素材并展示有序素材列表。
- 创建多素材 `MULTI_ASSET_ANALYSIS` Workflow，同时保留第二阶段单素材 API。
- 校验 WorkflowDefinition 的节点唯一性、边引用和 DAG 无环性。
- 执行 Task 状态流转：`PENDING -> READY -> DISPATCHING -> RUNNING -> SUCCEEDED/FAILED/SKIPPED`。
- Java 使用异步 HTTP 调用 `video.probe@1.0.0`，不直接执行 Python 脚本。
- Python FastAPI Tool Service 支持 Manifest、异步 Execution、幂等键、状态查询和回调。
- Python 调用 ffprobe，输出时长、分辨率、FPS、视频/音频编码和文件大小。
- 用户可选择 4K、2K、1080p 或 720p，Python 生成对应的 30 FPS H.264/AAC 代理视频并报告转码进度。
- Tool 结果保存为不可变 `VIDEO_METADATA`、`VIDEO_PROXY`、`SHOT_LIST` 和 `KEYFRAME_IMAGE` Artifact。
- Java 根据依赖表解锁所有就绪任务；上游失败时自动跳过下游，所有任务终态后结束 Workflow。
- Workflow 终态更新使用数据库行锁串行化；分发故障和可重试 Tool 故障采用有限指数退避，并由定时补偿扫描恢复 `READY`、`DISPATCHING`、`RETRY_WAIT` 和丢失的 Tool Execution。
- Java 接收回调，并通过定时轮询补偿丢失回调。
- 浏览器按素材动态展示 Task、Shot 和关键帧，并提供代理视频预览和下载。
- 一个 Workflow 可关联最多 20 个视频 Asset，并为每个素材并行展开独立分析分支。
- 历史项目可从数据库载入并恢复其素材列表，无需每次新建项目。
- 项目下的历史 Workflow 可按时间选择并恢复完整 Asset、Task、评分、高光和 Timeline 快照。
- 每个 Shot 输出清晰度、曝光、稳定性、构图与总质量分，并保留 Proxy、Shot List、关键帧血缘。
- 每个 Shot 输出 CLIP 语义标签：场景分类（15 类）、物体检测（15 类）、人物检测（有人/无人 + 人数 + 景别 + 活动姿态）。
- 跨素材 Ranking 保存评分分解、运动兴趣、时长适配、近重复/素材均衡/时间邻近惩罚、排名和原因码。
- Story Plan 使用固定 `HOOK -> INTRO -> JOURNEY -> CLIMAX -> ENDING` 模板，LLM 结合语义标签和数值评分进行 shot-to-beat 选择（支持 strict structured output：GPT-4o / Claude），失败时自动回退确定性算法。
- Highlight Selection 将已验证 Story Plan 编译为不可变高光集合。
- `TIMELINE` Artifact v1.1 使用受约束的结构化视频轨道、`CUT`/`FADE`/`CROSS_DISSOLVE` 转场、可选 `AUDIO` 轨（BGM）和 `SUBTITLE` 轨（SRT），不包含 Shell 或 FFmpeg 字符串。
- Timeline 在写入 Artifact 前校验画布、Clip 唯一性、Shot 边界、轨道连续性、时长一致性、转场类型/时长（CUT 0ms / FADE 200–2000ms / CROSS_DISSOLVE 200–2000ms）和转场白名单。
- 用户输入自然语言成片时长（如"快节奏15秒"、"1分钟慢旅行"），LLM 解析为目标毫秒数。
- 前端编辑 Story Plan：替换 shot、上下排序、锁定保护、添加镜头、删除镜头。
- 编辑后的方案保存到 `custom_story_plans` 表，支持自定义版本名。
- 一键 Apply & Render：Java 侧确定性构建 TIMELINE → 调度 video.render 渲染成片。
- 多版本管理：版本列表、Diff 对比（Added/Removed/Modified/Unchanged）、Load/Switch、Restore 回退、Delete 删除。
- LLM Story Proposal Schema v1.1：移除 beat 级别 `targetDurationMs`（编译器确定性计算），LLM 只需分配 `shotIds` + `reasonCodes`，提升采纳率。
- 可插拔 LLM Provider：DeepSeek（`json_object`）/ OpenAI GPT-4o（`json_schema` + `strict: true`）/ Claude（`tool_use` + `input_schema`），通过 `.env` 切换。
- FADE 转场（淡入 200–2000ms）+ CROSS_DISSOLVE 转场（交叉溶解 200–2000ms），启发式分配：首个 Clip FADE、段落边界 CROSS_DISSOLVE、段内 CUT。
- FFmpeg 滤镜图从纯 `concat` 升级为 `xfade`/`acrossfade`/`fade`/`afade` 链，支持转场叠加。
- `audio.bgm-select` Tool：段落角色 → 心情映射（HOOK→energetic, CLIMAX→epic 等），从 `runtime/bgm/` 曲库选择 BGM，渲染时 `amix` 混音（默认 volume 0.3）。
- `audio.source-transcribe` Tool：按素材代理视频生成源时间 `SOURCE_TRANSCRIPT`；`subtitle.compose` 再依据最终 Timeline 映射为 SRT，避免直接对成片二次 ASR/渲染。
- `video.render@1.1.0` 同时接受 `TIMELINE`、可选 `BGM_AUDIO` 和可选 `SUBTITLE_SRT`；无 BGM、无字幕或素材无音轨时均可降级。
- 渲染元数据记录 `hasBgm`、`hasSubtitles`，前端时间线展示转场类型指示器和 BGM/字幕状态条。

## 快速开始

当前状态与交接说明见 [第十阶段交接](docs/tenth-stage-handoff.md)。历史记录保留在 [第八阶段交接](docs/eighth-stage-handoff.md)、[第七阶段交接](docs/seventh-stage-handoff.md)、[第六阶段交接](docs/sixth-stage-handoff.md)、[第五阶段交接](docs/fifth-stage-handoff.md)、[第四阶段交接](docs/fourth-stage-handoff.md)、[第三阶段交接](docs/third-stage-handoff.md)、[第一条垂直链路](docs/first-vertical-slice.md) 和 [第二条垂直链路](docs/second-vertical-slice.md)。

```powershell
# 使用已有环境做无服务测试，不会安装或升级依赖
cd control-plane
mvn test

cd ..\tool-service
C:\software\Anaconda\envs\agent-video-pipeline\python.exe -m pytest -q

# Docker 配置可先做静态校验；实际启动步骤见第十阶段交接
cd ..
docker compose config
```

访问 `http://127.0.0.1:8080`。

## 项目结构

- `control-plane/`：Spring Boot 控制面与当前演示页面。
- `tool-service/`：FastAPI Tool Service、媒体分析、确定性 Shot 决策与 Timeline Tool。
- `contracts/`：共享协议与 OpenAPI。
- `docs/`：完整系统设计与开发说明。
- `scripts/`：本机启动、测试与数据库初始化脚本。

Java 控制面采用单 Maven 工程，模块职责文档集中在 [`docs/modules/control-plane`](docs/modules/control-plane/README.md)，不再保留容易与 Maven 子模块混淆的 `control-plane/modules` 目录。

Docker Compose 已提供 MySQL、Java Control Plane 和 Python Tool Service 的隔离运行方案，共享 `runtime` volume 以保证 Tool Artifact 可被 Java 下载。不要把密码、API Key、BGM 文件或本地缓存提交到 Git。
