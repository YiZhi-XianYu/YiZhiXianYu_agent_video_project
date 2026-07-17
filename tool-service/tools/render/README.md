# FFmpeg Render Tool

将已经校验的 Timeline 编译为受控 FFmpeg Filter Graph，并生成最终视频。

## 输出

成片、缩略图、渲染日志、编码参数和完整 Artifact 血缘。

## 安全边界

只接受类型化 Timeline；命令参数由内部编译器生成，不能执行 Planner 或用户提供的任意命令字符串。

