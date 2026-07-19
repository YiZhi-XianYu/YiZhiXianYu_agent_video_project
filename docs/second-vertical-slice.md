# 第二条垂直链路：代理视频生成与两节点调度交接文档

> 文档日期：2026-07-19  
> 当前阶段：第二条垂直链路已完成，并使用真实 4K 视频通过端到端验证  
> 用途：第二阶段开发记录、本机运行说明、后续对话窗口交接入口

## 1. 新窗口先读这里

本项目是 **Agent-Driven Intelligent Video Production Pipeline**。第一阶段完成了上传视频、Java 调用 Python `video.probe`、保存元数据 Artifact 的单节点链路。

第二阶段在该基线上新增了真正的视频生成能力和基础任务依赖调度：

```text
浏览器创建项目并上传原视频
  -> Java 创建 VIDEO_PROXY_PIPELINE
  -> Task 1: video.probe@1.0.0
  -> Task 1 成功后解锁 Task 2
  -> Task 2: video.proxy-generate@1.0.0
  -> Python 使用 FFmpeg 按用户选择生成对应清晰度 MP4
  -> Python 报告转码进度并生成 VIDEO_PROXY Artifact
  -> Java 保存 Artifact 和生产血缘
  -> 浏览器通过 Java 内容接口预览或下载代理视频
```

当前仍没有 LLM Planner、通用 DAG、Shot Detection、Whisper、Timeline 或最终成片渲染。`VIDEO_PROXY` 是分析与预览用代理文件，不是最终剪辑结果。

## 2. 第二阶段已完成内容

### 2.1 Python Tool Service

新增 `video.proxy-generate@1.0.0`：

- 输入 `VIDEO_SOURCE`，输出 `VIDEO_PROXY`；
- 使用 FFmpeg `libx264` 和 AAC；
- 输出 `yuv420p` 并启用 `faststart`，保证浏览器兼容性；
- 支持 4K（3840×2160）、2K（2560×1440）、1080p（1920×1080）和 720p（1280×720）四档；
- 竖屏素材自动交换宽高上限，不放大低于目标规格的素材，并确保编码尺寸为偶数；
- 输出统一为 30 FPS；
- 四档分别使用 CRF 20、21、22、23 和 `veryfast` preset，在清晰度、体积与生成速度之间取平衡；
- 保留可选音轨，没有音轨时也能正常生成；
- 生成不可变文件、SHA-256、媒体信息和源素材引用；
- 解析 FFmpeg `-progress` 输出，将中间进度保存到 Tool Execution。

主要源码：

```text
tool-service/app/tools/video_proxy_generate.py
tool-service/app/registry/registry.py
tool-service/app/execution/service.py
tool-service/tests/test_video_proxy_generate.py
```

Tool Registry 当前包含：

```text
video.probe@1.0.0
video.proxy-generate@1.0.0
```

### 2.2 Java 两节点调度

Workflow 类型和节点关系：

```text
VIDEO_PROXY_PIPELINE

video_probe
  -> video_proxy_generate
```

实现内容：

- `TaskRun` 增加 `dependsOnTaskRunId`；
- 创建 Workflow 时同时持久化两个 Task；
- 仅根节点初始进入 `READY`；
- 前置任务成功后，后继任务从 `PENDING` 进入 `READY` 并在事务提交后分发；
- 第一个任务成功时 Workflow 进度更新为 50%，不会提前标记成功；
- 所有 Task 成功后 Workflow 才进入 `SUCCEEDED`；
- Tool Service 的中间进度由 Java 定时轮询并写入 Task；
- Tool 调用幂等键包含节点名、Task ID 和尝试次数。

这仍然是基础串行调度，不是完整通用 DAG：当前每个 Task 只有一个可选前置 Task，没有 WorkflowDefinition、边集合、条件节点、拓扑校验、并行就绪扫描或自动重试策略。

### 2.3 Artifact 内容访问

新增接口：

```text
GET /api/v1/artifacts/{artifactId}/content
```

本地开发模式下，该接口根据数据库中的 Artifact 定位 `file://` 文件，并以数据库记录的媒体类型返回内容。Spring `Resource` 响应支持 HTTP Range，已验证：

```text
普通请求: 200 OK
Range: bytes=0-1023
响应: 206 Partial Content
Accept-Ranges: bytes
Content-Type: video/mp4
```

因此浏览器可以播放、暂停和拖动代理视频进度条。页面不会直接获得本地文件系统绝对路径。

### 2.4 浏览器页面

页面仍由 Spring Boot 静态资源提供，尚未建立独立 Vue 工程。

当前页面支持：

1. 创建项目；
2. 上传视频；
3. 启动两节点代理工作流；
4. 分别查看 Probe 与 Proxy Task 状态、进度和尝试次数；
5. 查看原视频技术元数据；
6. 在 `<video>` 播放器中预览代理视频；
7. 下载代理 MP4。
8. 在启动 Workflow 前选择 4K、2K、1080p 或 720p 输出清晰度。

主要文件：

```text
control-plane/src/main/resources/static/index.html
control-plane/src/main/resources/static/styles.css
control-plane/src/main/resources/static/app.js
```

