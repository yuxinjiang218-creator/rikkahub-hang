from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPBasic, HTTPBasicCredentials
from passlib.hash import bcrypt

from db import Database, User

security = HTTPBasic()

database: Database | None = None


def configure_auth(db: Database) -> None:
    global database
    database = db


def get_current_user(credentials: HTTPBasicCredentials = Depends(security)) -> User:
    if database is None:
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR)
    user = database.get_user(credentials.username)
    if not user or not bcrypt.verify(credentials.password, user.password_hash):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            headers={"WWW-Authenticate": "Basic"},
        )
    return user
