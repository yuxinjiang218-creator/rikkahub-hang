import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Config:
    db_path: str = os.getenv("DB_PATH", "data/rikka.db")
    vector_username: str = os.getenv("VECTOR_USERNAME", "admin")
    vector_password: str = os.getenv("VECTOR_PASSWORD", "admin")
    embed_base_url: str = os.getenv("EMBED_BASE_URL", "https://api.openai.com/v1")
    embed_api_key: str = os.getenv("EMBED_API_KEY", "")
    embed_model: str = os.getenv("EMBED_MODEL", "text-embedding-3-small")
    embed_dimension: int = int(os.getenv("EMBED_DIMENSION", "1536"))
    chunk_max_units: int = int(os.getenv("CHUNK_MAX_UNITS", "900"))
    chunk_overlap_units: int = int(os.getenv("CHUNK_OVERLAP_UNITS", "120"))
    chunk_min_units: int = int(os.getenv("CHUNK_MIN_UNITS", "80"))
    embed_batch_size: int = int(os.getenv("EMBED_BATCH_SIZE", "64"))


config = Config()
