# Project Domain

本模块管理智能视频项目及其生命周期，是素材、请求、工作流和版本的聚合边界。

## 职责

- 创建、查询、归档和删除 Project。
- 管理项目所有者、权限、状态和保留策略。
- 关联 ProductionRequest、Workflow、Timeline 和 Render 版本。
- 发出项目级领域事件。

## 边界

不负责上传二进制、执行工作流或分析视频内容。

