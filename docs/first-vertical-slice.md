# 第一条垂直链路：Day 1 进度与交接文档

> 文档日期：2026-07-16  
> 当前阶段：第一条垂直链路已完成并通过真实端到端验证  
> 用途：第一天开发记录、本机运行说明、新对话窗口交接入口

> 历史说明：本文记录 Day 1 基线。第二阶段已经完成，当前开发入口请阅读 [`second-vertical-slice.md`](second-vertical-slice.md)。

## 1. 新窗口先读这里

本项目是 **Agent-Driven Intelligent Video Production Pipeline**。完整目标是通过大模型理解用户意图、生成 Workflow DAG、调用独立 AI Tool 并最终渲染视频。

目前只完成了第一条基础链路：上传视频并分析技术元数据。当前**没有剪辑、修改或生成新视频**，也还没有接入大模型。

已验证流程：

```text
浏览器创建项目
  -> 上传视频到 Java 本地 Storage Adapter
  -> Java 在 MySQL 创建 WorkflowRun 和 TaskRun
  -> Java 通过 HTTP 创建 Python Tool Execution
  -> Python 异步调用 ffprobe
  -> Python 生成 VIDEO_METADATA Artifact
  -> Python 回调 Java，Java同时定时轮询补偿
  -> Java 更新 MySQL 中的 Task/Workflow 状态
  -> 浏览器轮询 Java API 并展示状态与视频元数据
```

第一条成功验证记录：

```text
WorkflowRun: SUCCEEDED
TaskRun: SUCCEEDED
Task attempt: 1
Artifact: VIDEO_METADATA
测试视频时长: 2000 ms
分辨率: 640 x 360
FPS: 30
视频编码: h264
音频编码: aac
```

## 2. Day 1 已完成内容

### 2.1 工程和环境

- 建立 Spring Boot 3.4.4 单 Maven 工程 `control-plane/`。
- Maven 编译目标为 Java 21，已确认生成的 class major version 为 `65`。
- 在 VS Code 中配置 Java 21。
- 建立项目专用 Conda 环境：

```text
C:\software\Anaconda\envs\agent-video-pipeline
```

- Conda 环境使用 Python 3.12，并安装 FastAPI、Uvicorn、HTTPX、Pydantic 和 Pytest。
- 使用本机 MySQL 8.0，服务名为 `MySQL80`。
- 使用本机 FFmpeg 8.1.1 和 ffprobe。
- 当前不使用 Docker、Redis、MinIO 或独立 Vue 构建链。

### 2.2 Java Control Plane

已实现：

- Spring Boot 应用启动和本机配置。
- 项目创建与查询。
- 视频 Multipart 上传。
- 本地文件 Storage Adapter。
- Asset 元数据和 SHA-256 内容哈希。
- 固定单节点工作流 `INITIAL_VIDEO_PROBE`。
- WorkflowRun、TaskRun、ToolExecution 和 Artifact 持久化。
- Task 状态机：

```text
PENDING -> READY -> DISPATCHING -> RUNNING -> SUCCEEDED / FAILED
```

- 事务提交后异步分发任务，避免回调早于数据库提交。
- Java 21 标准 `HttpClient` 调用 Python Tool Service。
- Tool Client 强制使用 HTTP/1.1。
- Tool 调用幂等键。
- Python 完成回调入口。
- 对 QUEUED/RUNNING Tool Execution 的定时轮询补偿。
- 重复终态回调的基础幂等处理。
- 统一 API 错误响应。

主要源码：

```text
control-plane/src/main/java/com/yizhixianyu/agentvideo/
  AgentVideoControlPlaneApplication.java
  api/
  project/
  asset/
  artifact/
  execution/
  storage/
  toolclient/
```

### 2.3 Python Tool Service

已实现：

- FastAPI 应用和健康接口。
- Tool Manifest 列表接口。
- 异步 Tool Execution 创建接口，返回 `202 Accepted`。
- Tool Execution 状态查询。
- 基于幂等键的进程内去重。
- 后台线程池执行 Tool。
- 标准执行状态与错误结构。
- 执行结束后回调 Java。
- Java 回调失败时允许 Java 轮询恢复。
- `video.probe@1.0.0` Tool。
- ffprobe 原始结果归一化。
- 生成不可变 JSON Artifact，并计算 SHA-256。

`video.probe` 当前输出：

- 视频时长；
- 宽度和高度；
- FPS；
- 容器格式；
- 文件大小与码率；
- 视频编码；
- 是否存在音轨；
- 音频编码、采样率和声道数。

主要源码：

```text
tool-service/app/
  main.py
  api/routes.py
  core/config.py
  core/models.py
  execution/service.py
  registry/registry.py
  tools/video_probe.py
```

### 2.4 MySQL 持久化

数据库名称：

```text
agent-video-pipeline
```

当前 Hibernate 开发配置会自动创建或更新以下表：

```text
projects
assets
workflow_runs
task_runs
tool_executions
artifacts
```

