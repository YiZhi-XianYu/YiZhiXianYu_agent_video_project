# Vision Contracts

Stage 6 语义理解阶段的视觉分析数据合约。

## 合约清单

| 合约 | 用途 |
|------|------|
| `scene-tags.schema.json` | CLIP 零样本场景分类标签 |
| `object-tags.schema.json` | CLIP 零样本物体检测标签 |
| `person-tags.schema.json` | CLIP 零样本人物检测标签 |

三个合约共享相同的顶层结构：`schemaVersion`、`modelName`、`sourceAssetId`、`sourceShotListArtifactId`、`shotCount`、`shots`。

每个 `shots[]` 元素包含 `shotId`、`sourceAssetId`、`keyframeArtifactId`、`index` 以及对应的标签数组。

所有标签均由 `vision.scene-classify`、`vision.object-detect`、`vision.person-detect` 三个 Tool 输出。
