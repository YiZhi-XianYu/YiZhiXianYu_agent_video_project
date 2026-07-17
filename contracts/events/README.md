# 事件契约

本目录定义 Workflow、Task、Artifact、Tool Execution 和 WebSocket 进度事件的结构。

事件应带版本、时间、Trace、聚合对象 ID 和单调序号。消费者必须能够忽略未知可选字段，并通过 REST 快照补偿消息丢失。

