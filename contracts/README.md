# 共享契约

本目录是 Java、Python 和前端共同遵守的机器可校验契约源。

## 职责

- 定义 Workflow、Timeline、Tool Manifest、LLM 结构化输出和事件 Schema。
- 保存 OpenAPI 描述和版本兼容规则。
- 通过 CI 检查契约格式及兼容性。

## 约束

同一契约不得在多个服务中各自复制后独立修改。业务代码应从这里生成类型或在测试中验证兼容性。
