# Model Router 第三阶段与初雪 Agent Runtime 边界

本阶段完成 Model Router 的最后一层工程化：

- `llmAudit` 记录路由、Provider、模型、fallback、latency、prompt/completion tokens、估算成本；
- Story Plan 记录 `VALIDATED`、`SCHEMA_INVALID`、`CONTRACT_INVALID` 等质量状态和质量分数；
- VLM/Whisper 产物继续携带 `modelRoute`，可与执行 Artifact 关联；
- `/api/v1/llm-audits` 扩展返回 capability、routeId、tokens、estimatedCostUsd、qualityStatus、qualityScore、fallbackReason；
- Router 仍然是确定性组件，不接收模型生成的模型名，不做运行中 Replan。

到这里，Model Router 已具备初雪 Agent Runtime 所需的底座能力：

```text
Session / Turn
  -> Planner
  -> Tool Governance
  -> Model Router
  -> Tool / Model Execution
  -> Artifact + Trace + Quality Audit
```

因此可以开始“初雪” Agent 开发。初雪第一阶段建议只实现 Session Turn 驱动的 Planner/Blackboard 编排，不开放 LLM 直接调用 Shell、FFmpeg 或任意 Tool。
