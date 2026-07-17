# Tool Registry Domain

本模块维护系统当前可被 Planner 和 Scheduler 使用的 Tool 能力目录。

## 职责

- 同步并保存 Tool Manifest 与版本。
- 管理启用、禁用、降级和健康状态。
- 按输入输出类型、资源和能力检索 Tool。
- 校验语义化版本与 Schema 兼容性。

## 边界

不执行 Tool，不管理模型文件，也不包含具体 HTTP Client 实现。

