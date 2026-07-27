# 阿里云 ECS 部署指南

> 适用环境：Ubuntu 22.04 x86_64、Docker Engine、Docker Compose v2 兼容命令  
> 推荐配置：4 vCPU、8 GiB 内存、80 GiB 以上磁盘  
> 当前试用实例：4 vCPU、8 GiB、40 GiB 系统盘；适合首次冒烟部署，但必须控制视频与模型占用

## 1. 部署拓扑

```text
Internet
  -> ECS security group 80/443
  -> Caddy
  -> Java Control Plane :8080
  -> Python Tool Service :8090
  -> MySQL :3306
```

生产 Compose 只发布 Caddy 的 `80/443`。MySQL、Java 和 Python 均没有宿主机端口映射。

Java 与 Python 继续通过内部 HTTP Tool API 通信；Python 回调使用 `http://control-plane:8080/internal/tool-callbacks`。Caddy 对公网的 `/internal/*` 直接返回 404。

## 2. 已准备的服务器

服务器基础准备结果：

- Ubuntu 22.04.5 LTS；
- x86_64；
- Docker 29.6.2；
- Docker Compose 5.3.1；
- `deploy` 用户已加入 `sudo` 和 `docker` 组；
- `/opt/agent-video` 目录已由 `deploy` 用户管理；
- 4 GiB Swap 已启用并写入 `/etc/fstab`；
- 当前只需保留 SSH 22；部署前再开放 80，域名 HTTPS 就绪时开放 443；
- 不开放 3306、3307、8080 和 8090。

## 3. 提交到 Git 的部署文件

以下文件是无凭据模板，应提交到 Git：

```text
docker-compose.prod.yml
deploy/Caddyfile
deploy/.env.production.example
docs/deployment-aliyun.md
```

以下内容不得提交：

```text
.env
*.pem
*.key
数据库备份
视频与 Artifact
模型缓存
服务器 data 目录
```

## 4. 拉取代码

在服务器使用 `deploy` 用户：

```bash
cd /opt/agent-video
git clone <PRIVATE_REPOSITORY_SSH_URL> app
cd app
```

私有仓库建议使用仅能读取该仓库的 Deploy Key。不要把 Git 账号密码或 Personal Access Token 写进命令、脚本或仓库。

## 5. 创建生产环境变量

```bash
cd /opt/agent-video/app
cp deploy/.env.production.example .env
chmod 600 .env
nano .env
```

生成两个不同的数据库密码：

```bash
openssl rand -base64 32
openssl rand -base64 32
```

分别填写：

```dotenv
MYSQL_ROOT_PASSWORD=...
MYSQL_APP_PASSWORD=...
```

不要把真实密码或 LLM Key 发到聊天、截图或 Git。

首次使用公网 IP 做 HTTP 冒烟测试：

```dotenv
SITE_ADDRESS=http://YOUR_PUBLIC_IP
AUTH_SECURE_COOKIE=false
```

绑定域名并启用 HTTPS 后必须改为：

```dotenv
SITE_ADDRESS=video.example.com
AUTH_SECURE_COOKIE=true
```

## 6. 创建持久化目录

```bash
mkdir -p /opt/agent-video/data/mysql
mkdir -p /opt/agent-video/data/runtime
mkdir -p /opt/agent-video/data/huggingface
mkdir -p /opt/agent-video/data/caddy/data
mkdir -p /opt/agent-video/data/caddy/config
mkdir -p /opt/agent-video/backups
```

不要运行 `docker compose down -v` 或无目标的 `docker volume prune`。

## 7. 静态检查

```bash
cd /opt/agent-video/app
docker compose --env-file .env -f docker-compose.prod.yml config -q
docker compose --env-file .env -f docker-compose.prod.yml config --services
```

服务应为：

```text
mysql
control-plane
tool-service
caddy
```

## 8. 首次构建与启动

```bash
docker compose --env-file .env -f docker-compose.prod.yml build
docker compose --env-file .env -f docker-compose.prod.yml up -d
docker compose --env-file .env -f docker-compose.prod.yml ps
```

查看日志：

```bash
docker compose --env-file .env -f docker-compose.prod.yml logs --tail=100 mysql
docker compose --env-file .env -f docker-compose.prod.yml logs --tail=100 control-plane
docker compose --env-file .env -f docker-compose.prod.yml logs --tail=100 tool-service
docker compose --env-file .env -f docker-compose.prod.yml logs --tail=100 caddy
```

预期：

- MySQL healthy；
- Flyway 校验并升级到当前版本；
- Control Plane healthy；
- Tool Service healthy；
- Caddy healthy；
- 宿主机仅监听 80/443 和管理用 22。

## 9. 安全组与防火墙

阿里云安全组入方向：

