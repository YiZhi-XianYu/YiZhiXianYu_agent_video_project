# 项目文档

本目录保存项目的长期设计与协作资料，是实现方案和架构决策的文字依据。

## 职责

- 维护系统设计基线、接口说明和架构决策记录。
- 保存 Java 控制面等组件的逻辑模块说明与源码映射。
- 记录设计变更的原因、影响范围与版本。
- 为开发、测试、演示和项目评审提供统一参考。

## 边界

本目录不存放业务代码、运行配置或生成产物。当前完整设计基线见《Agent-Driven智能视频制作流水线-系统设计文档.md》。

阶段交接入口：

- `first-vertical-slice.md`：Day 1，单节点视频技术信息分析；
- `second-vertical-slice.md`：Day 2，两节点依赖调度与代理视频生成；
- `third-stage-handoff.md`：第三阶段，多素材 DAG、Shot Detection 与关键帧；
- `tenth-stage-handoff.md` 至 `twelfth-stage-handoff.md`：当前主链路收敛、用户工作台、服务器部署和后续修复记录。

## 后续内容

后续可增加开发指南、测试策略、演示手册和故障排查文档。

控制面模块说明位于 [`modules/control-plane`](modules/control-plane/README.md)。
