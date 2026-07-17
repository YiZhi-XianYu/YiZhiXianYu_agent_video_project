# Tool Workers

本目录定义 Tool 的后台执行进程和资源队列布局。

## 职责

- 区分 CPU、GPU 和高内存任务。
- 管理 Worker 心跳、并发、预取和优雅停止。
- 响应取消信号并上报执行进度。
- 隔离模型崩溃、FFmpeg 错误和资源耗尽。

## 边界

Worker 不持有工作流权威状态，也不绕过 Execution Runtime 直接回写 Java 数据库。

