from fastapi import Depends, FastAPI

from auth import configure_auth, get_current_user
from config import config
from db import Database, MessageRow, User
from embedder import embedder
from models import (
    DiffRequest,
    DiffResponse,
    HandshakeResponse,
    RecallRequest,
    RecallResponse,
    RecallResult,
    UploadRequest,
    UploadResponse,
)
from vector_db import VectorDatabase

app = FastAPI(title="RikkaHub Vector Recall")
db = Database(config.db_path)
db.ensure_user(config.vector_username, config.vector_password)
configure_auth(db)
vector_db = VectorDatabase(db.conn, config.embed_dimension)


@app.post("/api/v1/handshake", response_model=HandshakeResponse)
def handshake(user: User = Depends(get_current_user)):
    return HandshakeResponse(status="ok", version="1.0", username=user.username)


@app.post("/api/v1/sync/diff", response_model=DiffResponse)
def sync_diff(body: DiffRequest, user: User = Depends(get_current_user)):
    dirty = []
    for conv in body.conversations:
        server_update_at = db.get_conversation_update_at(conv.conversationId, user.id)
        if server_update_at is None or server_update_at < conv.updateAt:
            dirty.append(conv.conversationId)
    return DiffResponse(dirty=dirty)


@app.post("/api/v1/sync/upload", response_model=UploadResponse)
def sync_upload(body: UploadRequest, user: User = Depends(get_current_user)):
    existing_ids = db.get_message_ids(body.conversationId, user.id)
    incoming_ids: set[str] = set()

    for node in body.nodes:
        for msg in node.messages:
            pk = f"{body.conversationId}:{node.nodeId}:{msg.messageId}"
            incoming_ids.add(pk)
            row = MessageRow(
                conversation_id=body.conversationId,
                node_id=node.nodeId,
                message_id=msg.messageId,
                assistant_id=body.assistantId,
                user_id=user.id,
                node_index=node.nodeIndex,
                select_index=node.selectIndex,
                is_selected=1 if msg.isSelected else 0,
                role=msg.role,
                text=msg.text,
                created_at=msg.createdAt,
                conversation_title=body.conversationTitle,
                conversation_update_at=body.conversationUpdateAt,
            )
            db.upsert_message(row)
            if msg.isSelected and msg.role in ("user", "assistant") and msg.text.strip():
                vector_db.upsert(pk, embedder.embed(msg.text))
            else:
                vector_db.delete(pk)

    stale = existing_ids - incoming_ids
    for pk in stale:
        db.delete_message_by_pk(pk, user.id)
        vector_db.delete(pk)
    db.commit()
    return UploadResponse(synced=len(incoming_ids), deleted=len(stale))


@app.post("/api/v1/recall/search", response_model=RecallResponse)
def recall_search(body: RecallRequest, user: User = Depends(get_current_user)):
    query_vector = embedder.embed(body.query)
    raw_hits = vector_db.search(query_vector, max(body.limit * 8, body.limit))
    results: list[RecallResult] = []

    for hit in raw_hits:
        msg = db.get_message_by_pk(hit.pk, user.id)
        if msg is None or msg.user_id != user.id:
            continue
        if msg.assistant_id != body.assistantId or msg.is_selected != 1:
            continue
        if body.excludeConversationId and msg.conversation_id == body.excludeConversationId:
            continue
        if body.focusConversationId and msg.conversation_id != body.focusConversationId:
            continue
        if body.role != "any" and msg.role != body.role:
            continue

        results.append(
            RecallResult(
                conversationId=msg.conversation_id,
                nodeId=msg.node_id,
                messageId=msg.message_id,
                conversationTitle=msg.conversation_title,
                conversationUpdateAt=msg.conversation_update_at,
                role=msg.role,
                createdAt=msg.created_at,
                snippet=make_snippet(msg.text, body.query),
                score=hit.score,
            )
        )
        if len(results) >= body.limit:
            break

    return RecallResponse(results=results)


def make_snippet(text: str, query: str, window: int = 150) -> str:
    normalized = text.strip().replace("\n", " ")
    if len(normalized) <= window:
        return normalized
    terms = [term.lower() for term in query.split() if term.strip()]
    lower = normalized.lower()
    index = next((lower.find(term) for term in terms if lower.find(term) >= 0), -1)
    if index < 0:
        index = 0
    start = max(index - window // 2, 0)
    end = min(start + window, len(normalized))
    snippet = normalized[start:end]
    if start > 0:
        snippet = "..." + snippet
    if end < len(normalized):
        snippet += "..."
    for term in terms[:3]:
        snippet = snippet.replace(term, f"[{term}]")
    return snippet
