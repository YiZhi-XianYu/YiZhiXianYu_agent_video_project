# Execution Domain

本模块实现 WorkflowRun 和 TaskRun 的执行生命周期，是 DAG 调度内核。

## 职责

- 维护工作流与任务状态机。
- 解析 READY 节点，按资源和并发限制调度。
- 分发 Tool、处理回调、轮询补偿和取消。
- 实现重试、退避、租约、超时、恢复和进度聚合。
- 维护幂等和缓存命中记录。

## 边界

只调用 Tool Client 抽象，不包含 AI、视频算法或具体 HTTP 细节。

