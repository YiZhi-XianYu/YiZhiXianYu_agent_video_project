from pathlib import Path

from dotenv import load_dotenv
from pydantic_settings import BaseSettings, SettingsConfigDict

# Load .env before Settings is instantiated so all env vars are available.
# This runs once at module import time.  If the file does not exist
# (e.g. in CI) load_dotenv silently returns False.
_project_root = Path(__file__).resolve().parent.parent.parent
load_dotenv(_project_root / ".env")


class Settings(BaseSettings):
    host: str = "127.0.0.1"
    port: int = 8090
    ffmpeg_path: str = "ffmpeg"
    ffprobe_path: str = "ffprobe"
    artifact_root: Path = Path("runtime/artifacts")
    artifact_storage_provider: str = "local"
    oss_endpoint: str = ""
    oss_region: str = ""
    oss_bucket: str = ""
    oss_access_key_id: str = ""
    oss_access_key_secret: str = ""
    execution_store_path: Path = Path("runtime/executions/tool-executions.sqlite3")
    callback_timeout_seconds: float = 10.0
    callback_retry_attempts: int = 3
    callback_retry_backoff_seconds: float = 0.5
    callback_publisher_interval_seconds: float = 1.0
    execution_max_workers: int = 4
    execution_light_limit: int = 3
    execution_media_limit: int = 2
    execution_model_limit: int = 1
    execution_render_limit: int = 1
    execution_heavy_limit: int = 2
    execution_max_recoveries: int = 1

    # LLM configuration (loaded from .env or environment)
    llm_provider: str = "deepseek"
    llm_api_key: str = ""
    llm_base_url: str = "https://api.deepseek.com"
    llm_model: str = "deepseek-chat"

    # Provider-specific keys (fall back to llm_api_key when not set)
    llm_openai_api_key: str = ""
    llm_openai_model: str = "gpt-4o"
    llm_anthropic_api_key: str = ""
    llm_anthropic_model: str = "claude-sonnet-4-6"

    # BGM library
    bgm_library_root: Path = Path("runtime/bgm")
    music_provider: str = "auto"
    jamendo_client_id: str = ""
    music_cache_root: Path = Path("runtime/music-cache")
    music_candidate_limit: int = 3
    music_request_timeout_seconds: float = 20.0

    # ASR
    asr_model_size: str = "small"  # "tiny", "small", "medium", "large-v3"
    release_models_after_execution: bool = True
    rabbitmq_enabled: bool = False
    rabbitmq_host: str = "127.0.0.1"
    rabbitmq_port: int = 5672
    rabbitmq_username: str = "agentvideo"
    rabbitmq_password: str = "agentvideo"
    rabbitmq_prefetch: int = 1
    worker_resource_group: str = "LIGHT"
    control_plane_base_url: str = "http://127.0.0.1:8080"
    rabbitmq_worker_token: str = ""

    model_config = SettingsConfigDict(
        env_prefix="TOOL_SERVICE_",
        case_sensitive=False,
    )


settings = Settings()
