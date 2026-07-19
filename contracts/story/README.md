# Story Plan Contract

`story-plan.schema.json` 定义 Ranking 与 Highlight/Timeline 之间的确定性叙事中间表示。

当前只允许 `TRAVEL_JOURNEY_V1`，节拍顺序固定为 `HOOK -> INTRO -> JOURNEY -> CLIMAX -> ENDING`。每个 Shot 必须来自同一 `SHOT_RANKING` 候选集合，时间范围不得越过源 Shot，全部节拍必须精确填满目标时长。

