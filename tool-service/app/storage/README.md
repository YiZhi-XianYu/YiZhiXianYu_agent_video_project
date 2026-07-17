# Tool Storage Adapter

本模块为 Tool 提供对象存储和本地临时工作区的统一访问。

## 职责

- 下载输入 Artifact，校验哈希和媒体信息。
- 管理隔离的临时目录。
- 上传结果并返回 ArtifactDescriptor。
- 清理临时文件和处理存储错误。

## 边界

Tool 不能假设 Java 与 Python 共享本地文件路径，大型结果必须通过对象存储交换。

