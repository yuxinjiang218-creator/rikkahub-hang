import json
import math
import sqlite3
from dataclasses import dataclass


@dataclass
class VectorHit:
    pk: str
    score: float


class VectorDatabase:
    def __init__(self, conn: sqlite3.Connection, dimension: int):
        self.conn = conn
        self.dimension = dimension
        self.sqlite_vec_available = self._try_load_sqlite_vec()
        self.init_schema()

    def _try_load_sqlite_vec(self) -> bool:
        try:
            import sqlite_vec

            sqlite_vec.load(self.conn)
            return True
        except Exception:
            return False

    def init_schema(self) -> None:
        self.conn.execute(
            """
            CREATE TABLE IF NOT EXISTS vector_entries(
                message_pk TEXT PRIMARY KEY,
                rowid_value INTEGER UNIQUE NOT NULL,
                embedding_json TEXT NOT NULL
            )
            """
        )
        if self.sqlite_vec_available:
            self.conn.execute(
                f"CREATE VIRTUAL TABLE IF NOT EXISTS message_vectors USING vec0(embedding FLOAT[{self.dimension}])"
            )
        self.conn.commit()

    def upsert(self, pk: str, vector: list[float]) -> None:
        self.delete(pk)
        row = self.conn.execute("SELECT COALESCE(MAX(rowid_value), 0) + 1 AS next_id FROM vector_entries").fetchone()
        rowid_value = int(row["next_id"] if isinstance(row, sqlite3.Row) else row[0])
        embedding_json = json.dumps(vector)
        self.conn.execute(
            "INSERT INTO vector_entries(message_pk, rowid_value, embedding_json) VALUES (?, ?, ?)",
            (pk, rowid_value, embedding_json),
        )
        if self.sqlite_vec_available:
            self.conn.execute(
                "INSERT INTO message_vectors(rowid, embedding) VALUES (?, ?)",
                (rowid_value, json.dumps(vector)),
            )

    def delete(self, pk: str) -> None:
        row = self.conn.execute(
            "SELECT rowid_value FROM vector_entries WHERE message_pk = ?",
            (pk,),
        ).fetchone()
        if row:
            rowid_value = int(row["rowid_value"] if isinstance(row, sqlite3.Row) else row[0])
            if self.sqlite_vec_available:
                self.conn.execute("DELETE FROM message_vectors WHERE rowid = ?", (rowid_value,))
            self.conn.execute("DELETE FROM vector_entries WHERE message_pk = ?", (pk,))

    def search(self, query_vector: list[float], limit: int) -> list[VectorHit]:
        if self.sqlite_vec_available:
            try:
                rows = self.conn.execute(
                    """
                    SELECT e.message_pk, v.distance
                    FROM message_vectors v
                    JOIN vector_entries e ON e.rowid_value = v.rowid
                    WHERE v.embedding MATCH ? AND k = ?
                    ORDER BY v.distance
                    """,
                    (json.dumps(query_vector), limit),
                ).fetchall()
                return [VectorHit(pk=row["message_pk"], score=float(row["distance"])) for row in rows]
            except Exception:
                pass

        rows = self.conn.execute("SELECT message_pk, embedding_json FROM vector_entries").fetchall()
        hits = []
        for row in rows:
            vector = json.loads(row["embedding_json"])
            hits.append(VectorHit(pk=row["message_pk"], score=cosine_distance(query_vector, vector)))
        return sorted(hits, key=lambda hit: hit.score)[:limit]


def cosine_distance(a: list[float], b: list[float]) -> float:
    if not a or not b or len(a) != len(b):
        return 1.0
    dot = sum(x * y for x, y in zip(a, b))
    norm_a = math.sqrt(sum(x * x for x in a))
    norm_b = math.sqrt(sum(y * y for y in b))
    if norm_a == 0 or norm_b == 0:
        return 1.0
    return 1.0 - dot / (norm_a * norm_b)
