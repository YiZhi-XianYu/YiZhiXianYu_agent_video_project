# Tool Governance 生产化说明

Tool Registry 的 Manifest 现在由统一规范化层补齐治理字段，旧工具无需一次性改写即可获得一致的准入信息。

每个工具的治理 Manifest 包含：

- `automationPolicy`：`AUTO`、`REQUIRE_CONFIRMATION`、`MANUAL_ONLY`、`DISABLED`
- `requiresUserConfirmation`：是否需要人工确认
- `sideEffectLevel`：`NONE`、`LOW`、`HIGH`
- `resourceGroup`：`LIGHT`、`MEDIA`、`MODEL`、`RENDER`
- `timeoutSeconds`、`maxAttempts`、`allowFallback`
- `inputSchema`、`outputSchema`、`estimatedCost`

`video.render` 和 `audio.bgm-select` 默认需要人工确认；`video.render` 为高副作用工具且不允许静默降级。Python `/api/v1/tools` 返回规范化后的完整清单，Java Workflow Validator 使用本地确定性 Catalog 做准入校验，不依赖运行时访问 Python Registry。

Worker 在执行前检查 Rabbit 消息的 `attempt` 是否超过 Manifest 的 `maxAttempts`，执行过程中应用 `timeoutSeconds`。超时会生成可重试的终态错误，最终重试次数仍由 Control Plane 的 Task attempt 机制决定。

Planner Preview 会返回每个节点的自动化策略、资源组、最大尝试次数，以及 `governanceWarnings` / `requiresConfirmation`，前端可以在 Gate 前明确展示需要用户确认的节点。
