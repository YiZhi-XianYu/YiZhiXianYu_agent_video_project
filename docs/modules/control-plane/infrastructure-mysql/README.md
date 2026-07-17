# MySQL Infrastructure

本模块实现 Java 领域 Repository、事务、迁移和 Transactional Outbox 的 MySQL 适配。

## 职责

- 持久化 Project、Workflow、TaskRun、Artifact、Timeline 和审计数据。
- 提供乐观锁或行锁，保证多实例任务领取安全。
- 管理数据库迁移、索引和查询实现。
- 在同一事务中写入业务状态与 Outbox Event。

## 边界

MySQL 是权威状态来源；本模块不承载领域决策。

