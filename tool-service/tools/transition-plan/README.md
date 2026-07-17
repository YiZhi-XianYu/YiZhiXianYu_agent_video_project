# Transition Planner Tool

根据相邻镜头的运动、颜色、节奏和情绪选择合适转场。

## MVP 范围

支持 CUT、FADE、CROSS_DISSOLVE 和 FLASH，并输出类型、时长与理由。

## 实现边界

参数必须来自受限目录，不允许生成任意 FFmpeg Filter 字符串。

