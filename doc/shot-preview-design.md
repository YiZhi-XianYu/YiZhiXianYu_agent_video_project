# 镜头预览功能设计方案

> 版本：1.0 | 日期：2026-07-27 | 状态：待实现

---

## 1. 需求概述

在"人在回路"审核界面中，用户面对镜头列表（`ShotRankingReview`）和故事编辑器（`StoryEditor`）时，仅看到 `shotId` 和数字评分，无法直观判断镜头内容。需要增加预览能力：**点击镜头标签 → 在合理位置弹出该镜头的关键帧图像或视频片段**。

## 2. 使用场景

| 场景 | 组件 | 触发方式 | 预览内容 |
|------|------|----------|----------|
| Gate 1：镜头排序审核 | `ShotRankingReview.vue` | 点击镜头行 | 关键帧图片（主） + 代理视频片段（可选） |
| Gate 2：故事安排编辑 | `StoryEditor.vue` | 点击 shot ID 文本 | 同上 |
| Gate 3：时间线预览 | `TimelinePreview.vue` | 可选，暂不实现 | — |

## 3. 数据来源分析

### 3.1 现有数据流

```
WorkflowMonitorPage.syncGate()
  └→ loadArtifactJson(nodeKey, artifactType)  // 从后端 Artifact URL 拉 JSON
       └→ mapShotScores() / mapStoryPlan() / mapTimeline()
            └→ reviewStore.setShotScores() ...
```

关键事实：
- `ShotScore` 当前只有评分维度，**没有** `keyframeUrl` 或 `videoUrl`
- `StoryBeat` 当前只有 `shotIds: string[]`，**没有** 任何 URL 信息
- 关键帧和代理视频 URL 需要通过后端 Artifact API 动态获取

### 3.2 关键帧 Artifact

- **生产任务**：`video_shot_detect`（nodeKey） → tool: `video.shot-detect`
- **Artifact**：`type: "SHOT_LIST"` → fetch JSON → 每条 shot 含 `keyframeArtifactId`
- **关键帧图片路径**：`{artifact_root}/{keyframeArtifactId}/keyframe.jpg`
- **后端暴露的 Content URL**：通过 `ArtifactSnapshot.contentUrl` 获取（Java 侧 `ArtifactResolver` 映射）

### 3.3 代理视频 Artifact

- **生产任务**：`video_proxy_generate`（nodeKey） → tool: `video.proxy-generate`
- **Artifact**：`type: "VIDEO_PROXY"` → `contentUrl` 指向代理视频文件

---

## 4. 架构设计

### 4.1 组件树

```
WorkflowMonitorPage.vue
├── ShotRankingReview.vue          ← 新增：点击行 → 弹出 ShotPreviewPanel
│   └── ShotPreviewPanel.vue  (new) ← Teleport 到 body，绝对定位浮层
├── StoryEditor.vue                ← 新增：点击 shotId → 弹出 ShotPreviewPanel
│   └── ShotPreviewPanel.vue  (new)
├── TimelinePreview.vue
└── FinalReview.vue
```

### 4.2 新组件：`ShotPreviewPanel.vue`

**职责**：浮层预览面板，展示单镜头关键帧或视频片段。

**接口**：

```typescript
// Props
interface ShotPreviewPanelProps {
  /** 镜头唯一标识 */
  shotId: string
  /** 关键帧图片 URL（优先级最高，始终展示） */
  keyframeUrl: string | null
  /** 代理视频 URL（可选），提供后展示可播放片段 */
  videoUrl: string | null
  /** 镜头的起止时间（毫秒），用于视频片段定位 */
  startMs: number
  endMs: number
  /** 锚点 DOM 元素，用于浮层定位 */
  anchorEl: HTMLElement | null
  /** 是否可见 */
  visible: boolean
}

// Events
interface ShotPreviewPanelEmits {
  close: []
}
```

**行为**：
- 使用 `<Teleport to="body">` 渲染到 body 层级，避免被父容器 overflow:hidden 裁剪
- 根据 `anchorEl.getBoundingClientRect()` 计算浮层位置：
  - 水平方向：优先在锚点右侧展示；若右侧空间不足（距视口右边缘 < 380px），则改为左侧
  - 垂直方向：垂直居中对齐锚点，限制在视口内
- 浮层尺寸：360×240（图片模式）/ 360×280（视频模式）
- 关键帧优先：`keyframeUrl` 不为空时展示图片；仅当 keyframe 缺失时才回退到视频
- 视频模式下：自动 seek 到 `startMs`，循环播放片段（startMs → endMs）
- 点击浮层外部或按 Escape → 关闭
- 关闭时暂停视频播放

