# 第十二阶段交接：服务器部署与资源感知执行

> 日期：2026-07-27  
> 范围：阿里云 ECS 生产部署、Python 执行稳定性、模型下载与 Render OOM 收口  
> 边界：Java 与 Python 继续通过 HTTP Tool API 解耦；Artifact 不可变与血缘规则未改变

## 1. 阶段结果

第十二阶段完成了首个阿里云 ECS 部署，并针对 4 核 8 GiB、40 GiB
实例暴露出的真实问题完成代码级修复：

1. 新增 Caddy、生产 Compose、环境变量模板和阿里云部署文档；
2. Debian、PyPI 和 Hugging Face 镜像源改为可配置参数；
3. Python Execution Store 继续使用持久化 SQLite；
4. 执行器从固定 4 线程升级为资源感知调度；
5. CLIP/Whisper 与最终 Render 不再同时占用重资源；
6. 模型任务结束后释放进程内引用并调用 `malloc_trim(0)`；
7. Whisper 转写按 segment 时间持续上报进度；
8. 连续进程崩溃的执行会收敛为可重试失败，不再无限恢复；
9. Render 限制 FFmpeg 滤镜和编码线程，并保留退出码与 stderr；
10. Tool Service 生产镜像正式包含共享 LLM Contract Schema。

## 2. 线上故障证据

首次真实工作流中，CLIP 和 Whisper 模型下载后，Python 容器内存约为
2.4 GiB。最终 Render 启动 FFmpeg 后，内核连续记录：

```text
Memory cgroup out of memory
Killed process (ffmpeg)
ffmpeg anon-rss about 2.7-2.9 GiB
```

旧容器限制为 5 GiB，因此常驻模型与 FFmpeg 相加超过 cgroup 上限。
该故障不是滤镜语法错误，也不是工作流死锁。

同时确认了三个部署问题：

- `deb.debian.org` 在 ECS 上连接超时，阿里云镜像约 16.9 MiB/s；
- `files.pythonhosted.org` 和 Hugging Face 首次下载不稳定；
- 原 Tool Service 镜像缺少 `/contracts/llm`，导致 Story Plan Schema 警告。

## 3. 资源调度模型

Tool Manifest 新增可选 `resourceGroup`，旧 Tool 可由 `resourceClass`
安全推导：

| 分组 | 示例 | 权重 | 默认并发 |
|---|---|---:|---:|
| LIGHT | Ranking、Story Plan、Timeline | 0 | 3 |
| MEDIA | Probe、Proxy、Shot Detect | 1 | 2 |
| MODEL | VLM/CLIP、Whisper | 2 | 1 |
| RENDER | 最终视频渲染 | 2 | 1 |

重资源总容量为 2。两个 MEDIA 可以并行；MODEL 或 RENDER 各自占满容量。
排队任务保持 `QUEUED`，拿到资源后才进入 `RUNNING`。

## 4. 崩溃恢复

SQLite 中的 `QUEUED` 任务在进程重启后继续排队。`RUNNING` 任务允许用
同一 execution ID 恢复一次；如果恢复后再次随进程退出，下一次启动会
写入：

```text
code = EXECUTION_RECOVERY_EXHAUSTED
retryable = true
```

Java 可按既有重试策略继续收敛，不会无限轮询僵尸执行。

## 5. Render 改进

- `-filter_threads 1`
- `-filter_complex_threads 1`
- x264 `-threads 2`
- stderr 使用文件流，避免未读取 pipe 堵塞；
- 失败保存退出码、信号和最多 8 KiB 原始诊断；
- signal 9 无 stderr 时明确提示可能为内存或容器限制。
- Tool Service 镜像安装 Noto CJK 字体，字幕烧录明确使用
  `Noto Sans CJK SC`，避免中文被渲染成方框。

Timeline、BGM、字幕输入契约和 `video.render@1.1.0` 版本未改变。

## 6. 生产默认配置

```dotenv
TOOL_MAX_WORKERS=4
TOOL_LIGHT_LIMIT=3
TOOL_MEDIA_LIMIT=2
TOOL_MODEL_LIMIT=1
TOOL_RENDER_LIMIT=1
TOOL_HEAVY_LIMIT=2
TOOL_MAX_RECOVERIES=1
RELEASE_MODELS_AFTER_EXECUTION=true
```

模型缓存仍挂载到 `/opt/agent-video/data/huggingface`，释放内存不会触发
重新下载。

## 7. 验证结果

