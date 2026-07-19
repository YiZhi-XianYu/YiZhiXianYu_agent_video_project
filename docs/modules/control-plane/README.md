# Java 控制面逻辑模块

本目录保存 Java 控制面的逻辑模块说明。这些目录是设计文档，不是 Maven 子模块，也不参与编译。

实际 Java 代码统一位于：

```text
control-plane/src/main/java/com/yizhixianyu/agentvideo/
```

当前采用单 Maven 工程，通过 Java package 保持模块化边界。模块之间通过应用服务、明确接口和领域事件协作。

## 分层原则

- Interface 负责传输协议和输入输出 DTO。
- Application 负责用例编排和事务边界。
- Domain 负责实体、值对象、状态机、策略和不变量。
- Infrastructure 负责数据库、缓存、对象存储和外部 HTTP 集成。

其中 `infrastructure-llm-provider` 负责大模型访问适配；Planning Domain 通过抽象接口使用它，不直接绑定具体模型厂商。

## 依赖约束

领域模块不得依赖具体基础设施实现；Controller 不得直接访问 Repository 或拼装 Tool 请求。

## 当前映射

| 逻辑模块 | 当前源码位置 | 状态 |
|---|---|---|
| App Bootstrap | `agentvideo/AgentVideoControlPlaneApplication.java` | 已实现基础启动 |
| Interface API | `agentvideo/api/` | 已实现项目列表、批量素材、项目 Workflow 历史、多素材工作流与 Artifact 内容 API；静态页面可回看历史运行 |
| Project Domain | `agentvideo/project/` | 已实现 |
| Asset Domain | `agentvideo/asset/`、`agentvideo/artifact/` | 已实现基础素材与 Artifact |
| Execution Domain | `agentvideo/execution/` | 已实现 `ASSET`/`WORKFLOW` 节点作用域、多素材展开、跨分支汇聚、Artifact 类型输入绑定、依赖表、数据库行锁收敛、有限重试、丢失执行检测、补偿扫描、失败传播与状态机 |
| Storage Infrastructure | `agentvideo/storage/` | 已实现本地存储适配器 |
| Tool Client Infrastructure | `agentvideo/toolclient/` | 已实现 Python HTTP Tool Client |
| Planning Domain | Python `planning.story-template` 与共享 Story/LLM 契约 | 已实现确定性五段 Story 基线；待第五阶段增加受约束 LLM Provider |
| Workflow Domain | `agentvideo/workflow/` | 已实现带节点作用域的 WorkflowDefinition、DAG Validator 与确定性 8 节点多素材模板 |
| Context Domain | 尚未创建源码包 | 待实现 |
| Explanation Domain | 尚未创建源码包 | 待实现 |
| Tool Registry Domain | 尚未创建源码包 | 待实现 |
| MySQL Infrastructure | 当前由 Spring Data Repository 分布在各包 | 后续可统一整理 |
| Redis Infrastructure | 尚未创建源码包 | 待实现 |
| LLM Provider Infrastructure | 尚未创建源码包 | 待实现 |
