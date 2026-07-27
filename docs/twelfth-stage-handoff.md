# 第十二阶段交接：服务器部署与资源感知执行

> 日期：2026-07-27  
> 范围：阿里云 ECS 生产部署、Python 执行稳定性、模型下载与 Render OOM 收口  
> 边界：Java 与 Python 继续通过 HTTP Tool API 解耦；Artifact 不可变与血缘规则未改变

## 1. 阶段结果

第十二阶段完成了首个阿里云 ECS 部署，并针对 4 核 8 GiB、40 GiB
实例暴露出的真实问题完成代码级修复：

1. 新增 Caddy、生产 Compose、环境变量模板和阿里云部署文档；
2. Debian、PyPI 和 Hugging Face 镜像源改为可配置参数；
3. Python Execution Store 继续使用持久化 SQLite；
4. 执行器从固定 4 线程升级为资源感知调度；
5. CLIP/Whisper 与最终 Render 不再同时占用重资源；
6. 模型任务结束后释放进程内引用并调用 `malloc_trim(0)`；
7. Whisper 转写按 segment 时间持续上报进度；
8. 连续进程崩溃的执行会收敛为可重试失败，不再无限恢复；
9. Render 限制 FFmpeg 滤镜和编码线程，并保留退出码与 stderr；
10. Tool Service 生产镜像正式包含共享 LLM Contract Schema。

## 2. 线上故障证据

首次真实工作流中，CLIP 和 Whisper 模型下载后，Python 容器内存约为
2.4 GiB。最终 Render 启动 FFmpeg 后，内核连续记录：

```text
Memory cgroup out of memory
Killed process (ffmpeg)
ffmpeg anon-rss about 2.7-2.9 GiB
```

旧容器限制为 5 GiB，因此常驻模型与 FFmpeg 相加超过 cgroup 上限。
该故障不是滤镜语法错误，也不是工作流死锁。

同时确认了三个部署问题：

- `deb.debian.org` 在 ECS 上连接超时，阿里云镜像约 16.9 MiB/s；
- `files.pythonhosted.org` 和 Hugging Face 首次下载不稳定；
- 原 Tool Service 镜像缺少 `/contracts/llm`，导致 Story Plan Schema 警告。

## 3. 资源调度模型

Tool Manifest 新增可选 `resourceGroup`，旧 Tool 可由 `resourceClass`
安全推导：

| 分组 | 示例 | 权重 | 默认并发 |
|---|---|---:|---:|
| LIGHT | Ranking、Story Plan、Timeline | 0 | 3 |
| MEDIA | Probe、Proxy、Shot Detect | 1 | 2 |
| MODEL | VLM/CLIP、Whisper | 2 | 1 |
| RENDER | 最终视频渲染 | 2 | 1 |

重资源总容量为 2。两个 MEDIA 可以并行；MODEL 或 RENDER 各自占满容量。
排队任务保持 `QUEUED`，拿到资源后才进入 `RUNNING`。

## 4. 崩溃恢复

SQLite 中的 `QUEUED` 任务在进程重启后继续排队。`RUNNING` 任务允许用
同一 execution ID 恢复一次；如果恢复后再次随进程退出，下一次启动会
写入：

```text
code = EXECUTION_RECOVERY_EXHAUSTED
retryable = true
```

Java 可按既有重试策略继续收敛，不会无限轮询僵尸执行。

## 5. Render 改进

- `-filter_threads 1`
- `-filter_complex_threads 1`
- x264 `-threads 2`
- stderr 使用文件流，避免未读取 pipe 堵塞；
- 失败保存退出码、信号和最多 8 KiB 原始诊断；
- signal 9 无 stderr 时明确提示可能为内存或容器限制。
- Tool Service 镜像安装 Noto CJK 字体，字幕烧录明确使用
  `Noto Sans CJK SC`，避免中文被渲染成方框。

Timeline、BGM、字幕输入契约和 `video.render@1.1.0` 版本未改变。

## 6. 生产默认配置

```dotenv
TOOL_MAX_WORKERS=4
TOOL_LIGHT_LIMIT=3
TOOL_MEDIA_LIMIT=2
TOOL_MODEL_LIMIT=1
TOOL_RENDER_LIMIT=1
TOOL_HEAVY_LIMIT=2
TOOL_MAX_RECOVERIES=1
RELEASE_MODELS_AFTER_EXECUTION=true
```

模型缓存仍挂载到 `/opt/agent-video/data/huggingface`，释放内存不会触发
重新下载。

## 7. 验证结果

- Python 完整测试：71 passed；
- Java Maven 测试：通过，退出码 0；
- 生产 Compose 静态校验：通过；
- 生产服务清单：mysql、control-plane、caddy、tool-service；
- Tool Service 生产镜像：实际构建通过；
- 镜像自检：Manifest 资源组与两个 LLM Schema 均存在；
- Debian/PyPI 阿里云镜像：实际构建下载成功；
- 真实 ECS 故障定位：确认 Render 为 memory cgroup OOM。

## 8. 尚需服务器验收

代码提交并推送后，服务器需要恢复临时手改、拉取正式提交、重建
Tool Service，然后用原最小素材重新发起全新 Workflow，确认：

1. VLM 和转写依次执行且进度持续变化；
2. 转写完成后 Python 容器内存显著回落；
3. Render 不再出现内核 OOM；
4. 成片可预览和下载；
5. 容器重启后执行记录仍可恢复；
6. 日志不再出现 LLM Schema 缺失警告。
7. 中文语音生成的字幕能显示中文，而不是方框字形。

在完成这次真实服务器 Workflow 前，第十二阶段代码已完成，但生产端到端
验收仍应标记为待验证。
