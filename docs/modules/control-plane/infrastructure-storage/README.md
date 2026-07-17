# Object Storage Infrastructure

本模块实现 MinIO/S3 对象存储适配，管理大型媒体和 Artifact 的访问。

## 职责

- 创建上传会话和短期签名 URL。
- 上传、读取、复制和删除媒体对象。
- 校验项目级对象键与访问权限。
- 管理 Artifact 生命周期、保留期限和清理。

## 边界

不向前端暴露永久密钥，不在此模块决定业务上的 Artifact 血缘。

