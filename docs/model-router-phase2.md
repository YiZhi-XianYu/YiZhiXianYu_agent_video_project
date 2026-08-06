# Model Router 第二阶段

本阶段增加 Provider 健康与调用审计：

- 连续失败达到 `model_router_failure_threshold` 后进入冷却窗口 `model_router_cooldown_seconds`；
- 冷却中的 Provider 不再作为主路由；
- 文本模型调用使用显式 fallback chain，主 Provider 失败后按确定性顺序尝试下一个 Provider；
- 每次调用记录 Prometheus latency、成功/失败和 prompt/completion token；
- VLM 调用同样记录 latency/token，并将失败标记为 `VLM_CALL_FAILED`；
- 查询接口：`GET /api/v1/model-provider-health`。

这套机制仍然是单进程、单 Tool Service 范围内的健康状态，不宣称跨机器共享熔断或多机高可用。Provider 重启后健康状态自然重置，长期共享审计仍以 Artifact/Agent Trace 为准。
