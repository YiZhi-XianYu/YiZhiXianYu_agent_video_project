# Timeline Compose Tool

将 Story、字幕、转场、音乐和效果计划合并为版本化 Timeline 中间表示。

## 职责

- 计算 Clip 的源时间和目标时间。
- 合并多轨计划并处理冲突。
- 校验时长、重叠、转场、资源和参数范围。
- 输出可供 Renderer 消费的声明式 Timeline。

## 边界

不生成 Shell 命令，也不执行实际编码。