- Python 完整测试：71 passed；
- Java Maven 测试：通过，退出码 0；
- 生产 Compose 静态校验：通过；
- 生产服务清单：mysql、control-plane、caddy、tool-service；
- Tool Service 生产镜像：实际构建通过；
- 镜像自检：Manifest 资源组与两个 LLM Schema 均存在；
- Debian/PyPI 阿里云镜像：实际构建下载成功；
- 真实 ECS 故障定位：确认 Render 为 memory cgroup OOM。

## 8. 尚需服务器验收

代码提交并推送后，服务器需要恢复临时手改、拉取正式提交、重建
Tool Service，然后用原最小素材重新发起全新 Workflow，确认：

1. VLM 和转写依次执行且进度持续变化；
2. 转写完成后 Python 容器内存显著回落；
3. Render 不再出现内核 OOM；
4. 成片可预览和下载；
5. 容器重启后执行记录仍可恢复；
6. 日志不再出现 LLM Schema 缺失警告。
7. 中文语音生成的字幕能显示中文，而不是方框字形。

在完成这次真实服务器 Workflow 前，第十二阶段代码已完成，但生产端到端
验收仍应标记为待验证。

## 9. 人工故事编辑交互收口

后续在本地联调中继续收口了人工 Story Plan 编辑体验：

- “添加镜头”不再静默选择第一个候选，而是显示关键帧、时长、评分和素材来源，
  由用户明确选择后加入指定故事段；
- “保存版本”改名为“保存故事方案版本”，支持填写版本名称并显示未保存状态；
- 页面明确说明版本只是 Story Plan 数据快照，不会复制原素材、任务、Timeline、
  Workflow 状态或渲染成片；
- “确认故事安排”改名为“应用方案并继续执行”，只有该操作才会保存当前方案并
  从当前 Workflow 的高光选择节点开始重新执行下游任务；
- 锁定仅作为当前编辑会话的防误操作状态，不再错误地标记为可持久化修改。
- 故事编辑器内新增历史版本选择与载入功能；载入只替换当前编辑内容，保存时生成
  新的 Story Plan 快照，不会覆盖不可变 Artifact 或直接启动 Workflow；
- 版本名称输入框与编辑器统一使用深色表面色调；
- 镜头视频预览启用原生播放控制和声音。浏览器禁止有声自动播放时，用户可通过
  播放控件启动，片段仍按镜头起止时间循环。

## 10. Story Gate 原 Workflow 恢复与 Render 时间基修复

人工 Story Plan 的“应用方案”语义已纠正：不再创建 `CUSTOM_PLAN_RENDER`
Workflow，而是在原 Workflow 内完成以下操作：

1. 将编辑后的 Story Plan 写为由原 `story_plan` Task 产生的新不可变
   `STORY_PLAN` Artifact，旧 Artifact 保留以维持完整血缘；
2. 重置 `highlight_selection`、`timeline_compose` 及其所有下游 Task，确保新的
   Story Plan 会真实参与 Highlight、Timeline、BGM、字幕与 Render；
3. 完成 `gate_story_edit` 并恢复原 Workflow，页面继续监控同一个 Workflow ID；
4. 输入解析按 producer Task 与 Artifact 类型选择最新 Artifact，避免旧 Story Plan
   与新 Story Plan 同时发送给 Python Tool。

Timeline Gate 使用相同语义：编辑后的 Timeline 写为原 `timeline_compose` Task 的新
不可变 `TIMELINE` Artifact，随后重置 BGM、字幕与 Render 等下游 Task，并在同一
Workflow ID 下继续执行。页面不再跳转或创建 `CUSTOM_TIMELINE_RENDER` Workflow。

Timeline 预览页同时补齐人工调整体验：每个切片可以按 `sourceInMs/sourceOutMs`
播放代理视频并保留原声，通过明显的拖动手柄直接调整切片顺序；拖动完成后会重新
计算各 Clip 的 Timeline 起止时间和转场重叠。原先过小的前移/后移按钮，以及面向
实现细节的 `in/out` 毫秒输入框已从界面移除；底层精确裁剪数据保持不变。

本地 Render 失败记录确认根因是 `xfade` 两侧时间基分别为 `1/1000000` 与
`1/30`。每个视频片段现统一经过 `fps=<fps>,settb=expr=1/<fps>` 后再进入
`concat/xfade`。专门的 Render 回归测试通过（14 passed）；需要重建 Tool Service
镜像后，该修复才会进入实际运行容器。
# 第十二阶段补充：短素材 Story Plan 适配

