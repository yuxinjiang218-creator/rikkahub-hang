import hashlib
import secrets
import time
from dataclasses import dataclass

from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from passlib.hash import bcrypt

from config import config
from db import Database, DeviceToken, User

bearer_security = HTTPBearer(auto_error=False)

database: Database | None = None
login_failures: dict[tuple[str, str], list[float]] = {}
login_blocks: dict[tuple[str, str], float] = {}


@dataclass
class AuthContext:
    user: User
    device: DeviceToken


def configure_auth(db: Database) -> None:
    global database
    database = db


def now_millis() -> int:
    return int(time.time() * 1000)


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def generate_device_token() -> str:
    return "rvk_" + secrets.token_urlsafe(48)


def token_prefix(token: str) -> str:
    return token[:16]


def verify_password(username: str, password: str) -> User | None:
    if database is None:
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR)
    user = database.get_user(username)
    if not user or not bcrypt.verify(password, user.password_hash):
        return None
    return user


def register_login_failure(ip: str, username: str) -> None:
    key = (ip, username.lower())
    now = time.time()
    window_start = now - config.login_window_seconds
    failures = [value for value in login_failures.get(key, []) if value >= window_start]
    failures.append(now)
    login_failures[key] = failures
    if len(failures) >= config.login_fail_limit:
        login_blocks[key] = now + config.login_block_seconds


def clear_login_failures(ip: str, username: str) -> None:
    key = (ip, username.lower())
    login_failures.pop(key, None)
    login_blocks.pop(key, None)


def ensure_login_allowed(ip: str, username: str) -> None:
    key = (ip, username.lower())
    blocked_until = login_blocks.get(key)
    if blocked_until and blocked_until > time.time():
        raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail="Too many attempts")
    if blocked_until:
        login_blocks.pop(key, None)


def client_ip(request: Request) -> str:
    forwarded = request.headers.get("x-forwarded-for")
    if forwarded:
        return forwarded.split(",", 1)[0].strip()
    return request.client.host if request.client else "unknown"


def issue_device_token(user: User, device_name: str) -> tuple[str, DeviceToken]:
    if database is None:
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR)
    token = generate_device_token()
    device = database.create_device_token(
        user_id=user.id,
        token_hash=token_hash(token),
        token_prefix=token_prefix(token),
        device_name=device_name.strip()[:120] or "RikkaHub",
        created_at=now_millis(),
    )
    return token, device


def get_auth_context(
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer_security),
) -> AuthContext:
    if database is None:
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR)
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED)
    device = database.get_active_device_token_by_hash(token_hash(credentials.credentials))
    if not device:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED)
    user = database.get_user_by_id(device.user_id)
    if not user:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED)
    database.touch_device_token(device.id, now_millis())
    return AuthContext(user=user, device=device)


def get_current_user(context: AuthContext = Depends(get_auth_context)) -> User:
    return context.user
