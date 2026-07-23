# LLM Structured Output Contracts

本目录定义大模型在意图理解、工作流规划、计划修复、二次编辑和解释生成时必须返回的结构化结果。

## 已有契约

- **Story Plan Proposal** (`story-plan-proposal.schema.json`) — v1.1：LLM 只需分配 shotIds 和 reasonCodes，beat 级别的 targetDurationMs 已移除，由编译器的 `_beat_budgets()` 确定性计算。向后兼容 v1.0。
- **Duration Parsing** (`duration-parsing.schema.json`) — v1.0：解析自然语言时长描述为目标毫秒数。
- Intent Result：视频类型、目标时长、风格、平台、约束、假设和置信度。
- Plan Proposal：高层阶段、Workflow 节点、依赖、参数和选择理由。
- Plan Repair Result：针对校验错误的最小计划修正。
- Edit Intent：修改类型、目标对象和可能受影响的工作流子图。
- Explanation Draft：引用 DecisionRecord 和 Evidence 的自然语言说明。

## 约束

这些 Schema 是大模型与确定性系统之间的安全边界。模型返回内容必须通过校验，不能包含任意可执行命令、未注册 Tool、项目外 Artifact URI 或无法追溯的事实。

第四阶段已冻结首个可执行契约。第五阶段 LLM 只允许在固定旅行模板中分配 shotIds 并返回原因码。Schema 不提供 Shell、FFmpeg、Tool、URI、路径、SQL 或任意参数字段；候选结果还必须通过服务端 `LlmStoryProposalValidator` 的引用和重复校验后，才能编译为 `STORY_PLAN`。

## v1.1 改进（第八阶段 LLM 优化）

相比 v1.0，v1.1 Schema 移除了 beat 级别的 `targetDurationMs` 字段。原因是：
1. JSON Schema 无法表达跨字段算术约束（`sum(beats[*].targetDurationMs) == targetDurationMs`）
2. 编译器 `_compile_llm_proposal()` 始终使用确定性 `_beat_budgets()` 计算时长，忽略 LLM 输出的 beat 时长
3. DeepSeek V3 数值推理能力有限，经常在该约束上失败

移除后，LLM 只需关注 shot-to-beat 的语义分配，配合支持 strict structured output 的模型（GPT-4o、Claude Sonnet 4/4.6），可大幅提升采纳率。
