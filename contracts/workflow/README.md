# Workflow Schema

本目录定义 WorkflowDefinition、节点、边、输入绑定、条件、重试策略和资源需求的 JSON Schema。

Schema 必须支持确定性校验，并与 DAG Validator 的规则一致。LLM Planner 只允许产生符合该契约的候选计划。

当前最小契约见 `workflow-definition.schema.json`。第三阶段支持节点、边、版本、Tool 参数和两类输入绑定：项目原始 Asset 与上游 Artifact。

Java 侧 `WorkflowDefinitionValidator` 目前确定性校验：定义键和版本、节点键唯一、Tool 引用完整、边端点存在、禁止自环和拓扑排序无环。当前多素材模板由服务端生成并持久化快照，尚未开放任意客户端定义上传。
