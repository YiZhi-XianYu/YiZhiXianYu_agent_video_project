from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    host: str = "127.0.0.1"
    port: int = 8090
    ffprobe_path: str = "ffprobe"
    artifact_root: Path = Path("runtime/artifacts")
    callback_timeout_seconds: float = 10.0

    model_config = SettingsConfigDict(
        env_prefix="TOOL_SERVICE_",
        case_sensitive=False,
    )


settings = Settings()

