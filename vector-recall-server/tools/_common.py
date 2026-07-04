import time

from config import config
from db import Database


def open_db() -> Database:
    return Database(config.db_path)


def now_millis() -> int:
    return int(time.time() * 1000)
