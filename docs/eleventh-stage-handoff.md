# 第十一阶段交接：用户体系与前端工作台完善

> 文档日期：2026-07-27  
> 工作基线：`C:\Users\XRZ\Desktop\ninth\WwDa3B884n8dj`  
> 阶段状态：核心目标已完成，Docker 部署、自动化测试和真实浏览器回归通过

## 1. 本阶段目标

第十一阶段在第十阶段已收敛的 Workflow 骨架上，集中补齐面向真实用户的产品入口：

- 建立基础注册、登录、退出和服务端 Session；
- 按用户隔离项目、素材、Workflow、Artifact 和 Story Plan；
- 重做主要前端页面布局、导航和响应式工作台；
- 支持原始素材视频预览；
- 为每个 Workflow 子进程展示独立进度；
- 在 Workflow 完成后直接预览并下载最终成片；
- 为以后部署上线保留清晰的安全加固边界。

## 2. 身份与会话设计

本阶段采用服务端数据库 Session，不使用 JWT：

- 密码使用 BCrypt strength 12 哈希，数据库不保存明文密码；
- 浏览器使用 `HttpOnly`、`SameSite=Lax` Cookie：`avp_session`；
- 数据库只保存 Session Token 的 SHA-256 哈希；
- Session 默认有效期为 30 天，可通过 `AUTH_SESSION_DAYS` 调整；
- 生产环境可通过 `AUTH_SECURE_COOKIE=true` 开启 Secure Cookie；
- Logout 会撤销服务端 Session，并清除浏览器 Cookie；
- 前端收到全局 401 后会清理本地用户状态并返回登录页。

主要接口：

```text
POST /api/v1/auth/register
POST /api/v1/auth/login
POST /api/v1/auth/logout
GET  /api/v1/auth/me
```

## 3. 数据库迁移

新增 Flyway migration：

`control-plane/src/main/resources/db/migration/V2__user_authentication.sql`

迁移内容：

- 按用户授权清空未发布环境中的旧业务记录；
- 新增 `users` 表；
- 新增 `auth_sessions` 表；
- `projects` 新增非空字段 `owner_user_id` 和所有者索引；
- Flyway 已在 Docker MySQL 中成功升级并校验到 V2。

本次只清空 MySQL 业务记录，没有删除共享 Docker Volume 中的旧媒体文件，也没有删除 Python SQLite Execution Journal。

## 4. 用户数据隔离

后端在返回或修改业务数据前，均通过当前 Session 用户检查项目所有权。已覆盖：

- 项目创建、列表和详情；
- 素材上传、列表和原视频读取；
- Workflow 创建、历史、详情和继续执行；
- Artifact 内容读取与成片下载；
- 自定义 Story Plan 查询、保存和 Apply & Render。

`/internal/tool-callbacks` 仍是 Java 与 Python Tool Service 之间的内部 HTTP 回调，不依赖浏览器 Session。Java/Python 继续通过 HTTP Tool API 解耦。

## 5. 前端改造

### 5.1 登录与路由

- 新增独立注册/登录页面；
- 注册成功后自动建立 Session 并进入工作台；
- 路由守卫会在进入私有页面前检查 `/api/v1/auth/me`；
- 未登录访问私有路由时跳转到 `/auth?redirect=...`；
- 后端 SPA 转发已覆盖登录页、项目详情、Workflow、版本和审计深层路由；
- 退出登录时同时清空项目和 Workflow Store，避免不同账号之间残留前一个用户的数据。

### 5.2 工作台布局

- 左侧栏改为明确的项目、Workflow 和 LLM 审计导航；
- 展示当前用户昵称、邮箱和退出入口；
- 增加移动端顶部栏和底部导航；
- 项目列表重做为工作台首页；
- 项目详情拆分为“准备素材”和“配置并启动”两个清晰步骤；
- 修复桌面布局中侧栏 Grid 预留与主内容 margin 重复计算导致的内容挤压问题。