> 2026-07-28 本地回归补充

本地项目“转场test”使用约 12.215 秒素材并请求 10 秒成片时，Story Plan 连续失败。根因不是素材损坏或
LLM 接口不可用，而是固定五段预算与短镜头边界无法同时满足：LLM 方案出现重复镜头或空故事段后会安全
回退，但确定性方案仍按原始比例校验每段时长，因此总时长可满足时也可能因单段偏差失败。

修复后，Story Plan 在不重复、不拉伸源镜头的前提下，将受素材边界限制的五段目标预算重新对齐实际选片；
若唯一素材总时长不足请求时长，则同时把 Story Plan 总时长收缩到真实可用长度。LLM 编译路径和确定性
回退路径使用同一规则，Artifact 仍以新对象写入，血缘与不可变约束未改变。

验证结果：

- 真实素材：`测试视频.mp4`，探测时长约 12.215 秒，镜头检测得到 5 个镜头；
- 10 秒目标真实 Tool API：`planning.story-template@1.0.0` 成功，五段合计严格 10 秒；
- 30 秒目标真实 Tool API：自动收缩为 12.236 秒，不重复或拉伸镜头；
- Python 完整测试：73 passed；
- 新真实 Workflow `338fd09e-53c5-4021-8100-66ba931aa7cf` 的 `story_plan` 成功，Workflow 正确暂停在
  `gate_story_edit`，等待人工故事安排审核。

## 第十二阶段补充：BGM Provider 与人工试听选择

BGM 流程从“自动使用第一首音乐”调整为明确的候选与决策链路：

1. `audio.bgm-select@1.0.0` 同时接收 `STORY_PLAN` 与 `TIMELINE`，按故事情绪、目标时长和 Provider
   结果生成最多 3 个有序 `BGM_CANDIDATE` Artifact；
2. 非全自动 Workflow 在 `bgm_select` 后进入 `gate_bgm_review`，前端展示排名、名称、作者、时长、
   匹配分、Provider 和来源信息，并使用 Artifact Content API 提供音频试听；
3. 用户确认候选后，Java 在原 `bgm_select` Task 下追加新的不可变 `BGM_AUDIO` 与 `BGM_SELECTION`
   Artifact，再在同一 Workflow 中继续 Render；
4. 用户选择无 BGM 时写入 `mode=NONE` 的 `BGM_SELECTION` Artifact。Render 输入解析会服从最新决策，
   不会误用历史 `BGM_AUDIO`；
5. 全自动模式通过 `autoSelect=true` 自动选择第一名；Provider 不可用或没有候选时仍可无 BGM 安全降级；
6. Provider 使用独立抽象，当前实现 Jamendo 与本地曲库回退。Jamendo Client ID 仅通过 `.env` 注入，
   音频缓存写入已被 Git 忽略的运行时目录，不向仓库提交音乐文件或凭据。

配置项：`MUSIC_PROVIDER`、`JAMENDO_CLIENT_ID`、`MUSIC_CANDIDATE_LIMIT`。本地未配置 Jamendo Client ID，
因此当前只能验证无候选降级；配置后无需改代码即可启用真实搜索与试听。

验证结果：Python 完整测试 76 passed；Java 完整测试通过；前端生产构建通过；Compose 静态校验通过。

### 本地真实无 BGM 降级验收（2026-07-28）

使用项目“转场test”的真实手动 Workflow
`3e313a15-e666-43d3-9e1f-76ca5400db9d` 完成了无 Jamendo Client ID 场景的端到端验收：

- `audio.bgm-select@1.0.0` 成功结束且未返回候选，页面正确显示“当前没有可用音乐候选”；
- 用户点击“无 BGM 继续”后，在原 `bgm_select` Task 下创建新的不可变
  `BGM_SELECTION` Artifact，内容为 `mode=NONE`；
- `video.render@1.1.0` 没有收到历史 `BGM_AUDIO`，仍收到最新 Timeline 与字幕，成片元数据为
  `hasBgm=false`、`hasSubtitles=true`、`durationMs=10000`；
- Workflow 保持原 ID，最终 13/13 节点成功并完成最终审核；浏览器控制台无 error/warn；
- 重渲染产生多个 `RENDERED_VIDEO` Artifact 时，Workflow 快照现在按创建时间倒序返回，前端预览和下载
  始终指向最新不可变成片，而不是历史版本。

