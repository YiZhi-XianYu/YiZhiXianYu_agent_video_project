# Tool Execution Runtime

本模块管理 Tool Execution 的异步执行生命周期。

## 职责

- 创建队列任务并选择 CPU/GPU Worker。
- 执行幂等去重、超时、取消和进度上报。
- 捕获异常并映射为标准错误。
- 保存执行结果并可靠触发 Java 回调。

## 边界

不决定 Java Workflow 的重试与依赖状态，只报告单次 Tool Execution 的事实。

