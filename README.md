# Agent-Driven Intelligent Video Production Pipeline

Agent 驱动的智能视频制作流水线暑期实训项目。

当前已完成第一条本机开发模式的垂直链路：Java 控制面通过 HTTP 调用独立 Python Tool Service，使用 `ffprobe` 分析用户上传的视频，并将项目、素材、Workflow、Task、Tool Execution 和 Artifact 状态保存到 MySQL。

## 当前功能

- 创建视频项目。
- 上传本地视频素材。
- 创建固定单节点 `INITIAL_VIDEO_PROBE` Workflow。
- 执行 Task 状态流转：`PENDING -> READY -> DISPATCHING -> RUNNING -> SUCCEEDED/FAILED`。
- Java 使用异步 HTTP 调用 `video.probe@1.0.0`，不直接执行 Python 脚本。
- Python FastAPI Tool Service 支持 Manifest、异步 Execution、幂等键、状态查询和回调。
- Python 调用 ffprobe，输出时长、分辨率、FPS、视频/音频编码和文件大小。
- Tool 结果保存为不可变 `VIDEO_METADATA` Artifact。
- Java 接收回调，并通过定时轮询补偿丢失回调。
- 浏览器展示 Workflow、Task 状态和视频元数据。

## 快速开始

完整本机运行说明见 [第一条垂直链路](docs/first-vertical-slice.md)。

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
- `tool-service/`：FastAPI Tool Service 与 `video.probe`。
- `contracts/`：共享协议与 OpenAPI。
- `docs/`：完整系统设计与开发说明。
- `scripts/`：本机启动、测试与数据库初始化脚本。

Java 控制面采用单 Maven 工程，模块职责文档集中在 [`docs/modules/control-plane`](docs/modules/control-plane/README.md)，不再保留容易与 Maven 子模块混淆的 `control-plane/modules` 目录。

当前链路不使用 Docker。MySQL 使用本机服务，Python 使用项目专用 Conda 环境，本地目录暂时代替对象存储。