验收中同时修复了 Gate 调度顺序：一个下游 Task 同时依赖多个带 Gate 的上游时，调度器会按
Workflow Definition 顺序寻找“尚未完成”的 Gate。已完成的较早 Gate 不再遮蔽后续 Gate，避免先跳过
BGM 审核、所有任务结束后又倒退显示 Story Gate。新增 Java 回归测试覆盖该情况以及最新 Artifact 排序。

### 真实 BGM Render 与时间线转场修复（2026-07-28）

接入 Jamendo 并选择真实音乐后，Workflow `bd396d89-6399-4d79-b106-46c122ec9481` 的 Render
暴露出 BGM 专属滤镜错误：音频链标签已经包含方括号，混音阶段再次包裹后生成
`[[ac3]][bgm]amix=...`，FFmpeg 因非法 filter graph 退出。修复后混音链保持
`[ac3][bgm]amix=...`。使用该失败 Workflow 原有的 Timeline、BGM 和字幕输入真实重放成功，生成约
5.96 MB、7 秒的 H.264/AAC 成片，元数据为 `hasBgm=true`。

时间线编辑器的转场交互也重新对齐了数据语义：Timeline 使用的是“进入当前片段前的
`transitionIn`”，旧界面却把选择器放在片段卡片内部/之后，容易被理解为离开当前片段的效果；首片段选择
交叉溶解还会被 Validator 安全改回 CUT，因此表现为选择无效。现在：

- 两个片段之间显示独立“片段间转场”选择器，直接修改后一个片段的 `transitionIn`；
- 支持硬切、淡入和交叉溶解，交叉溶解默认 500 ms，淡入默认 300 ms；
- 首片段只显示“直接开始/淡入”，不再提供语义上无效的交叉溶解；
- Timeline 重新计算继续根据交叉溶解时长计算重叠区间和总时长。

真实混合转场验收还发现：`concat` 输出使用 AVTB (`1/1000000`)，若后续再接 `xfade`，会与下一个
标准化为 `1/30` 的片段时间基冲突。现在每次视频 concat 后都会再次执行 `fps=<fps>,settb=1/<fps>`，
保证后续交叉溶解两侧时间基一致。使用真实素材与真实 BGM 渲染
`FADE → CROSS_DISSOLVE → FADE → CROSS_DISSOLVE → CUT` 成功，成片 6.4 秒、约 5.75 MB，
`hasBgm=true`。

新增 BGM 标签和混合转场时间基回归测试，Render 专项测试 16 passed；Python 完整测试 77 passed；
前端生产构建通过。

## 第十二阶段补充：动态助手“初雪”

前端左侧栏新增全局动态助手“初雪”，位置位于主导航下方、用户卡片上方。角色复用已验证的
Spine 3.8.99 骨骼资源和最小 3.8 Web Player runtime，不复制 Demo 的虚拟环境、依赖目录或整套
Spine 源码。角色层级高于普通页面内容，气泡高于角色，现有 `z-50` 及以上弹窗仍覆盖角色与气泡。

当前启用两种状态：

- 默认循环播放 `Relax` 待机动画；
- 待机 45～90 秒后进入 `Sleep`，每次睡眠随机持续 22～45 秒后自然醒来；
- 睡眠期间点击角色或用键盘 Enter/Space 可以提前唤醒；
- Spine `defaultMix` 与组件位移/明暗过渡共同保证状态切换平滑；系统启用减少动态效果时取消额外
  CSS 过渡并将 Spine 混合时间设为 0。

登录后的 AppShell 启动全局 Workflow 完成观察器，每 5 秒读取当前用户各项目的 Workflow 历史。
初始化只建立基线，不把历史成功记录误报为新完成；仅当 `MULTI_ASSET_ANALYSIS` 从非成功状态进入
`SUCCEEDED`，或本次页面会话中新建的 Workflow 已经完成时，通知初雪：

1. 若正在睡觉，立即回到待机；否则保持当前状态；
2. 头顶只显示一个“视频做好了”气泡，5 秒后消失；
3. 5 秒内多个 Workflow 完成时复用同一个气泡并刷新消失计时，不叠加气泡；
4. 页面从后台恢复时会立即补查状态，页面路由切换不会重建角色或丢失定时器。

初雪资源位于 `web-app/public/characters/chuxue/`，Spine 3.8 最小 runtime 与许可证位于
`web-app/public/vendor/spine-3.8/`。实现入口为 `ChuxuePet.vue`、`stores/chuxue.ts` 和
`useWorkflowCompletionWatcher.ts`。