```text
22/tcp   -> 最好限制为管理员公网 IP
80/tcp   -> 0.0.0.0/0
443/tcp  -> 0.0.0.0/0
443/udp  -> 0.0.0.0/0，可用于 HTTP/3
```

不开放：

```text
3306
3307
8080
8090
3389
```

启用 UFW 前先允许 SSH：

```bash
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw allow 443/udp
sudo ufw enable
sudo ufw status
```

## 10. 域名与 HTTPS

中国大陆 ECS 使用域名公开服务通常需要 ICP 备案。备案完成并添加 A 记录后，将 `.env` 的 `SITE_ADDRESS` 改为域名，并设置：

```dotenv
AUTH_SECURE_COOKIE=true
```

重建不必要，只需替换容器配置：

```bash
docker compose --env-file .env -f docker-compose.prod.yml up -d caddy control-plane
```

Caddy 会自动申请和续期 HTTPS 证书。

## 11. 公网验收

按顺序检查：

1. 未登录访问私有页面会进入登录页；
2. 注册、登录、退出可用；
3. 缺少 CSRF Token 的写请求被拒绝；
4. 登录和注册高频请求触发限流；
5. 用户之间项目和 Artifact 隔离；
6. 上传最小测试视频；
7. 原视频预览；
8. 真实多素材 Workflow；
9. 每个 Task 独立进度；
10. 最终成片预览与下载；
11. 容器重启后项目、Workflow 和 Python Execution 仍存在；
12. 公网不能访问 3306、8080 和 8090；
13. 浏览器控制台无严重错误；
14. `docker stats` 和 `df -h` 没有资源异常。

## 12. 40 GiB 磁盘注意事项

当前实例只有约 34 GiB 可用空间。占用大户包括：

- Docker 构建缓存和镜像；
- Hugging Face 模型；
- 上传原视频；
- 代理视频、中间 Artifact 和最终成片；
- MySQL 和日志。

定期查看：

```bash
df -h
docker system df
du -sh /opt/agent-video/data/*
```

不要自动执行破坏性清理。确认无用后可清理构建缓存，但不得删除正在引用的 Artifact、MySQL 数据或 Execution Journal。

## 13. OSS 正式存储路线

首次冒烟部署仍使用 ECS 本地目录。正式邀请外部用户上传前，建议迁移到阿里云 OSS：

```text
Browser -> OSS direct upload
Java -> save object key, ETag, hash and lineage
Python -> download through same-region internal endpoint
Python -> upload immutable output object
Browser -> short-lived signed preview/download URL
```

推荐设置：

- Bucket 与 ECS 同地域；
- Bucket 私有，不允许公共读写；
- 使用 ECS RAM 实例角色，不使用写入仓库的 AccessKey；
- 对象 Key 使用不可变 Artifact ID；
- 预览和下载使用短期签名 URL；
- 临时文件生命周期与业务 Artifact 生命周期分开；
- 不自动删除仍被数据库血缘引用的对象。

OSS 会减少 ECS 系统盘和公网带宽压力，但 OSS 存储、请求和公网下行仍可能收费。访问量增加后再评估 CDN。

当前代码仍以共享本地 `file://` Artifact 为实现基础。OSS 接入应新增 `StorageProvider` 抽象并保留 `LOCAL/OSS` 两种实现，不能只把本地路径替换成公网 URL。

## 14. 日常更新

```bash
cd /opt/agent-video/app
git pull --ff-only
docker compose --env-file .env -f docker-compose.prod.yml build
docker compose --env-file .env -f docker-compose.prod.yml up -d
docker compose --env-file .env -f docker-compose.prod.yml ps
```

前端或 Java 修改只需重建 `control-plane`；Python Tool 修改只需重建 `tool-service`。数据库结构以后只增加新的 Flyway migration，不修改已部署的 V1/V2。

## 15. 4 核 8 GiB 资源策略

生产 Tool Service 保留 4 个总 worker，但按资源权重调度：

```text
LIGHT  = 0 capacity unit
MEDIA  = 1 capacity unit
MODEL  = 2 capacity units
RENDER = 2 capacity units
total heavy capacity = 2
```

结果是轻量节点仍可并发，两个普通媒体任务可以并行，而 CLIP/Whisper
或最终 Render 会独占重资源时段。模型任务结束后只释放进程内模型引用，
不会删除 `/opt/agent-video/data/huggingface` 中的持久化缓存。

如果内核日志出现 `Memory cgroup out of memory`，不要直接提高容器内存。
先检查是否仍在使用旧镜像：

```bash
docker inspect avp-prod-tool-service --format '{{.Image}}'
docker compose --env-file .env -f docker-compose.prod.yml build tool-service
docker compose --env-file .env -f docker-compose.prod.yml up -d --force-recreate tool-service
```

Render 失败现在会保存 FFmpeg 退出码、终止信号和截断后的 stderr。
`signal 9` 且没有 FFmpeg 诊断通常表示容器内存限制触发。
