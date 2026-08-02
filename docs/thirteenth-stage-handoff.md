# 第十三阶段交接：执行前动态 DAG 编排

> 日期：2026-08-02  
> 正式仓库：`C:\Users\XRZ\Desktop\ninth\WwDa3B884n8dj`  
> 阶段状态：已完成，暂时收口  
> 范围边界：本阶段完成 Workflow 启动前的动态规划、可视化编辑、校验与确认；产品不规划面向用户的运行中拓扑 Replan，也未新增独立 Definition Revision 数据表或数据库迁移。

## 1. 阶段结论

第十三阶段把原来只能直接启动固定 `MULTI_ASSET_ANALYSIS` 模板的入口，升级为完整的执行前编排闭环：

```text
自然语言需求（含成片时长）
        -> LLM 生成受控能力意图
        -> 后端基于默认模板编译候选 DAG
        -> 多素材级节点展开与中文流程图展示
        -> 用户拖动节点、添加能力、增删连线或恢复默认 DAG
        -> 服务端重新校验最终 DAG
        -> 用户选择半自动或全自动
        -> 创建真实 Workflow 并复用既有执行引擎
```

LLM 只决定受控能力意图和目标时长，不返回 Tool、命令、SQL、文件路径或任意执行参数。节点、Tool 版本、依赖关系和最终 `WorkflowDefinition` 仍由 Java 控制面生成并校验。LLM 不可用或输出不合法时，系统回退到经过验证的默认 DAG。

## 2. 已交付能力

### 2.1 自然语言规划

- 用户在一个中文对话输入框中描述剪辑目标和时长，不再维护独立时长输入框；
- 支持从 `30秒`、`1分钟`、`半分钟`、`45 seconds` 等表达中提取时长；
- Tool Service 返回结构化 `targetDurationMs` 和受控能力状态；
- Java Planner 将目标时长写入 Story Plan 参数；
- LLM 不可用时使用确定性时长解析和默认能力集合。

### 2.2 候选 DAG 与默认 DAG

- 点击“生成候选流程图”时，LLM 根据自然语言需求在默认流程上进行受控调整；
- 点击“使用默认 DAG”或“恢复默认 DAG”可回到固定主流程；
- 默认 `MULTI_ASSET_ANALYSIS` 为 13 个逻辑节点、19 条依赖边和 5 个运行中审核 Gate；加上执行前动态 DAG 编排 Gate，产品流程共 6 个 Gate；
- 每个输入素材展开独立的探测、代理视频、镜头切分、质量评分、VLM 和源音频转写节点；
- 镜头排序、故事编排、高光选择、时间线、BGM、字幕和最终渲染保持工作流级节点。

### 2.3 中文 DAG 画布

- 所有面向用户的节点名称、能力名称、提示和校验反馈均使用中文；
- 初始布局按 DAG 拓扑分层，素材并行分支正常并行，串行任务沿主方向排列；
- 支持缩放、适应画布和调整画布高度；
- 节点卡片只显示中文名称及“素材序号/工作流”两行信息；
- 视频探测节点不显示左侧输入端口；
- 节点和连线的端口位置与画布缩放、拖动坐标保持一致。

### 2.4 编辑交互

- 节点可拖动到画布任意合法位置，拖动不会自动改变拓扑；
- 可选能力卡片可以直接拖入画布，放入后不自动连线，由用户自行连接；
- 能力面板展示视觉语义分析、源音频转写、字幕编排和背景音乐；
- “已选能力”区域只读，不承担编辑操作；
- 节点和连线均采用“两次点击删除”：第一次高亮，第二次删除；
- 点击画布其他位置会取消高亮，拖动阈值用于避免单击与拖拽混淆；
- 新增连线由右侧输出端口拖向左侧输入端口；
- 用户后续操作不会把刚拖入画布的能力节点重新自动连回默认依赖。

### 2.5 依赖与安全约束

- 字幕编排以源音频转写为必需前提；
- 禁用源音频转写时，后端同时禁用字幕，避免生成不可执行 DAG；
- VLM 在当前模板中属于核心分析能力，Planner 会将其规范化为启用状态；
- 所有画布编辑在确认前调用服务端校验；
- 环、自依赖、缺少必需生产者、无效节点或无法到达最终渲染等情况不能进入下一步；
- 前端提交的删除节点、删除边和新增边会由后端重新映射为逻辑节点并运行 `WorkflowDefinitionValidator`；
- 确认后仍复用既有 Task 状态机、Artifact 血缘、Gate、重试和恢复能力。

### 2.6 执行模式

- 确认 DAG 时可选择“半自动”或“全自动”；
- 半自动保留现有 5 个运行中审核 Gate；
- 全自动跳过这 5 个运行中 Gate；执行前动态 DAG Gate 始终保留，因为 Workflow 必须先确认才能创建；
- 执行模式和最终通过校验的 Definition 一起用于创建 Workflow。

### 2.7 多素材镜头预览修复

- Workflow 监控页不再只读取第一条 `video_shot_detect` Task；
- 所有素材级 `SHOT_LIST` 和关键帧 Artifact 会合并加载；
- 代理视频按 `sourceProxyArtifactId` 精确匹配到对应镜头；
- 镜头画廊、列表审核、故事编辑候选和时间线预览复用正确的关键帧、时间范围和代理视频；
- 修复后的前端已重新构建进 `control-plane` 镜像并在本机容器验证生效。

## 3. 默认流程依赖

