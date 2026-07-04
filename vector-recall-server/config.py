import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Config:
    db_path: str = os.getenv("DB_PATH", "data/rikka.db")
    vector_username: str = os.getenv("VECTOR_USERNAME", "admin")
    vector_password: str = os.getenv("VECTOR_PASSWORD", "admin")
    allow_insecure_defaults: bool = os.getenv("ALLOW_INSECURE_DEFAULTS", "false").lower() == "true"
    allowed_hosts: list[str] = tuple(
        host.strip() for host in os.getenv("ALLOWED_HOSTS", "*").split(",") if host.strip()
    )
    embed_base_url: str = os.getenv("EMBED_BASE_URL", "https://api.openai.com/v1")
    embed_api_key: str = os.getenv("EMBED_API_KEY", "")
    embed_model: str = os.getenv("EMBED_MODEL", "text-embedding-3-small")
    embed_dimension: int = int(os.getenv("EMBED_DIMENSION", "1536"))
    chunk_max_units: int = int(os.getenv("CHUNK_MAX_UNITS", "900"))
    chunk_overlap_units: int = int(os.getenv("CHUNK_OVERLAP_UNITS", "120"))
    chunk_min_units: int = int(os.getenv("CHUNK_MIN_UNITS", "80"))
    embed_batch_size: int = int(os.getenv("EMBED_BATCH_SIZE", "64"))
    request_max_bytes: int = int(os.getenv("REQUEST_MAX_BYTES", str(1024 * 1024)))
    upload_max_bytes: int = int(os.getenv("UPLOAD_MAX_BYTES", str(20 * 1024 * 1024)))
    login_fail_limit: int = int(os.getenv("LOGIN_FAIL_LIMIT", "8"))
    login_window_seconds: int = int(os.getenv("LOGIN_WINDOW_SECONDS", "300"))
    login_block_seconds: int = int(os.getenv("LOGIN_BLOCK_SECONDS", "300"))


config = Config()
