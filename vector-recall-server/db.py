import os
import sqlite3
import time
import uuid
from dataclasses import dataclass
from pathlib import Path

from passlib.hash import bcrypt


@dataclass
class User:
    id: str
    username: str
    password_hash: str


@dataclass
class MessageRow:
    conversation_id: str
    node_id: str
    message_id: str
    assistant_id: str
    user_id: str
    node_index: int
    select_index: int
    is_selected: int
    role: str
    text: str
    created_at: int
    conversation_title: str
    conversation_update_at: int

    @property
    def pk(self) -> str:
        return f"{self.conversation_id}:{self.node_id}:{self.message_id}"


class Database:
    def __init__(self, path: str):
        Path(os.path.dirname(path) or ".").mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(path, check_same_thread=False)
        self.conn.row_factory = sqlite3.Row
        self.init_schema()

    def init_schema(self) -> None:
        self.conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS users (
                id TEXT PRIMARY KEY,
                username TEXT UNIQUE NOT NULL,
                password_hash TEXT NOT NULL,
                created_at INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS messages (
                conversation_id TEXT NOT NULL,
                node_id TEXT NOT NULL,
                message_id TEXT NOT NULL,
                assistant_id TEXT NOT NULL,
                user_id TEXT NOT NULL,
                node_index INTEGER NOT NULL,
                select_index INTEGER NOT NULL,
                is_selected INTEGER NOT NULL,
                role TEXT NOT NULL,
                text TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                conversation_title TEXT NOT NULL,
                conversation_update_at INTEGER NOT NULL,
                PRIMARY KEY (conversation_id, node_id, message_id)
            );
            CREATE INDEX IF NOT EXISTS idx_messages_assistant ON messages(assistant_id, user_id);
            CREATE INDEX IF NOT EXISTS idx_messages_conv ON messages(conversation_id);
            CREATE INDEX IF NOT EXISTS idx_messages_selected ON messages(user_id, assistant_id, is_selected);
            """
        )
        self.conn.commit()

    def ensure_user(self, username: str, password: str) -> User:
        password_hash = bcrypt.hash(password)
        existing = self.get_user(username)
        if existing:
            self.conn.execute(
                "UPDATE users SET password_hash = ? WHERE id = ?",
                (password_hash, existing.id),
            )
            self.conn.commit()
            return self.get_user(username)
        user_id = str(uuid.uuid4())
        self.conn.execute(
            "INSERT INTO users(id, username, password_hash, created_at) VALUES (?, ?, ?, ?)",
            (user_id, username, password_hash, int(time.time() * 1000)),
        )
        self.conn.commit()
        return self.get_user(username)

    def get_user(self, username: str) -> User | None:
        row = self.conn.execute(
            "SELECT id, username, password_hash FROM users WHERE username = ?",
            (username,),
        ).fetchone()
        if not row:
            return None
        return User(id=row["id"], username=row["username"], password_hash=row["password_hash"])

    def get_conversation_update_at(self, conversation_id: str, user_id: str) -> int | None:
        row = self.conn.execute(
            """
            SELECT MAX(conversation_update_at) AS update_at
            FROM messages
            WHERE conversation_id = ? AND user_id = ?
            """,
            (conversation_id, user_id),
        ).fetchone()
        value = row["update_at"] if row else None
        return int(value) if value is not None else None

    def get_message_ids(self, conversation_id: str, user_id: str) -> set[str]:
        rows = self.conn.execute(
            "SELECT conversation_id, node_id, message_id FROM messages WHERE conversation_id = ? AND user_id = ?",
            (conversation_id, user_id),
        ).fetchall()
        return {f"{r['conversation_id']}:{r['node_id']}:{r['message_id']}" for r in rows}

    def upsert_message(self, row: MessageRow) -> None:
        self.conn.execute(
            """
            INSERT INTO messages(
                conversation_id, node_id, message_id, assistant_id, user_id, node_index, select_index,
                is_selected, role, text, created_at, conversation_title, conversation_update_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(conversation_id, node_id, message_id) DO UPDATE SET
                assistant_id = excluded.assistant_id,
                user_id = excluded.user_id,
                node_index = excluded.node_index,
                select_index = excluded.select_index,
                is_selected = excluded.is_selected,
                role = excluded.role,
                text = excluded.text,
                created_at = excluded.created_at,
                conversation_title = excluded.conversation_title,
                conversation_update_at = excluded.conversation_update_at
            """,
            (
                row.conversation_id,
                row.node_id,
                row.message_id,
                row.assistant_id,
                row.user_id,
                row.node_index,
                row.select_index,
                row.is_selected,
                row.role,
                row.text,
                row.created_at,
                row.conversation_title,
                row.conversation_update_at,
            ),
        )

    def delete_message_by_pk(self, pk: str, user_id: str) -> None:
        conv_id, node_id, msg_id = pk.split(":", 2)
        self.conn.execute(
            """
            DELETE FROM messages
            WHERE conversation_id = ? AND node_id = ? AND message_id = ? AND user_id = ?
            """,
            (conv_id, node_id, msg_id, user_id),
        )

    def get_message_by_pk(self, pk: str, user_id: str) -> MessageRow | None:
        conv_id, node_id, msg_id = pk.split(":", 2)
        row = self.conn.execute(
            """
            SELECT * FROM messages
            WHERE conversation_id = ? AND node_id = ? AND message_id = ? AND user_id = ?
            """,
            (conv_id, node_id, msg_id, user_id),
        ).fetchone()
        return self._message_from_row(row) if row else None

    def selected_messages(self, user_id: str, assistant_id: str) -> list[MessageRow]:
        rows = self.conn.execute(
            """
            SELECT * FROM messages
            WHERE user_id = ? AND assistant_id = ? AND is_selected = 1
            """,
            (user_id, assistant_id),
        ).fetchall()
        return [self._message_from_row(row) for row in rows]

    def commit(self) -> None:
        self.conn.commit()

    @staticmethod
    def _message_from_row(row: sqlite3.Row) -> MessageRow:
        return MessageRow(
            conversation_id=row["conversation_id"],
            node_id=row["node_id"],
            message_id=row["message_id"],
            assistant_id=row["assistant_id"],
            user_id=row["user_id"],
            node_index=row["node_index"],
            select_index=row["select_index"],
            is_selected=row["is_selected"],
            role=row["role"],
            text=row["text"],
            created_at=row["created_at"],
            conversation_title=row["conversation_title"],
            conversation_update_at=row["conversation_update_at"],
        )
