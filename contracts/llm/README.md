# LLM Structured Output Contracts

本目录定义大模型在意图理解、工作流规划、计划修复、二次编辑和解释生成时必须返回的结构化结果。

## 计划中的契约

- Intent Result：视频类型、目标时长、风格、平台、约束、假设和置信度。
- Plan Proposal：高层阶段、Workflow 节点、依赖、参数和选择理由。
- Plan Repair Result：针对校验错误的最小计划修正。
- Edit Intent：修改类型、目标对象和可能受影响的工作流子图。
- Explanation Draft：引用 DecisionRecord 和 Evidence 的自然语言说明。

## 约束

这些 Schema 是大模型与确定性系统之间的安全边界。模型返回内容必须通过校验，不能包含任意可执行命令、未注册 Tool、项目外 Artifact URI 或无法追溯的事实。