### 5.3 视频预览与下载

- 素材列表可打开模态框预览原始视频；
- Workflow 成功后从快照中查找最新的 `RENDERED_VIDEO` Artifact；
- 完成页直接显示浏览器原生视频播放器；
- 增加“下载成片”按钮；
- 下载复用原不可变 Artifact，不复制或改写媒体文件；
- Artifact 内容和下载接口均保留登录校验与项目所有权校验；
- `download=true` 时后端使用附件响应，文件名来自 Artifact 对应文件。

### 5.4 子进程进度

- Task 展示改为按实际执行顺序排列的纵向流程；
- 每个 Task 独立显示状态、进度条、步骤序号、尝试次数和重试次数；
- 失败 Task 展示后端返回的错误信息；
- Workflow 历史列表增加整体进度；
- Workflow 总进度直接使用后端持久化的 `run.progress`，不再由前端自行估算。

## 6. 测试与真实验收

本阶段没有安装或修改本机 Node、Maven、JDK、Python 或 Conda 环境，构建和测试均通过现有 Docker 环境完成。

- Java 完整测试：24/24 通过；
- 新增 Auth 回归：BCrypt 密码哈希、Session Token 哈希、过期 Session 拒绝、Logout 撤销；
- 新增项目所有权隔离回归；
- Vue TypeScript 检查和生产构建通过，Vite 完成 1634 个模块转换；
- Docker `control-plane` 镜像重建并部署成功；
- Flyway V2 校验成功；
- 未登录访问私有页面会进入登录页；
- 真实账号注册、登录、工作台和退出流程可用；
- 真实多素材 Workflow `c16b7534-cbaa-4ab9-a4c1-65c580e38ceb` 为 `SUCCEEDED`，19/19 Task 成功；
- 完成页成功读取 `RENDERED_VIDEO`，浏览器识别为 1280x720、约 12.03 秒、`video/mp4`，媒体错误为空；
- 最终成片预览和下载入口已在真实 Workflow 页面显示；
- `git diff --check` 通过；
- 测试生成的 TypeScript `.js/.d.ts` 产物已恢复，没有混入本阶段改动。

Python Tool Service 本阶段没有业务代码改动；第十阶段的 Python 测试和 Execution Journal 验证结论继续有效。正式发布前仍建议再执行一次完整 Python 回归，作为发布候选版本的统一验收记录。

## 7. 阶段完成度判断

第十一阶段可以判定为成功：注册登录、用户隔离、前端主导航、项目工作台、原视频预览、Task 进度、真实 Workflow 监控、最终成片预览和下载已经形成可实际操作的闭环。

这一判断表示“教学演示和后续开发地基已经完成”，不表示系统已经具备公网生产环境所需的全部安全和运维能力。

## 8. 上线前仍需加固

- 全站 HTTPS，并在生产环境设置 `AUTH_SECURE_COOKIE=true`；
- 增加 CSRF Token；当前仅依赖 `SameSite=Lax` 作为基础防护；
- 增加邮箱验证、忘记密码和密码重置；
- 增加登录限流、失败次数限制和防暴力破解；
- 增加定时清理过期/撤销 Session 的任务；
- 增加账号禁用、审计日志和管理员能力；
- 将本地共享 Volume 迁移为对象存储，并按需要接入 CDN；
- 对大视频补充更完整的 HTTP Range、断点下载和带宽控制验证；
- 使用第二个测试账号完成一次自动化跨用户 403 数据隔离回归；
- 发布候选版本统一重跑 Java、Python、前端构建和真实多素材端到端测试。

## 9. 安全与仓库卫生

- 没有把数据库密码、账号密码、API Key 或本地凭据写入代码、文档或仓库；
- 没有把视频、音乐二进制、模型缓存、`.env` 或 `node_modules` 提交到 Git；
- Artifact 内容仍保持不可变，前端只通过 Artifact Snapshot 的 `contentUrl` 读取；