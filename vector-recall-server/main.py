from fastapi import Depends, FastAPI, HTTPException, Request, status
from starlette.responses import PlainTextResponse
from starlette.middleware.trustedhost import TrustedHostMiddleware

from auth import (
    clear_login_failures,
    client_ip,
    configure_auth,
    ensure_login_allowed,
    get_current_user,
    issue_device_token,
    register_login_failure,
    token_prefix,
    verify_password,
)
from chunker import TextChunk, chunk_text
from config import config
from db import Database, MessageChunkRow, MessageRow, User
from embedder import embedder
from models import (
    DeleteConversationRequest,
    DeleteConversationResponse,
    DiffRequest,
    DiffResponse,
    HandshakeResponse,
    LoginRequest,
    LoginResponse,
    RecallRequest,
    RecallResponse,
    RecallResult,
    UploadRequest,
    UploadResponse,
)
from vector_db import VectorDatabase


class RequestSizeLimitMiddleware:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        path = scope.get("path", "")
        limit = config.upload_max_bytes if path == "/api/v1/sync/upload" else config.request_max_bytes
        headers = {key.lower(): value for key, value in scope.get("headers", [])}
        content_length = headers.get(b"content-length")
        if content_length and int(content_length) > limit:
            response = PlainTextResponse("Request body too large", status_code=413)
            await response(scope, receive, send)
            return
        body = bytearray()
        more_body = True
        while more_body:
            message = await receive()
            if message["type"] == "http.request":
                body.extend(message.get("body", b""))
                if len(body) > limit:
                    response = PlainTextResponse("Request body too large", status_code=413)
                    await response(scope, receive, send)
                    return
                more_body = message.get("more_body", False)
            else:
                await self.app(scope, receive, send)
                return

        sent = False

        async def replay_receive():
            nonlocal sent
            if sent:
                return {"type": "http.request", "body": b"", "more_body": False}
            sent = True
            return {"type": "http.request", "body": bytes(body), "more_body": False}

        await self.app(scope, replay_receive, send)


if (
    config.vector_username == "admin"
    and config.vector_password == "admin"
    and not config.allow_insecure_defaults
):
    raise RuntimeError("Refusing to start with default admin/admin credentials")

app = FastAPI(title="RikkaHub Vector Recall")
app.add_middleware(RequestSizeLimitMiddleware)
app.add_middleware(TrustedHostMiddleware, allowed_hosts=list(config.allowed_hosts))
db = Database(config.db_path)
db.ensure_user(config.vector_username, config.vector_password)
configure_auth(db)
vector_db = VectorDatabase(db.conn, config.embed_dimension)


@app.post("/api/v1/auth/login", response_model=LoginResponse)
def login(body: LoginRequest, request: Request):
    ip = client_ip(request)
    ensure_login_allowed(ip, body.username)
    user = verify_password(body.username, body.password)
    if not user:
        register_login_failure(ip, body.username)
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED)
    clear_login_failures(ip, body.username)
    device_token, device = issue_device_token(user, body.deviceName)
    return LoginResponse(
        deviceToken=device_token,
        tokenPrefix=token_prefix(device_token),
        createdAt=device.created_at,
    )


@app.post("/api/v1/handshake", response_model=HandshakeResponse)
def handshake(user: User = Depends(get_current_user)):
    return HandshakeResponse(status="ok", version="1.0", username=user.username)


@app.post("/api/v1/sync/diff", response_model=DiffResponse)
def sync_diff(body: DiffRequest, user: User = Depends(get_current_user)):
    dirty = []
    local_ids = {conv.conversationId for conv in body.conversations}
    for conv in body.conversations:
        server_update_at = db.get_conversation_update_at(conv.conversationId, user.id)
        if server_update_at is None or server_update_at < conv.updateAt:
            dirty.append(conv.conversationId)

    deleted = 0
    try:
        stale_conversations = db.get_conversation_ids_for_assistant(user.id, body.assistantId) - local_ids
        for conversation_id in stale_conversations:
            vector_db.delete_many(db.get_chunk_pks_for_conversation(conversation_id, user.id))
            deleted += db.delete_conversation(conversation_id, user.id, body.assistantId)
        db.commit()
    except Exception:
        db.rollback()
        raise
    return DiffResponse(dirty=dirty, deleted=deleted)


@app.post("/api/v1/sync/delete", response_model=DeleteConversationResponse)
def sync_delete(body: DeleteConversationRequest, user: User = Depends(get_current_user)):
    try:
        vector_db.delete_many(db.get_chunk_pks_for_conversation(body.conversationId, user.id))
        deleted = db.delete_conversation(body.conversationId, user.id, body.assistantId)
        db.commit()
    except Exception:
        db.rollback()
        raise
    return DeleteConversationResponse(deleted=deleted)