### 4.3 新增 Composable：`useShotPreviewData.ts`

**职责**：封装 Artifact 查找 + JSON 获取逻辑，将 `shotId` 映射为 `{ keyframeUrl, videoUrl, startMs, endMs }`。

```typescript
// 返回值
interface ShotPreviewData {
  keyframeUrl: string | null
  videoUrl: string | null
  startMs: number
  endMs: number
}

// 用法
function useShotPreviewData(workflowRunId: string): {
  getPreviewData: (shotId: string) => Promise<ShotPreviewData>
  loading: Ref<boolean>
}
```

**实现逻辑**：
1. 从 `workflowStore.tasks` 中找到 `nodeKey === 'video_shot_detect'` 的 task
2. 找到其 `type === 'SHOT_LIST'` 的 artifact，fetch JSON
3. 从 JSON 中找到对应 shotId 的 `keyframeArtifactId`
4. 构造 `keyframeUrl = artifact.contentUrl + "/keyframe.jpg"`（或直接用 Artifact API）
5. 同理，从 `nodeKey === 'video_proxy_generate'` 的 task 找到 `type === 'VIDEO_PROXY'` 的 artifact
6. 用其 `contentUrl` 作为视频源

> **备选方案**：也可以在后端 Java 侧新增一个 API `GET /api/v1/workflow-runs/{id}/shot-preview/{shotId}`，直接返回 `{ keyframeUrl, proxyUrl, startMs, endMs }`，减少前端多次 fetch。如果前端复杂度太高，可后续切到后端方案。

---

## 5. 数据模型扩展

### 5.1 `ShotScore` 类型扩展

```typescript
// shared/types.ts 中 ShotScore 增加可选字段
export interface ShotScore {
  // ... 现有字段不变
  /** 关键帧 Content-URL（由 syncGate 阶段预加载） */
  keyframeUrl?: string
  /** 对应代理视频 Content-URL */
  proxyVideoUrl?: string
  /** 镜头起始时间（毫秒） */
  startMs?: number
  /** 镜头结束时间（毫秒） */
  endMs?: number
}
```

### 5.2 `syncGate()` 预加载增强

在 `WorkflowMonitorPage.vue` 的 `syncGate()` 中，`gate_shot_ranking` 分支增加预加载逻辑：

```typescript
// gate_shot_ranking 分支（伪代码）
if (gate.gateKey === 'gate_shot_ranking') {
  // 原有：加载 SHOT_RANKING 评分数据
  const rankingPayload = await loadArtifactJson('shot_ranking', 'SHOT_RANKING')
  const scores = mapShotScores(rankingPayload)
  
  // 新增：加载 SHOT_LIST（含 keyframeId 和时间信息）
  const shotList = await loadArtifactJson('video_shot_detect', 'SHOT_LIST')
  const shotMetaMap = new Map(
    (shotList.shots ?? []).map((s: any) => [s.shotId, {
      keyframeArtifactId: s.keyframeArtifactId,
      startMs: s.startMs,
      endMs: s.endMs,
    }])
  )
  
  // 新增：获取代理视频 URL
  const proxyArtifact = findArtifact('video_proxy_generate', 'VIDEO_PROXY')
  const proxyUrl = proxyArtifact?.contentUrl ?? null
  
  // 合并数据
  const enrichedScores = scores.map(shot => {
    const meta = shotMetaMap.get(shot.shotId)
    return {
      ...shot,
      keyframeUrl: meta ? `${proxyArtifact?.contentUrl}/../../../${meta.keyframeArtifactId}/keyframe.jpg` : undefined,
      // 注：实际 keyframeUrl 需要从后端 Artifact API 获取，上面是示意
      proxyVideoUrl: proxyUrl,
      startMs: meta?.startMs,
      endMs: meta?.endMs,
    }
  })
  
  reviewStore.setShotScores(enrichedScores)
}
```

### 5.3 更好的方案：后端 Shot Preview API

前端数据获取最高效的方式是在后端新增一个轻量查询接口：

```
GET /api/v1/workflow-runs/{runId}/shot-previews
  → [{ shotId, keyframeUrl, proxyVideoUrl, startMs, endMs }, ...]
```

这样一次 API 调用即可获取所有镜头的预览数据，避免前端多次 fetch 和路径拼接。

---

## 6. 浮层定位算法