## 3. 当前 API

### Java API

```text
POST /api/v1/projects
GET  /api/v1/projects
POST /api/v1/projects/{projectId}/assets
GET  /api/v1/projects/{projectId}/assets
POST /api/v1/projects/{projectId}/video-proxy-runs
GET  /api/v1/workflow-runs/{workflowRunId}
GET  /api/v1/artifacts/{artifactId}/content
POST /internal/tool-callbacks
```

### Python Tool API

```text
GET  /api/v1/health
GET  /api/v1/tools
POST /api/v1/tool-executions
GET  /api/v1/tool-executions/{executionId}
```

## 4. 本机启动方式

### 4.1 Python Tool Service

```powershell
scripts\start-tool-service.cmd
```

成功标志：

```text
Uvicorn running on http://127.0.0.1:8090
```

### 4.2 Java Control Plane

在当前终端提供数据库凭据，不要写入仓库：

```powershell
$env:MYSQL_USER = "root"
$env:MYSQL_PASSWORD = "你的 MySQL 密码"
scripts\start-control-plane.cmd
```

成功标志：

```text
Tomcat started on port 8080
```

访问 `http://127.0.0.1:8080`。

## 5. 测试与验证

### 5.1 自动化测试

Java：

```powershell
scripts\run-java-tests.cmd
```

当前结果：`6 tests passed`。覆盖 Task 状态机、前置任务字段、清晰度枚举和 Java Tool 请求参数契约。

Python：

```powershell
scripts\run-python-tests.cmd
```

当前结果：`5 tests passed`。覆盖 ffprobe 归一化、四档代理 FFmpeg 参数、非法档位拒绝和进度换算。Pytest 在当前受限环境下可能提示无法创建 `.pytest_cache`，不影响测试结果。

### 5.2 真实端到端素材

测试输入：

```text
C:\Users\XRZ\Desktop\summer\input_zxf.mp4
```

输入信息：

| 属性 | 数值 |
|---|---:|
| 时长 | 24.009 秒 |
| 分辨率 | 3840 × 2160 |
| FPS | 30 |
| 视频编码 | H.264 |
| 音频编码 | AAC |
| 文件大小 | 74,240,317 bytes |
| 总码率 | 24,737,493 bit/s |

最终验证记录：

```text
WorkflowRun: f71e0400-04cf-41d3-81c7-bc5946ab282e
Workflow type: VIDEO_PROXY_PIPELINE
Workflow status: SUCCEEDED

video_probe: SUCCEEDED, attempt 1
video_proxy_generate: SUCCEEDED, attempt 1
```

观察到的代理任务进度：

```text
10% -> 25% -> 48% -> 73% -> 100%
```

输出代理视频：

| 属性 | 数值 |
|---|---:|
| Artifact 类型 | VIDEO_PROXY |
| 时长 | 24.009 秒 |
| 分辨率 | 1920 × 1080 |
| FPS | 30 |
| 视频编码 | H.264 / yuv420p |
| 音频编码 | AAC / 44.1 kHz / 2 声道 |
| 文件大小 | 4,827,220 bytes |
| 总码率 | 1,608,470 bit/s |

下载后的代理文件再次通过 ffprobe 校验，视频与音频轨均可读取。

### 5.3 清晰度参数端到端验证

使用同一个 4K 输入素材，从 Java API 分别创建 720p 和 2K Workflow，确认参数经过 WorkflowRun 持久化并传递到异步 Python Tool：

| 请求档位 | Workflow 保存值 | 实际输出 | FPS | 文件大小 | 状态 |
|---|---|---:|---:|---:|---|
| 720p | `720P` | 1280×720 | 30 | 2,355,517 bytes | SUCCEEDED |
| 2K | `2K` | 2560×1440 | 30 | 8,432,627 bytes | SUCCEEDED |

4K、2K、1080p 和 720p 均使用服务端白名单 profile。前端不能注入任意分辨率或 FFmpeg 参数。

### 5.4 浏览器验收

使用实际页面完成了创建项目、选择 `input_zxf.mp4`、上传、启动工作流和等待结束。

最终页面状态：

```text
Workflow: SUCCEEDED
Video Probe: SUCCEEDED
Proxy Generate: SUCCEEDED
代理预览: visible
HTMLVideoElement.readyState: 4
HTMLVideoElement.duration: 24.009002
前端错误区域: hidden
```

## 6. 当前技术债与边界

- 当前依赖模型只有单个 `dependsOnTaskRunId`，不能表达多前置节点；
- 当前 Workflow 是 Java 代码中固定创建的，不是持久化 WorkflowDefinition；
- 没有 DAG 环检测、拓扑排序、条件边和输入绑定解析；
- Python Tool Execution、幂等映射和进度仍保存在进程内存，服务重启后丢失；
- Python Worker 仍是进程内线程池；
- 进度依赖 Java 轮询，最终回调本身只携带终态；
- Artifact 仍使用本地 `file://`，Java 与 Python 必须在同一台机器；
- Artifact 内容接口当前无鉴权，适用于本地单用户开发；
- Hibernate 仍使用 `ddl-auto=update`，尚未引入 Flyway；
- 页面仍使用轮询，不是 WebSocket；
- 代理清晰度使用 4K、2K、1080p、720p 服务端白名单 profile；尚未建立独立的参数 Schema、管理员配置或自定义编码策略；
- 一个 Project 可以保存多条 Asset 记录，但当前 `WorkflowRun` 只有一个 `assetId`，单次工作流仍是单素材；
- 当前页面每次上传一个文件，并把最后上传的 Asset 作为工作流输入；尚未提供素材列表、批量上传和多选；
- 当前生成的是分析代理，不是最终 Timeline 渲染结果。

