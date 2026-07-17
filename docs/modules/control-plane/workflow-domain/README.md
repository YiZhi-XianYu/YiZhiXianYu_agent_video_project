# Workflow Domain

本模块定义版本化 Workflow DAG 及其确定性规则。

## 职责

- 管理 WorkflowDefinition、TaskDefinition 和 Edge。
- 校验节点唯一性、引用、类型、可达性和无环性。
- 冻结已确认的工作流版本。
- 计算依赖关系、条件分支和计划差异。

## 边界

本模块描述“应该执行什么”，不负责实际分发 Tool 或保存外部执行状态。