截至 Day 1 交接时，数据库中存在开发和故障排查产生的测试记录：

| 数据 | 数量 |
|---|---:|
| Project | 5 |
| Asset | 6 |
| WorkflowRun | 5 |
| TaskRun | 5 |
| ToolExecution | 2 |
| Artifact | 2 |
| SUCCEEDED Workflow | 2 |
| FAILED Workflow | 3 |

其中 3 条失败 Workflow 是修复 HTTP 协议问题前的测试记录，不代表当前代码仍然失败。暂未删除这些记录，便于回顾状态和错误路径。后续可以增加安全的开发数据清理脚本。

### 2.5 浏览器页面

当前页面由 Spring Boot 静态资源直接提供，不是 Vue 工程。

已实现操作：

1. 创建项目；
2. 选择并上传视频；
3. 启动 `video.probe` 工作流；
4. 查看 Workflow 与 Task 状态；
5. 查看进度、尝试次数和错误；
6. 查看视频元数据 Artifact。

页面地址：

```text
http://127.0.0.1:8080
```

静态资源：

```text
control-plane/src/main/resources/static/
  index.html
  styles.css
  app.js
```

## 3. 当前 API

### Java API

```text
POST /api/v1/projects
GET  /api/v1/projects
POST /api/v1/projects/{projectId}/assets
GET  /api/v1/projects/{projectId}/assets
POST /api/v1/projects/{projectId}/video-probe-runs
GET  /api/v1/workflow-runs/{workflowRunId}
POST /internal/tool-callbacks
```

### Python Tool API

```text
GET  /api/v1/health
GET  /api/v1/tools
POST /api/v1/tool-executions
GET  /api/v1/tool-executions/{executionId}
```

共享协议草案：

```text
contracts/openapi/tool-service.yaml
```

## 4. 本机启动方式

### 4.1 启动 Python Tool Service

双击或在终端运行：

```powershell
scripts\start-tool-service.cmd
```

成功标志：

```text
Uvicorn running on http://127.0.0.1:8090
```

保持该窗口打开。

### 4.2 启动 Java Control Plane

双击或在第二个终端运行：

```powershell
scripts\start-control-plane.cmd
```

若当前终端没有 `MYSQL_PASSWORD`，脚本会询问密码。密码只进入当前进程环境，不应写入源码、配置或文档。

也可以先设置环境变量：

```powershell
$env:MYSQL_USER = "root"
$env:MYSQL_PASSWORD = "你的 MySQL 密码"
scripts\start-control-plane.cmd
```

成功标志：

```text
Tomcat started on port 8080
```

### 4.3 端口

| 服务 | 地址 |
|---|---|
| Java Control Plane | `127.0.0.1:8080` |
| Python Tool Service | `127.0.0.1:8090` |
| MySQL | `127.0.0.1:3306` |

同一个服务不能重复启动。启动脚本会检查 8080/8090 是否已经被占用，并在失败时保留窗口显示原因。

## 5. 测试与验证

### Python 测试

```powershell
scripts\run-python-tests.cmd
```

Day 1 结果：

```text
1 passed
```

覆盖 ffprobe 结果归一化。

### Java 测试

```powershell
scripts\run-java-tests.cmd
```

Day 1 结果：

```text
2 tests passed
```

覆盖：

- Task 状态机正常路径；
- Java Tool 请求 JSON 契约序列化。

### 真实端到端验证

已经使用 FFmpeg 生成 2 秒测试视频，真实完成：

```text
HTTP API
  -> Java
  -> MySQL
  -> Python FastAPI
  -> ffprobe
  -> VIDEO_METADATA Artifact
  -> Java 回调/查询
  -> MySQL SUCCEEDED
```

MySQL 中确认：

```text
WorkflowRun status = SUCCEEDED, progress = 100
TaskRun status = SUCCEEDED, progress = 100, attempt = 1
Artifact type = VIDEO_METADATA
Artifact media type = application/json
```

## 6. Day 1 关键问题与解决方案

### 6.1 CMD 双击后立即退出

原因：

- Java 脚本没有获得 `MYSQL_PASSWORD`；
- Python 原脚本使用 `uvicorn --reload`，Windows 下重载进程触发权限问题；
- 测试服务未停止时端口被占用。

解决：

- Java 脚本在缺少密码时主动询问；
- Python 启动脚本移除 `--reload`；
- 两个脚本增加端口检测和失败 `pause`。

### 6.2 Java 调用 FastAPI 返回 422 body missing

第一次修复尝试排除了 JSON 序列化问题。最终根因是 Java 标准 HttpClient 对明文 HTTP 尝试 h2c 升级，Uvicorn 日志显示：

```text
Unsupported upgrade request
Invalid HTTP request received
```

请求体随后被 FastAPI 解析为空。

最终解决方案：

```java
HttpClient.newBuilder()
    .version(HttpClient.Version.HTTP_1_1)
```

强制 HTTP/1.1 后端到端链路成功。