本地验收结果：

- Docker `control-plane` 生产构建通过，`vue-tsc` 与 Vite 均无错误；
- 浏览器实际加载 1 个透明 Spine Canvas，待机动画正常，随机入睡真实触发，点击后恢复待机；
- Spine 内部控制按钮已隐藏并从可访问树移除，睡眠时只保留一个明确的唤醒按钮；
- 角色区域不遮挡主导航和用户卡片；视频预览弹窗 `z-index: 50` 正确覆盖侧栏 `z-index: 40`；
- 页面切换后角色保持为 AppShell 全局单实例，浏览器控制台无 warning/error；
- 本轮未人为启动新 Workflow，因此没有为测试气泡而制造业务数据；完成事件去重、睡眠唤醒和 5 秒
  单气泡刷新由全局观察器与 Pinia 状态机的同一生产代码路径负责。

## 第十二阶段补充：Highlight 链式替换去重修复

2026-07-29 本地五素材 Workflow 在 `timeline.compose@1.1.0` 失败，真实错误为第 5 个
Clip 的 `clipId` 与 `shotId` 重复。血缘检查确认确定性 Story Plan 已生成五个不同镜头，重复由
`decision.highlight-select@1.0.0` 应用多条 LLM 调整时产生：一条与已选镜头冲突的替换被拒绝后，
占用集合错误记录了候选 ID，而不是实际保留的镜头 ID，导致后一条链式替换被误判为安全。

修复后 Highlight 编译器按实际保留的 `shotId` 更新占用集合，并禁止把已存在于 Story Plan 的
其他镜头作为替换目标。新增回归测试覆盖“CLIMAX 替换到 HOOK 已用镜头、ENDING 再替换到原
CLIMAX 镜头”的真实冲突组合，保证输出镜头 ID 唯一并在进入 Timeline 前消除该类重复。

验证结果：专项冲突测试通过；Python 全量测试 79 passed；使用失败 Workflow 原有的 Story Plan、
Ranking 与 LLM 调整只读重放后得到 5 个镜头和 5 个唯一 `shotId`。

## 第十二阶段补充：规划边界校验与素材库软删除

Highlight 的 LLM 调整结果现在区分三类状态：`llmSuggestions` 保存模型给出的全部建议，
`llmRefinements` 只保存实际应用成功的替换，`rejectedLlmRefinements` 保存拒绝项及结构化原因。
策略标识同步区分确定性编译、仅经 LLM 审阅和实际完成 LLM 调整，避免界面或审计把“模型建议过”
误报为“方案已经修改”。时长再分配也只依据真正生效的替换计算。

Java Control Plane 在保存、应用和恢复人工 Story Plan 前执行完整 Payload 校验，包括五段角色、镜头
全局唯一性、源区间、选片时长、角色对应关系、目标总时长与最大镜头数。人工 Timeline 在进入
Workflow 前同样校验画布、轨道、Clip/Shot 唯一性、源区间、轨道连续性、转场约束、总时长以及
音频和字幕基础结构。校验同时接入 Service 层，不能通过绕过 Controller 提交非法数据。

项目素材库为每个视频新增“删除素材”按钮。删除采用 `Asset.status=REMOVED` 软删除语义：

- 工作台列表和后续新建 Workflow 只使用 `AVAILABLE` 素材；
- 已开始和历史 Workflow 仍按原 ID 读取素材，已有 Artifact、数据库行、源文件和完整血缘均不删除；
- 删除前明确提示上述语义，删除期间按钮禁用，删除成功后刷新素材列表并关闭对应预览；
- 删除接口先校验当前用户的项目权限，并校验素材确实属于目标项目。

验证结果：Python 全量测试 79 passed；Java 全量测试通过；`control-plane` 与 `tool-service` Docker
生产构建通过，其中前端 `vue-tsc`、Vite 与 Java package 均成功。本地容器使用新镜像重建后，
项目页实际显示 5 个素材删除按钮；素材预览与按钮布局正常，浏览器控制台无 warning/error。

## 第十二阶段补充：本地 BGM 上传与受控循环播放

Jamendo 在大陆 ECS 出现间歇性请求失败时，非全自动 Workflow 现在仍会停在 `gate_bgm_review`，
不会因为在线 Provider 失败直接跳过人工选择。BGM 审核页无论是否取得在线候选都提供本地音频上传，
支持 MP3、WAV、M4A、AAC、OGG 和 FLAC，单文件最大 100 MB。

