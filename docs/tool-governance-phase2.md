# Tool Governance 第二轮

第二轮将治理策略从 Manifest 描述推进到运行时准入：

- `video.render` 在非 `autoMode` 下新增前置 `gate_governance_video_render`，用户确认前不会进入 `READY`，也不会写入 Rabbit Outbox。
- 用户继续该 Gate 时写入 `TOOL_GOVERNANCE_APPROVED` Trace；阻断时写入 `TOOL_GOVERNANCE_BLOCKED` Trace。
- Task 派发 Trace 包含自动化策略、副作用等级、资源组、最大尝试次数和降级开关，能够解释一次执行为什么被允许、如何重试。
- Control Plane 的实际最大尝试次数取系统配置与工具 Manifest `maxAttempts` 的较小值，避免产生 Worker 必然拒绝的超限 attempt。
- `autoMode=true` 仍表示用户显式选择全自动执行，因此跳过人工确认 Gate；该选择本身不等同于 Kubernetes 或多机能力。

现有 BGM、故事、时间线审核 Gate 保持原语义：它们分别在候选/计划/时间线产物生成后暂停，用户操作后再继续下游任务。