### 6.1 多素材现状与正确演进方式

当前并不是数据库只能保存一个视频：`assets` 表和 `GET /api/v1/projects/{projectId}/assets` 已经支持一个项目包含多条素材。限制出现在工作流输入层：

```text
WorkflowRun.assetId       # 当前只有一个
StartVideoProxyRequest    # 当前接收一个 assetId
Tool inputs.video         # 当前接收一个视频输入
前端 state.assetId        # 当前只保存最后上传项
```

未来旅行 Vlog 剪辑需要将模型升级为素材集合，而不能只给文件选择框增加 `multiple`：

1. 前端增加批量上传、素材列表、缩略图和多选；
2. Workflow 创建请求改为 `assetIds: []`，并校验全部素材属于同一项目；
3. 使用 Workflow 与 Asset 的关联表保存有序素材集合，不在单列中塞 JSON；
4. 为每个素材创建独立的 Probe、Proxy、Shot Detection 等分析分支；
5. 在 Video Knowledge 或 Timeline 阶段合并跨素材结果；
6. 页面根据实际 Task 列表动态渲染，而不是固定两个节点。

因此当前“单素材代理生成”适合作为阶段基线；第三阶段通用 DAG 与并行分支完成时，应同时升级为真正的多素材工作流。

## 7. 建议的第三阶段

下一阶段应从“固定串行链路”升级为“小型通用 DAG 执行内核”，并加入第一批并行视频理解 Tool。

推荐目标：

```text
video.probe
  -> video.proxy-generate
       -> video.shot-detect
       -> audio.transcribe
       -> vision.quality-score
```

建议拆分：

1. 定义最小 `WorkflowDefinition`、节点、边和输入绑定结构；
2. 实现确定性 DAG Validator：节点唯一、引用存在、无环；
3. 将单前置字段升级为边或依赖表；
4. Scheduler 扫描全部依赖成功的 `PENDING` Task，并允许并行进入 `READY`；
5. 实现至少一个真实分析 Tool，优先 `video.shot-detect`；
6. 为并行调度、失败传播和幂等补测试；
7. 页面从固定两个节点升级为根据 API 数据渲染节点列表。

大模型 Planner 仍不建议在第三阶段最开始接入。先保证 WorkflowDefinition 和 Validator 可以稳定运行，再让 LLM 只生成受约束的声明式计划。

## 8. 新对话窗口交接提示词

```text
这是 Agent-Driven Intelligent Video Production Pipeline 项目。

开始工作前必须阅读：
1. docs/second-vertical-slice.md
2. docs/first-vertical-slice.md
3. docs/Agent-Driven智能视频制作流水线-系统设计文档.md
4. 当前任务目录 README
5. docs/modules/control-plane/README.md（修改 Java 时）

当前已真实打通两节点链路：
浏览器 -> Java -> video.probe -> video.proxy-generate -> VIDEO_PROXY -> Java 内容接口 -> 浏览器播放器。

input_zxf.mp4 的真实验证结果：4K 24.009 秒原片可以按用户选择生成不同清晰度的 30 FPS H.264/AAC 代理 MP4；已真实验证 720p、1080p 和 2K，Workflow 与两个 Task 均 SUCCEEDED，转码中间进度可见。

当前一个项目可保存多条 Asset，但单次 Workflow 只接受一个 assetId。多素材剪辑必须在下一阶段配合 WorkflowDefinition、并行 DAG 和 Workflow-Asset 关联一起实现，不能只改前端文件多选。

当前依赖调度只有一个可选前置 Task，不是通用 DAG。下一阶段优先实现最小 WorkflowDefinition、DAG Validator、并行就绪调度和 Shot Detection，不要直接接入 LLM Planner。

开发约束：
- Java 与 Python 保持 HTTP Tool API 解耦；
- Java 不直接执行 Python 或 FFmpeg；
- Artifact 不可变并保留哈希与生产血缘；
- 不把密码、密钥或本地凭据写入仓库；
- 修改前检查 Git 状态并保留用户已有改动；
- 完成后运行 Java/Python 测试、真实 E2E，并更新交接文档。
```

## 9. 相关文档

- [第一阶段历史记录](first-vertical-slice.md)
- [完整系统设计](Agent-Driven智能视频制作流水线-系统设计文档.md)
- [Java 控制面模块映射](modules/control-plane/README.md)
- [Proxy Generate Tool](../tool-service/tools/proxy-generate/README.md)
- [项目根说明](../README.md)