浏览器读取音频时长并与 Timeline 时长比较：若音频更短，必须明确选择“循环至视频结束”或“只播放
一次”后才能提交；音频不短于视频时默认只播放一次。Java 将上传文件保存为原 `bgm_select` Task
产生的新不可变 `BGM_AUDIO` Artifact，并用 `BGM_SELECTION` 保存 `UPLOADED`、`ONCE/LOOP`、
文件名和时长。Workflow 保持原 ID，随后继续原 Render 节点。

播放模式不会作为任意 FFmpeg 内容传递：Java 只允许 `ONCE/LOOP` 两个枚举值，Python Render
再次校验。循环模式先用 FFprobe 读取真实时长和采样率，再为 `aloop` 设置一轮音频的有界采样数，
最后裁切至成片时长；单次模式播放完成后保留视频原声，不自动重复。Artifact 文件、哈希、选择记录
和 Render 元数据均保留完整血缘。

验证结果：Python 全量测试 82 passed，其中 Render 专项 19 passed；Java 全量测试通过；前端
`vue-tsc`、Vite 和 Control Plane Docker 生产构建通过。

## 第十二阶段补充：故事编排添加镜头 Payload 修复

人工故事编排中从候选列表添加镜头后，前端原先只追加 `shotIds/shots`，没有同步更新 Beat 的实际
时长，并且新镜头缺少 Java Payload Validator 强制要求的 `selectionReasons`。保存版本或应用方案时，
因此会被后端以 `actualDurationMs` 不匹配或 `selectionReasons must be an array` 拒绝。

修复后编辑状态在每次添加、删除和排序后按 `shotIds` 重建镜头顺序，并重算每段及全片时长；添加
镜头会保留完整素材 ID、代理 Artifact ID 和源区间，同时写入受控的人工编辑原因。短于 600 ms 的
候选不会再出现在可添加列表中。保存或应用前还会再次规范化所有镜头的整数时间、选择区间、时长、
排名、原因数组和故事角色，并据此生成 `actualDurationMs`、`targetDurationMs` 与 `maxShots`，使历史
版本或浏览器中的旧编辑状态也不能提交不一致 Payload。后端严格校验保持不变。

验证结果：前端 `vue-tsc --noEmit` 通过；Control Plane Docker 生产构建通过，其中容器内 `npm ci`、
`vue-tsc`、Vite 和 Java package 均成功；`git diff --check` 通过。

## 第十二阶段补充：BGM 多样化推荐与换一批

原 BGM 推荐把五段式 Story Plan 中权重最高的 `CLIMAX` 固定映射为 `epic`，再使用 Jamendo 的
`order=relevance` 取前三名。由于每个方案都包含高潮段，相同检索条件长期稳定返回同一批歌曲，页面
虽然显示“按情绪推荐”，实际没有利用各故事段时长和镜头语义。

现在 Tool Service 根据五段的实际时长分布与镜头 `selectionReasons` 构建受控音乐画像，生成至多三个
白名单标签，例如 energetic、upbeat、calm、serene、epic、cinematic、acoustic 和 instrumental。
Jamendo 针对多个画像标签扩大搜索池，并按以下因素重新排序：

- Provider 原始相关度；
- 音乐与成片的时长接近程度；
- 主情绪和辅助标签命中；
- 器乐曲加分；
- 相同作者的候选多样性惩罚；
- 当前项目近期已展示曲目的显著降权；
- 由 Workflow ID 派生的稳定微小扰动，保证同一批次可审计且不同项目不总得到相同顺序。

BGM 审核页新增“换一批”。点击后不会创建新 Workflow，也不会删除或覆盖旧候选：Java 在原
`bgm_select` Task 上写入受控的 `recommendationBatch`、`recommendationSeed` 和最多 100 个历史
`providerTrackId` 排除项，重置并重新执行该节点；完成后 Workflow 再次停回原 BGM Gate。前端只展示
最新 `candidateSetId` 对应的候选，但历史候选 Artifact 仍不可变保留。每首歌曲同时展示音乐画像标签和
推荐原因。本地上传、无 BGM、安全降级与原 Workflow 继续渲染逻辑保持不变。

验证结果：BGM 专项测试 4 passed；Python 全量测试 83 passed；Java 全量测试通过；Tool Service 与
Control Plane Docker 生产构建通过，其中前端 `vue-tsc`、Vite 和 Java package 均成功。
