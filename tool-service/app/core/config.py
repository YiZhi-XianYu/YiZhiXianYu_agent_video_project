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
    execution_store_path: Path = Path("runtime/executions/tool-executions.sqlite3")
    callback_timeout_seconds: float = 10.0

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

    # ASR
    asr_model_size: str = "small"  # "tiny", "small", "medium", "large-v3"

    model_config = SettingsConfigDict(
        env_prefix="TOOL_SERVICE_",
        case_sensitive=False,
    )


settings = Settings()
