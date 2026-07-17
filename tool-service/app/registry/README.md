# Python Tool Registry

本模块发现、加载和管理当前 Python 服务内可执行的 Tool 插件。

## 职责

- 启动时扫描 Tool Package 和 Manifest。
- 校验名称、版本、Schema 与资源声明。
- 按名称和版本定位执行实现。
- 暴露 Tool 能力和健康信息。

## 边界

只注册符合契约的 Tool；不能通过隐式导入把未声明能力暴露给 Java。