```
anchorEl.getBoundingClientRect() → { left, top, width, height }

panelWidth  = 360
panelHeight = 240 (image) | 280 (video)
gap         = 12   // 与锚点的间距

// 尝试右侧展示
x = anchor.right + gap
if (x + panelWidth > window.innerWidth - 16) {
  // 右侧空间不足，改为左侧
  x = anchor.left - gap - panelWidth
}

// 垂直居中锚点
y = anchor.top + anchor.height / 2 - panelHeight / 2
// 不超出视口
y = clamp(y, 8, window.innerHeight - panelHeight - 8)
```

---

## 7. 交互细节

| 操作 | 行为 |
|------|------|
| 点击镜头行（ShotRankingReview） | 该行高亮，弹出预览浮层 |
| 再次点击同一镜头行 | 关闭预览 |
| 点击另一个镜头行 | 关闭当前预览，打开新镜头的预览 |
| 点击 shot ID（StoryEditor） | 弹出预览浮层（定位到 shot ID 元素） |
| 按下 Escape | 关闭当前预览 |
| 点击浮层外部（backdrop click） | 关闭当前预览 |
| 视频自动播放 | 从 startMs 开始，到 endMs 后重新循环 |

---

## 8. 文件变更清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `web-app/src/components/ShotPreviewPanel.vue` | **新建** | 浮层预览组件 |
| `web-app/src/shared/types.ts` | 修改 | `ShotScore` 增加 4 个可选字段 |
| `web-app/src/features/review/ShotRankingReview.vue` | 修改 | 新增点击事件和预览集成 |
| `web-app/src/features/review/StoryEditor.vue` | 修改 | 新增点击事件和预览集成 |
| `web-app/src/features/workflow/WorkflowMonitorPage.vue` | 修改 | `syncGate()` 中增强数据预加载 |
| `web-app/src/stores/review.ts` | 不变 | 依靠 WorkflowMonitorPage 注入数据 |
| `web-app/src/shared/composables/useVideoPlayer.ts` | 不变 | 可复用（ShotPreviewPanel 内部使用原生 `<video>`） |

---

## 9. 风险与边界

- **关键帧缺失**：如果 `video_shot_detect` 任务失败或 Artifact 不可达，`keyframeUrl` 为 null，预览面板显示"关键帧不可用"，不回退到视频
- **代理视频缺失**：`proxyUrl` 为 null 时，仅展示关键帧图片（这就是主方案）
- **性能**：关键帧图片通常 < 200KB，浮层打开时延迟加载；不在 DOM 中保留隐藏的 `<img>` 标签
- **故事编辑器中 shotId 重复**：不同 beat 可能引用同一 shotId，预览不依赖 beat 上下文，无冲突

---

## 10. 后续扩展

- **TimelinePreview.vue 预览**：时间线条目同样可接入，成本低（复用同一组件和 composable）
- **多镜头对比**：未来可扩展为选中多个镜头后在浮层中并列展示
- **后端 API 优化**：如果前端多次 fetch 成为瓶颈，将预加载逻辑下沉到 Java 端单个 API

---

## 11. 补充设计：ShotGalleryView 画廊总览页

> 2026-07-27 补充：用户提出需要集中预览所有镜头的页面，便于直观扫描和快速筛选。

### 11.1 定位与交互流

画廊页作为 Gate 1（镜头排序审核）的**第一视图**，替代当前纯数据列表作为默认展示。用户先做视觉筛选，再进入精查。

```
Gate 1 激活时：
  ┌─────────────────────────────────────────┐
  │  [画廊视图] ← 默认            [列表视图]  │  ← 顶部切换 tabs
  │  ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐      │
  │  │ 📷  │ │ 📷  │ │ 📷  │ │ 📷  │      │
  │  │ #1  │ │ #2  │ │ #3  │ │ #4  │      │
  │  │ 92  │ │ 85  │ │ 45  │ │ 78  │      │
  │  │ ⭐× │ │ ✓  │ │ ✗  │ │ ✓  │      │
  │  └─────┘ └─────┘ └─────┘ └─────┘      │
  │  ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐      │
  │  │ ... │ │ ... │ │ ... │ │ ... │      │
  │  └─────┘ └─────┘ └─────┘ └─────┘      │
  └─────────────────────────────────────────┘
                 │ 点击某个卡片
                 ▼
      ┌──────────────────┐
      │  ShotPreviewPanel │  ← 浮层预览（大图/视频片段）
      └──────────────────┘
```

### 11.2 ShotGalleryView.vue 设计

