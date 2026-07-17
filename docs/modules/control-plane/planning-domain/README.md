# Planning Domain

本模块实现由大模型驱动的 Agent Planner，将用户自然语言目标、上下文和 Tool 能力转换为候选 Workflow。

## 职责

- 使用大模型理解自然语言、多轮修改指令和隐含创作目标，生成结构化 ProductionRequest。
- 管理场景模板和规划约束。
- 检索可用 Tool Manifest，将能力、输入输出 Schema 和资源限制提供给 LLM Planner。
- 使用大模型选择工作流模板、编排 Task、设置依赖与参数，并编译 Workflow DSL。
- 根据确定性校验错误使用大模型修复计划，同时提供成本估算和模板降级。
- 保存规划假设、置信度和节点选择理由。

## 大模型参与范围

- Intent Parser：理解“做一个 30 秒温暖旅行 Vlog”等自然语言需求。
- Workflow Planner：根据视频类型和可用 Tool 动态生成 DAG。
- Plan Repairer：依据 Schema、类型或依赖错误修复候选计划。
- Edit Planner：理解“字幕放大”“不要夜景”等二次编辑并判断受影响子图。
- Explanation Generator：把真实的结构化决策记录转换为用户可读说明。

## 边界

大模型只生成受约束的结构化候选结果，不直接调度 Task，也不生成或执行 Shell、Python、SQL 和 FFmpeg 命令。候选 Workflow 必须经过 JSON Schema、Tool 存在性、输入输出类型、DAG 无环、权限、资源和预算校验后才能执行；大模型不可用时使用场景模板降级。
