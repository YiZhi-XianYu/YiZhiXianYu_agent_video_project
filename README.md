# Agent-Driven Intelligent Video Production Pipeline

Agent 驱动的智能视频制作流水线暑期实训项目。

当前已完成第三条本机开发模式的垂直链路：浏览器可批量上传多个视频，Java 根据受约束的 `WorkflowDefinition` 为每个素材展开 `video.probe -> video.proxy-generate -> video.shot-detect` DAG，Python 使用 FFmpeg 生成代理视频、镜头列表和关键帧。项目、素材集合、Workflow、Task 依赖、Tool Execution 和 Artifact 状态保存到 MySQL。

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
- Java 接收回调，并通过定时轮询补偿丢失回调。
- 浏览器按素材动态展示 Task、Shot 和关键帧，并提供代理视频预览和下载。
- 一个 Workflow 可关联最多 20 个视频 Asset，并为每个素材并行展开独立分析分支。

## 快速开始

当前本机运行与交接说明见 [第三阶段交接](docs/third-stage-handoff.md)。前两阶段历史记录保留在 [第一条垂直链路](docs/first-vertical-slice.md) 和 [第二条垂直链路](docs/second-vertical-slice.md)。

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
- `tool-service/`：FastAPI Tool Service、Probe、Proxy Generate 与 Shot Detection Tool。
- `contracts/`：共享协议与 OpenAPI。
- `docs/`：完整系统设计与开发说明。
- `scripts/`：本机启动、测试与数据库初始化脚本。

Java 控制面采用单 Maven 工程，模块职责文档集中在 [`docs/modules/control-plane`](docs/modules/control-plane/README.md)，不再保留容易与 Maven 子模块混淆的 `control-plane/modules` 目录。

当前链路不使用 Docker。MySQL 使用本机服务，Python 使用项目专用 Conda 环境，本地目录暂时代替对象存储。