**组件职责**：以网格形式展示所有镜头关键帧，支持快速扫视、标记和排序。

**布局**：
- 响应式网格：桌面 5 列 → 平板 3 列 → 手机 2 列
- 卡片尺寸：统一 200×140（图片区）+ 40px 信息栏
- 图片使用 `object-fit: cover`，16:9 比例
- 滚动区域最大高度 70vh，超出滚动

**每张卡片展示**：

```
┌──────────────────────┐
│                      │
│     关键帧图片       │  ← 200×112 (16:9)
│                      │
├──────────────────────┤
│ #1  │  92  │  ⭐  ✗  │  ← 40px info bar
└──────────────────────┘
  │      │      │  │
  │      │      │  └─ 排除按钮 (切换)
  │      │      └──── 强制入选 (切换)
  │      └─────────── rankScore（颜色编码）
  └────────────────── shot ID (截断 6 字符)
```

**状态视觉编码**：

| 镜头状态 | 边框 | 背景 | 角标 |
|----------|------|------|------|
| 正常入选 (selected) | `border-surface-600` | 默认 | — |
| 强制入选 (forced) | `border-accent ring-1 ring-accent/50` | `bg-accent/5` | 左上角星标 ⭐ |
| 已排除 (excluded) | `border-danger/30` | `bg-danger/5` | 半透明蒙层 + ✗ |
| 低分 (< 50) | `border-warning/30` | 默认 | 右下角 ⚠ |

**键盘快捷键**（在画廊视图中）：
- `← → ↑ ↓`：在卡片间移动焦点
- `空格`：切换选中（强制入选）
- `Delete` / `X`：切换排除
- `Enter`：打开浮层预览
- `Escape`：关闭浮层预览

**筛选栏**（顶部）：
```
[全部镜头 ▼] [排序: 评分降序 ▼] [搜索 shotId...]  [显示: 25/48]
```

筛选项：
- 全部镜头 / 已入选 / 已排除 / 低分(< 50)
- 排序：评分降序 / 评分升序 / 镜头序号 / 时长

**批量操作**：
- 按住 Shift 点击选中连续范围
- 按住 Ctrl/Cmd 点击多选
- 选中后底部浮现操作栏：「批量强制入选」「批量排除」「取消全部」

### 11.3 与 ShotRankingReview 的关系

`ShotRankingReview.vue`（列表视图）**保留**，与画廊视图通过顶部 tabs 切换：

```
      ┌──────────────────────────────┐
      │ 📷 画廊视图  |  📋 列表视图  │  ← tabs
      └──────────────────────────────┘
```

两个视图共享同一个 `reviewStore` 数据源，操作同步：
- 在画廊中标记"强制入选" → 列表视图同步高亮
- 在列表中排除 → 画廊中卡片变灰蒙层
- 点击"确认排名"按钮在两者中都可见，点击后统一提交

### 11.4 实现要点

**数据获取**：复用第 5 节设计的 `syncGate()` 预加载增强 — 在 `gate_shot_ranking` 激活时一次性加载所有 keyframe URL。

**性能考虑**：
- 关键帧图片使用懒加载 (`loading="lazy"`)
- 卡片使用 `v-for` 带 `:key="shot.shotId"`，Vue 虚拟 DOM diff 天然增量更新
- 超过 50 个镜头时使用虚拟滚动（`@vueuse/core` 的 `useVirtualList`，需安装依赖），否则直接渲染
- 图片加载失败时显示占位图标

**图片加载状态**：
- 加载中：显示 `Skeleton` 占位（复用现有 `Skeleton.vue`）
- 加载失败：显示图片破损图标 + "关键帧不可用"文案
- 加载成功：淡入动画

### 11.5 文件变更清单（增补）

| 文件 | 操作 | 说明 |
|------|------|------|
| `web-app/src/features/review/ShotGalleryView.vue` | **新建** | 画廊视图组件 |
| `web-app/src/features/review/ShotRankingReview.vue` | 修改 | 增加视图切换 tabs |
| 其余同第 8 节 | — | — |

### 11.6 分阶段实施

| 阶段 | 内容 | 优先级 |
|------|------|--------|
| Phase A | `ShotPreviewPanel.vue`（浮层预览） + `ShotScore` 类型扩展 + `syncGate` 预加载 | P0 |
| Phase B | `ShotGalleryView.vue`（画廊总览）+ 视图切换 tabs | P1 |
| Phase C | 键盘导航 + 批量操作 + 虚拟滚动 | P2 |