```text
每个素材：
视频探测 -> 生成代理视频 -> 镜头切分
生成代理视频 + 镜头切分 -> 质量评分
镜头切分 -> 视觉语义分析
生成代理视频 -> 源音频转写

所有素材汇聚：
质量评分 -> 镜头排序
镜头排序 + 视觉语义分析 -> 故事编排
故事编排 + 镜头排序 -> 高光选择
高光选择 -> 时间线合成
故事编排 + 时间线合成 -> 背景音乐
时间线合成 + 源音频转写 -> 字幕编排
时间线合成 + 背景音乐（可选）+ 字幕（可选） -> 最终渲染
```

## 4. 主要接口

```text
POST /api/v1/projects/{projectId}/workflow-plans/preview
POST /api/v1/projects/{projectId}/workflow-plans/validate
POST /api/v1/projects/{projectId}/workflow-plans/confirm
POST /internal/v1/workflow-intent
```

- `preview`：生成候选 Definition、默认 Definition、中文解释和素材级画布，不创建 Task；
- `validate`：应用画布编辑并执行服务端 DAG 校验，不创建 Workflow；
- `confirm`：再次生成受控候选、应用编辑、校验并创建真实 Workflow；
- `workflow-intent`：Tool Service 调用 LLM 或确定性回退，输出能力意图和目标时长。

确认请求中的主要编辑字段：

```json
{
  "removedNodeIds": [],
  "removedEdgeIds": [],
  "addedEdges": [],
  "autoMode": false,
  "useDefault": false
}
```

## 5. 关键源码

### Java Control Plane

- `control-plane/src/main/java/com/yizhixianyu/agentvideo/workflow/DynamicWorkflowPlanner.java`
- `control-plane/src/main/java/com/yizhixianyu/agentvideo/workflow/MultiAssetAnalysisTemplate.java`
- `control-plane/src/main/java/com/yizhixianyu/agentvideo/api/WorkflowController.java`
- `control-plane/src/main/java/com/yizhixianyu/agentvideo/execution/WorkflowExecutionService.java`
- `control-plane/src/main/java/com/yizhixianyu/agentvideo/toolclient/ToolServiceClient.java`
- `control-plane/src/test/java/com/yizhixianyu/agentvideo/workflow/DynamicWorkflowPlannerTest.java`

### Python Tool Service

- `tool-service/app/api/routes.py`

### Vue Web App

- `web-app/src/features/workflow/WorkflowTopologyPlanner.vue`
- `web-app/src/features/workflow/WorkflowMonitorPage.vue`
- `web-app/src/features/projects/ProjectDetailPage.vue`
- `web-app/src/api/workflows.ts`
- `web-app/src/api/types.ts`

## 6. 验证记录

本阶段完成过以下验证：

- Java 动态规划单元测试与 Maven 构建；
- Python `compileall`；
- Vue `vue-tsc` 类型检查；
- Vite production build；
- `control-plane` 和 `tool-service` Docker 镜像构建；
- Tool Service 自然语言时长解析实测：`两个视频做成30秒快节奏短片 -> targetDurationMs = 30000`；
- 本机 `control-plane` 更新后返回 HTTP 200；
- 生产前端分包已确认包含多素材 `SHOT_LIST` 合并与 `sourceProxyArtifactId` 映射；
- 用户实际验收动态画布和镜头卡片预览通过。

本阶段最后一次部署命令：

```powershell
docker compose build control-plane
docker compose up -d --no-deps control-plane
```

生产前端由 `control-plane/Dockerfile` 构建并打入 Java 镜像。只修改 Vue 源码但不重建 `control-plane` 时，`http://127.0.0.1:8080` 仍会继续加载旧前端包。

## 7. 当前架构边界

本阶段完成的是“执行前动态 DAG 编排”，不是任意 Agent 执行框架：

- Agent 不能直接选择 Tool 版本、参数或执行命令；
- 当前能力集合由代码中的受控模板和白名单约束；
- 画布坐标只属于前端展示状态，不写入 Workflow 业务定义；
- 当前确认结果保存于 `workflow_runs.definitionJson`，没有新增独立 Revision 表；
- Workflow 创建后拓扑冻结，不提供运行中的通用 DAG Replan；现有 Story Plan/Timeline 修改仍使用原 Workflow 内的受控下游重执行机制；
- 未新增数据库迁移，也没有修改历史 Workflow 数据结构。

## 8. 后续接手建议

第十三阶段可以暂时结束。后续若继续生产化，建议拆成独立阶段，不在当前闭环上继续堆叠：

1. 按审计需要持久化 Workflow Intent、Definition hash、确认人和幂等信息；
2. 建立由 Tool Manifest 驱动的 Capability Catalog，减少 Java/Python 双端能力声明；
3. 增加浏览器级自动化回归，覆盖多素材布局、拖入能力、删边、非法 DAG 和默认回退；
4. 将画布布局作为独立草稿保存，仅在确有恢复编辑需求时引入。

产品原则：Workflow 在执行前动态 DAG Gate 确认后冻结。运行中的 Gate 只修改业务 Artifact；需求变化时创建新的 Workflow，不修改正在执行的拓扑。

## 9. 交接注意事项

- 只在正式目录 `C:\Users\XRZ\Desktop\ninth\WwDa3B884n8dj` 修改代码和文档；
- 不要把历史备份目录与正式仓库混用；
- 不要提交 `.env`、媒体文件、模型缓存、运行 Artifact 或前端构建产物；
- 不要通过修改旧 Migration、删除 Artifact 或重置数据库实现回滚；
- 动态 Planner 异常时应优先恢复默认 DAG，而不是绕过服务端校验；
- 修改前端后必须重新构建 `control-plane` 镜像，浏览器必要时使用 `Ctrl+F5` 强制刷新。
