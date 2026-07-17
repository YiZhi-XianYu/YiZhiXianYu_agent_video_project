# Tool 插件集合

本目录按能力保存可独立注册和版本化的 Python Tool。

## Tool 规范

每个 Tool 未来应包含 Manifest、输入输出 Schema、实现、模型适配和测试样例，并声明资源等级、超时、缓存、确定性及取消能力。

## 边界

Tool 之间不通过隐式本地文件直接耦合，应通过声明式输入、Artifact 和标准结果协作。新增 Tool 不应要求修改 Java Workflow Engine。