### 6.3 `control-plane/modules` 容易产生误解

原目录只有架构 README，不参与 Maven 构建，容易被误解为 Maven 多模块工程。

已完成整理：

- 删除 `control-plane/modules/`；
- 说明文档迁移到 `docs/modules/control-plane/`；
- Java 保持单 Maven 工程；
- 模块边界通过 Java package 实现。

## 7. 目录现状

```text
WwDa3B884n8dj/
  control-plane/          # 实际 Spring Boot/Maven 工程
    pom.xml
    src/main/java/
    src/main/resources/
    src/test/java/
  tool-service/           # 实际 FastAPI Tool Service
    app/
    tests/
    requirements.txt
    environment.yml
  contracts/              # 共享契约
  docs/                   # 设计、进度与模块说明
    first-vertical-slice.md
    modules/control-plane/
  scripts/                # 本机启动、测试和建库脚本
  web-app/                # 未来独立前端，目前尚未初始化
  runtime/                # 本机运行和测试产物，已被 Git 忽略
```

逻辑模块与实际 Java 包映射见：

```text
docs/modules/control-plane/README.md
```

## 8. 当前明确未实现

以下功能仍处于设计或占位阶段：

- 大模型 Intent Parser；
- LLM Workflow Planner；
- 动态多节点 DAG；
- Tool Registry 持久化和健康管理；
- 任务自动重试、暂停、恢复和取消；
- Redis；
- MinIO/S3；
- WebSocket 实时推送；
- 独立 Vue 前端；
- 视频代理生成；
- Shot Detection；
- Whisper 转写；
- OCR、CLIP、Embedding 和 Video Knowledge；
- Shot Ranking 和 Highlight Detection；
- Story、Subtitle、Transition、Music 和 Effect Planner；
- Timeline；
- FFmpeg 最终视频渲染；
- 用户登录、权限和项目隔离；
- 回调签名与重放保护。

因此当前系统只会上传原视频并分析其技术信息，**不会修改或生成新视频**。

## 9. 当前技术债与风险

- `spring.jpa.hibernate.ddl-auto=update` 只适合当前开发阶段，后续应改为 Flyway 迁移。
- 上传目前经过 Java Multipart，中大型视频后续应改为对象存储签名直传。
- Artifact 当前使用本地 `file://` URI，Java 和 Python 必须运行在同一台机器。
- Python Tool Execution 状态和幂等映射当前仅在内存中，Python 重启后会丢失。
- Python Worker 当前是进程内线程池，不具备独立资源隔离。
- Java 当前采用前端轮询，不是 WebSocket。
- Tool 回调当前没有签名验证。
- Tool Execution 轮询只处理基础终态，没有租约和超时扫描。
- 当前固定 Workflow 不是通用 WorkflowDefinition/DAG。
- `.vscode/settings.json` 绑定了本机 Conda 绝对路径，换机器时需要调整。

## 10. 建议的下一步

推荐下一阶段实现 `video.proxy-generate@1.0.0`，让系统第一次真正生成一个新视频 Artifact。

目标链路：

```text
上传原视频
  -> video.probe
  -> video.proxy-generate
  -> 生成低分辨率代理 MP4
  -> Java 保存 VIDEO_PROXY Artifact
  -> 页面预览或下载代理视频
```

这一阶段可以验证：

- 两节点依赖；
- 基础 DAG 串行调度；
- FFmpeg 视频编码；
- 大型 Artifact 生命周期；
- 视频处理进度；
- 生成视频的预览和下载。

完成代理视频后，再增加 Shot Detection、Transcription 和 Quality Score 的并行分析分支。大模型 Planner 建议在通用 DAG Validator 和至少两个 Tool 稳定后接入。

## 11. 新对话窗口交接提示词

可以将下面内容直接发送给新窗口：

```text
这是 Agent-Driven Intelligent Video Production Pipeline 项目。

开始工作前必须阅读：
1. docs/first-vertical-slice.md
2. docs/Agent-Driven智能视频制作流水线-系统设计文档.md
3. 当前任务对应目录的 README.md
4. docs/modules/control-plane/README.md（如果修改 Java 控制面）

当前第一条链路已经真实打通：
浏览器 -> Java -> MySQL -> Python FastAPI -> ffprobe -> Artifact -> Java。

当前没有 LLM、通用 DAG、Shot 分析或视频渲染，不要把设计中的目标态误认为已实现。

开发要求：
- 保持 Java 与 Python 通过 HTTP Tool API 解耦；
- Java 不直接启动 Python 脚本；
- 大模型输出未来必须经过确定性校验；
- 不把密码或密钥写入仓库；
- 修改前先检查 Git 状态，保留用户已有变更；
- 完成后运行相应测试并更新本交接文档。
```

## 12. 相关文档

- [完整系统设计](Agent-Driven智能视频制作流水线-系统设计文档.md)
- [Java 控制面模块映射](modules/control-plane/README.md)
- [项目根说明](../README.md)
