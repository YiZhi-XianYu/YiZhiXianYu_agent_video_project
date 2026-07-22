# Agent-Driven Intelligent Video Production Pipeline

Agent 驱动的智能视频制作流水线暑期实训项目。

当前已完成第七阶段：用户在浏览器中编辑 Story Plan 的 shot 分配（替换、排序、锁定、添加、删除），保存多版本方案（自定义版本名），版本 Diff 对比与回退，一键 Apply & Render 生成成片，并支持自然语言输入成片时长（如"快节奏15秒"）。Java 根据受约束的 `WorkflowDefinition` v4（11 节点）为每个素材展开 `video.probe -> video.proxy-generate -> video.shot-detect -> (vision.quality-score + vision.scene-classify + vision.object-detect + vision.person-detect)` 分支，再以工作流级节点汇聚执行 `decision.shot-rank (+ 3 个视觉 Tool) -> planning.story-template -> decision.highlight-select -> timeline.compose`。Python 使用 FFmpeg 生成代理视频、镜头列表和关键帧，以 CLIP ViT-B-32 零样本模型输出语义标签（场景/物体/人物），并以确定性 Tool 输出质量指标、跨素材 Ranking、五段式 Story Plan（LLM-assisted with semantic context）、高光集合与声明式 Timeline。项目、素材集合、Workflow、Task 依赖、Tool Execution、Artifact 和 Custom Story Plan 状态保存到 MySQL。

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
- Story Plan 使用固定 `HOOK -> INTRO -> JOURNEY -> CLIMAX -> ENDING` 模板，LLM 结合语义标签和数值评分进行 shot-to-beat 选择，失败时自动回退确定性算法。
- Highlight Selection 将已验证 Story Plan 编译为不可变高光集合。
- `TIMELINE` Artifact 使用受约束的结构化视频轨道和 `CUT` 转场，不包含 Shell 或 FFmpeg 字符串。
- Timeline 在写入 Artifact 前校验画布、Clip 唯一性、Shot 边界、轨道连续性、时长一致性和转场白名单。
- 用户输入自然语言成片时长（如"快节奏15秒"、"1分钟慢旅行"），LLM 解析为目标毫秒数。
- 前端编辑 Story Plan：替换 shot、上下排序、锁定保护、添加镜头、删除镜头。
- 编辑后的方案保存到 `custom_story_plans` 表，支持自定义版本名。
- 一键 Apply & Render：Java 侧确定性构建 TIMELINE → 调度 video.render 渲染成片。
- 多版本管理：版本列表、Diff 对比（Added/Removed/Modified/Unchanged）、Load/Switch、Restore 回退、Delete 删除。

## 快速开始

当前本机运行与交接说明见 [第七阶段交接](docs/seventh-stage-handoff.md)。历史记录保留在 [第六阶段交接](docs/sixth-stage-handoff.md)、[第五阶段交接](docs/fifth-stage-handoff.md)、[第四阶段交接](docs/fourth-stage-handoff.md)、[第三阶段交接](docs/third-stage-handoff.md)、[第一条垂直链路](docs/first-vertical-slice.md) 和 [第二条垂直链路](docs/second-vertical-slice.md)。

```powershell
# 终端 1
scripts\start-tool-service.cmd

# 终端 2
$env:MYSQL_USER = "root"
$env:MYSQL_PASSWORD = "你的 MySQL 密码"
scripts\start-control-plane.cmd
```

访问 `http://127.0.0.1:8080`。

## 项目结构

- `control-plane/`：Spring Boot 控制面与当前演示页面。
- `tool-service/`：FastAPI Tool Service、媒体分析、确定性 Shot 决策与 Timeline Tool。
- `contracts/`：共享协议与 OpenAPI。
- `docs/`：完整系统设计与开发说明。
- `scripts/`：本机启动、测试与数据库初始化脚本。

Java 控制面采用单 Maven 工程，模块职责文档集中在 [`docs/modules/control-plane`](docs/modules/control-plane/README.md)，不再保留容易与 Maven 子模块混淆的 `control-plane/modules` 目录。

当前链路不使用 Docker。MySQL 使用本机服务，Python 使用项目专用 Conda 环境，本地目录暂时代替对象存储。
