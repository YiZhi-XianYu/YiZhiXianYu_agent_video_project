# 第十四阶段交接：可观测性与 Artifact Storage 基础

更新时间：2026-08-03

## 已完成

- Control Plane 增加 Spring Boot Actuator、Micrometer Prometheus Registry，暴露 `/actuator/health`、`/actuator/metrics`、`/actuator/prometheus`。
- 增加工作流启动/终态、任务派发/终态/耗时等低基数指标。
- Tool Service 增加 Prometheus `/metrics`，记录执行结果、耗时、队列深度、活跃执行、资源组占用和回调结果。
- 新增 `ArtifactStorage` 统一接口与 `ArtifactStorageRouter`，Local Provider 保持 `file:` URI 兼容。
- 素材上传、Artifact/Timeline/Story Plan/字幕/BGM JSON 以及用户上传 BGM 已通过统一存储边界写入。
- Artifact/素材内容 API 不再在 Controller 中直接解析 `file:`，改由 Storage Provider 提供资源。
- 新增 `scripts/stage14-k6.js` 基线 smoke 脚本及 `docs/stage14-baseline-report-template.md` 报告模板。

## 当前边界

- 默认 `ARTIFACT_STORAGE_PROVIDER=local`，不需要云凭据即可运行。
- 已接入阿里云 OSS Java SDK 与 Python `oss2`：Control Plane 支持对象上传和私有对象预签名读取，Tool Service 支持 OSS 输入物化到本地 workspace、输出发布回 OSS。
- 已用当前北京 Bucket 做只读鉴权验证，返回 Bucket 信息 `200`；Control Plane 与 Tool Service 健康检查均通过。尚未执行写入型测试，因此仍需在测试对象前缀下完成上传、Range 和失败恢复验证后再宣布生产可用。
- 视频/Artifact 内容接口已改为：先由 Control Plane 完成项目权限校验，再返回 OSS 短时预签名 URL；浏览器直接从 OSS 获取媒体，避免 Java 代理整段视频和丢失 Range 请求。
- 存储策略已收窄：视频、音频和图片二进制进入 OSS；`application/json`、SRT、Manifest 等结构化 Artifact 保持共享本地卷，避免镜头列表等 JSON 因跨域预签名读取失败而变成空数据。
- 本阶段没有引入 Redis、RabbitMQ 或运行中 Replan。

## 验证

- `python -m compileall tool-service/app` 已通过。
- Java Maven 构建未能在当前机器执行：IDEA 自带 `mvn.cmd` 被系统拒绝访问；应在 CI 或可执行 Maven 环境重新运行 `mvn -DskipTests compile` 与测试。

## 问题记录与解决方案

### OSS 上传和预览延迟很高

最初的实现由 Control Plane 创建 `UrlResource` 并代理 OSS 视频流。大视频的首字节、完整响应和浏览器 Range 请求都经过 Java 服务，导致首帧慢、拖动不工作，部分浏览器无法显示。

解决方案是保留 Control Plane 的项目权限校验，但对 `oss://` 内容返回短时预签名 URL（307 重定向），浏览器直接从 OSS 请求媒体。OSS 负责 Range、断点和分段读取，Control Plane 不再搬运视频字节。

### 预览仍可能显示失败

OSS Bucket 必须配置 CORS，允许正式前端域名的 `GET`、`HEAD`、`OPTIONS`，允许请求头 `Range`，并暴露响应头 `Content-Range`、`Accept-Ranges`、`Content-Length`、`ETag`。禁止使用 `*` 配合携带凭据的请求；签名 URL 本身不需要浏览器 Cookie。

### 镜头预览返回空数据

此前 Tool Service 将所有输出（包括 `SHOT_LIST` JSON、字幕和关键帧）发布到 OSS，Control Plane 又把所有 `oss://` URI 重定向到签名 URL。前端通过同源 `fetch` 读取镜头列表 JSON 时会受到 Bucket CORS/响应处理影响，导致空数据并阻断后续节点。

现在只有视频/音频/图片 MIME 类型会发布到 OSS；JSON/SRT/Manifest 继续走共享本地卷。对历史已经存在的 OSS JSON，内容接口仍回到 Control Plane 同源资源响应；二进制媒体则通过签名 URL 直连 OSS。

音频和图片已正式纳入 OSS：BGM、关键帧图片等二进制对象会获得 `oss://` URI，并通过短时签名 URL 供浏览器播放或显示；镜头列表等结构化 JSON 不走该路径。

### Docker 构建时间过长

新增 SDK 会触发容器内 Maven/Python 依赖下载。当前 Dockerfile 已保留依赖层缓存；后续只修改源码时应命中 `dependency:go-offline`、npm 和 pip 缓存层。构建失败时优先重试，避免清理 Docker 构建缓存。

### OSS 凭据安全

AccessKey 只从本机 `.env` 和 Compose 环境变量读取，未写入源码。生产环境应改用 RAM Role/STS，并把 Bucket 权限限制到业务前缀和必要动作。

## 当前性能策略

- OSS 预览通过短时签名 URL直连，避免 Java 代理整段视频。
- `oss://` 对象使用内容 hash 参与 Object Key，上传对象不可覆盖。
- 内容 API 的签名重定向允许在签名 TTL 内缓存；Local Provider 继续使用原有文件资源。
- 后续应在固定素材集上比较 Local/OSS 的上传、首帧、Range、完整下载和 Workflow 端到端 P50/P95/P99。

## 下一步

1. 在隔离测试环境验证私有 Bucket、签名读取、Range 和 multipart，并限制测试对象前缀。
2. 补充 OSS 写入、hash/size 校验、失败恢复和 workspace 清理测试。
3. 使用固定素材集运行 Local 基线，再做 Local/OSS 对照，记录 P50/P95/P99、资源峰值和失败恢复。
4. 第十五阶段再处理 RabbitMQ/Worker 横向扩展与 Redis，不与本阶段混合部署。
