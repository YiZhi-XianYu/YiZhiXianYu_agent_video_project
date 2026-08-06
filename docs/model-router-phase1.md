# Model Router 第一阶段

Model Router 采用确定性能力路由，不允许 LLM 自行选择模型，也不改变 Workflow DAG。

当前能力映射：

| capability | 主路由 | fallback |
|---|---|---|
| `STRUCTURED_INTENT` | 配置的文本模型 | 后端默认意图 |
| `STORY_PLAN` | 配置的文本模型 + JSON Schema 校验 | 确定性 Story Plan |
| `SHOT_SEMANTICS` | 视觉模型 | 本地 CLIP |
| `LONG_AUDIO_TRANSCRIPTION` | 本地 faster-whisper | 无静默远程替换 |

每次决策统一记录：

- `routeId`
- `capability`
- `provider` / `model`
- `fallbackChain`
- `selectedBy`
- `selectionReason`
- `available`
- `fallbackReason`

查询当前配置下的路由：

```text
GET /api/v1/model-routes
```

Workflow Intent 响应包含 `modelRoute`；Story Plan 的 `llmAudit` 包含路由信息；VLM 与 Whisper 产物 metadata 包含 `modelRoute`。模型不可用和调用失败会分别记录 `LLM_UNAVAILABLE`、`VLM_UNAVAILABLE`、`MODEL_CALL_FAILED` 或 `VLM_CALL_FAILED`，不再只显示“调用成功”。

本阶段不进行基于成本/质量的动态学习，不做运行中 Replan，也不把 Router 变成 Control Plane 的远程强依赖。
