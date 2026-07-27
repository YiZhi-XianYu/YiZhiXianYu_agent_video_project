# Tool Manifest Schema

本目录定义 Tool 名称、语义化版本、输入输出 Schema、执行模式、资源等级、超时、缓存、取消和健康检查等元数据。

Java Tool Registry 和 Python 注册器应使用同一 Schema，确保 Planner 只选择已启用且兼容的 Tool。

## Runtime resource groups

`resourceClass` remains the portable capacity hint exposed to Java. Python may
also declare `resourceGroup` to enforce host-specific concurrency without
changing the HTTP Tool API:

- `LIGHT`: JSON, ranking, planning and timeline work.
- `MEDIA`: ordinary FFmpeg probing, proxy generation and shot detection.
- `MODEL`: CLIP/VLM/Whisper inference; memory-heavy and mutually exclusive.
- `RENDER`: final FFmpeg composition; memory-heavy and mutually exclusive.

The production scheduler derives a safe fallback group from `resourceClass`
when `resourceGroup` is absent. This keeps older manifests compatible.

