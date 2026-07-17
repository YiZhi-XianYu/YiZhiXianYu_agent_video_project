# Video Knowledge Merge Tool

将 Shot、转写、OCR、场景标签、质量和向量结果合并为共享的 Video Knowledge。

## 职责

- 对齐不同 Tool 的时间轴。
- 统一置信度、缺失值和证据引用。
- 生成 Shot 级结构与供 Planner 使用的紧凑摘要。
- 保存来源 Tool、模型和 Schema 版本。

## 边界

不重复分析原视频，也不执行镜头排序。

