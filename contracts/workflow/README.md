# Workflow Schema

本目录定义 WorkflowDefinition、节点、边、输入绑定、条件、重试策略和资源需求的 JSON Schema。

Schema 必须支持确定性校验，并与 DAG Validator 的规则一致。LLM Planner 只允许产生符合该契约的候选计划。