@app.post("/api/v1/sync/upload", response_model=UploadResponse)
def sync_upload(body: UploadRequest, user: User = Depends(get_current_user)):
    existing_ids = db.get_message_ids(body.conversationId, user.id)
    incoming_ids: set[str] = set()
    stale: set[str] = set()

    try:
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
                rebuild_message_chunks_and_vectors(row)

        stale = existing_ids - incoming_ids
        for pk in stale:
            vector_db.delete_many(db.get_chunk_pks_for_message(pk, user.id))
            db.delete_message_by_pk(pk, user.id)
        db.commit()
    except Exception:
        db.rollback()
        raise
    return UploadResponse(synced=len(incoming_ids), deleted=len(stale))


@app.post("/api/v1/recall/search", response_model=RecallResponse)
def recall_search(body: RecallRequest, user: User = Depends(get_current_user)):
    query_vector = embedder.embed(body.query)
    raw_hits = vector_db.search(query_vector, max(body.limit * 12, body.limit))
    results: list[RecallResult] = []
    seen_messages: set[str] = set()

    for hit in raw_hits:
        chunk = db.get_chunk_by_pk(hit.chunk_pk, user.id)
        if chunk is None or chunk.user_id != user.id:
            continue
        if chunk.message_pk in seen_messages:
            continue
        if chunk.assistant_id != body.assistantId or chunk.is_selected != 1:
            continue
        if body.excludeConversationId and chunk.conversation_id == body.excludeConversationId:
            continue
        if body.focusConversationId and chunk.conversation_id != body.focusConversationId:
            continue
        if body.role != "any" and chunk.role != body.role:
            continue
        msg = db.get_message_by_pk(chunk.message_pk, user.id)
        if msg is None:
            continue

        seen_messages.add(chunk.message_pk)
        results.append(
            RecallResult(
                conversationId=chunk.conversation_id,
                nodeId=chunk.node_id,
                messageId=chunk.message_id,
                conversationTitle=msg.conversation_title,
                conversationUpdateAt=msg.conversation_update_at,
                role=chunk.role,
                createdAt=msg.created_at,
                snippet=make_snippet(chunk.text, body.query),
                score=hit.score,
                chunkIndex=chunk.chunk_index,
                chunkStartOffset=chunk.start_offset,
                chunkEndOffset=chunk.end_offset,
            )
        )
        if len(results) >= body.limit:
            break

    return RecallResponse(results=results)


def rebuild_message_chunks_and_vectors(message: MessageRow) -> None:
    old_chunk_pks = db.get_chunk_pks_for_message(message.pk, message.user_id)
    vector_db.delete_many(old_chunk_pks)
    db.delete_chunks_for_message(message.pk, message.user_id)
    if message.is_selected != 1 or message.role not in ("user", "assistant") or not message.text.strip():
        return
    chunks = build_message_chunks(message)
    for chunk in chunks:
        db.upsert_chunk(chunk)
    sync_message_vectors(message)


def build_message_chunks(message: MessageRow) -> list[MessageChunkRow]:
    chunks = chunk_text(
        message.text,
        max_units=config.chunk_max_units,
        overlap_units=config.chunk_overlap_units,
        min_units=config.chunk_min_units,
    )
    return [_to_chunk_row(message, chunk) for chunk in chunks]


def sync_message_vectors(message: MessageRow) -> None:
    chunks = [
        db.get_chunk_by_pk(chunk_pk, message.user_id)
        for chunk_pk in db.get_chunk_pks_for_message(message.pk, message.user_id)
    ]
    valid_chunks = [chunk for chunk in chunks if chunk is not None]
    for start in range(0, len(valid_chunks), config.embed_batch_size):
        batch = valid_chunks[start : start + config.embed_batch_size]
        vectors = embedder.embed_many([chunk.text for chunk in batch])
        for chunk, vector in zip(batch, vectors):
            vector_db.upsert(chunk.chunk_pk, vector)


def _to_chunk_row(message: MessageRow, chunk: TextChunk) -> MessageChunkRow:
    return MessageChunkRow(
        chunk_pk=f"{message.pk}:{chunk.chunk_index}",
        message_pk=message.pk,
        chunk_index=chunk.chunk_index,
        start_offset=chunk.start_offset,
        end_offset=chunk.end_offset,
        text=chunk.text,
        user_id=message.user_id,
        assistant_id=message.assistant_id,
        conversation_id=message.conversation_id,
        node_id=message.node_id,
        message_id=message.message_id,
        role=message.role,
        is_selected=message.is_selected,
    )


def make_snippet(text: str, query: str, window: int = 240) -> str:
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


if vector_db.requires_rebuild:
    for message in db.selected_indexable_messages():
        for chunk in build_message_chunks(message):
            db.upsert_chunk(chunk)
        sync_message_vectors(message)
    db.commit()
