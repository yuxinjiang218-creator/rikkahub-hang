import json
import math
import sqlite3
from dataclasses import dataclass


@dataclass
class VectorHit:
    chunk_pk: str
    score: float


class VectorDatabase:
    def __init__(self, conn: sqlite3.Connection, dimension: int):
        self.conn = conn
        self.dimension = dimension
        self.sqlite_vec_available = self._try_load_sqlite_vec()
        self.requires_rebuild = False
        self.init_schema()

    def _try_load_sqlite_vec(self) -> bool:
        try:
            import sqlite_vec

            sqlite_vec.load(self.conn)
            return True
        except Exception:
            return False

    def init_schema(self) -> None:
        if self._table_exists("vector_entries"):
            self._drop_table("message_vectors")
            self._drop_table("vector_entries")
            self.requires_rebuild = True
        self.conn.execute(
            """
            CREATE TABLE IF NOT EXISTS chunk_vector_entries(
                chunk_pk TEXT PRIMARY KEY,
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

    def upsert(self, chunk_pk: str, vector: list[float]) -> None:
        self.delete(chunk_pk)
        row = self.conn.execute(
            "SELECT COALESCE(MAX(rowid_value), 0) + 1 AS next_id FROM chunk_vector_entries"
        ).fetchone()
        rowid_value = int(row["next_id"] if isinstance(row, sqlite3.Row) else row[0])
        embedding_json = json.dumps(vector)
        self.conn.execute(
            "INSERT INTO chunk_vector_entries(chunk_pk, rowid_value, embedding_json) VALUES (?, ?, ?)",
            (chunk_pk, rowid_value, embedding_json),
        )
        if self.sqlite_vec_available:
            self.conn.execute(
                "INSERT INTO message_vectors(rowid, embedding) VALUES (?, ?)",
                (rowid_value, json.dumps(vector)),
            )

    def delete(self, chunk_pk: str) -> None:
        row = self.conn.execute(
            "SELECT rowid_value FROM chunk_vector_entries WHERE chunk_pk = ?",
            (chunk_pk,),
        ).fetchone()
        if row:
            rowid_value = int(row["rowid_value"] if isinstance(row, sqlite3.Row) else row[0])
            if self.sqlite_vec_available:
                self.conn.execute("DELETE FROM message_vectors WHERE rowid = ?", (rowid_value,))
            self.conn.execute("DELETE FROM chunk_vector_entries WHERE chunk_pk = ?", (chunk_pk,))

    def delete_many(self, chunk_pks: list[str]) -> None:
        for chunk_pk in chunk_pks:
            self.delete(chunk_pk)

    def search(self, query_vector: list[float], limit: int) -> list[VectorHit]:
        if self.sqlite_vec_available:
            try:
                rows = self.conn.execute(
                    """
                    SELECT e.chunk_pk, v.distance
                    FROM message_vectors v
                    JOIN chunk_vector_entries e ON e.rowid_value = v.rowid
                    WHERE v.embedding MATCH ? AND k = ?
                    ORDER BY v.distance
                    """,
                    (json.dumps(query_vector), limit),
                ).fetchall()
                return [VectorHit(chunk_pk=row["chunk_pk"], score=float(row["distance"])) for row in rows]
            except Exception:
                pass

        rows = self.conn.execute("SELECT chunk_pk, embedding_json FROM chunk_vector_entries").fetchall()
        hits = []
        for row in rows:
            vector = json.loads(row["embedding_json"])
            hits.append(VectorHit(chunk_pk=row["chunk_pk"], score=cosine_distance(query_vector, vector)))
        return sorted(hits, key=lambda hit: hit.score)[:limit]

    def _table_exists(self, name: str) -> bool:
        row = self.conn.execute(
            "SELECT 1 FROM sqlite_master WHERE type IN ('table', 'virtual table') AND name = ?",
            (name,),
        ).fetchone()
        return row is not None

    def _drop_table(self, name: str) -> None:
        try:
            self.conn.execute(f"DROP TABLE IF EXISTS {name}")
        except sqlite3.OperationalError:
            pass


def cosine_distance(a: list[float], b: list[float]) -> float:
    if not a or not b or len(a) != len(b):
        return 1.0
    dot = sum(x * y for x, y in zip(a, b))
    norm_a = math.sqrt(sum(x * x for x in a))
    norm_b = math.sqrt(sum(y * y for y in b))
    if norm_a == 0 or norm_b == 0:
        return 1.0
    return 1.0 - dot / (norm_a * norm_b)
