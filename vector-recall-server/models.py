from pydantic import BaseModel


class HandshakeResponse(BaseModel):
    status: str
    version: str
    username: str


class LoginRequest(BaseModel):
    username: str
    password: str
    deviceName: str = "RikkaHub"


class LoginResponse(BaseModel):
    deviceToken: str
    tokenPrefix: str
    createdAt: int


class DiffConversation(BaseModel):
    conversationId: str
    updateAt: int


class DiffRequest(BaseModel):
    assistantId: str
    conversations: list[DiffConversation]


class DiffResponse(BaseModel):
    dirty: list[str]
    deleted: int = 0


class UploadMessage(BaseModel):
    messageId: str
    role: str
    text: str
    createdAt: int
    isSelected: bool


class UploadNode(BaseModel):
    nodeId: str
    nodeIndex: int
    selectIndex: int
    messages: list[UploadMessage]


class UploadRequest(BaseModel):
    assistantId: str
    conversationId: str
    conversationTitle: str
    conversationUpdateAt: int
    nodes: list[UploadNode]


class UploadResponse(BaseModel):
    synced: int
    deleted: int


class DeleteConversationRequest(BaseModel):
    assistantId: str | None = None
    conversationId: str


class DeleteConversationResponse(BaseModel):
    deleted: int


class RecallRequest(BaseModel):
    assistantId: str
    query: str
    role: str = "any"
    limit: int = 6
    excludeConversationId: str | None = None
    focusConversationId: str | None = None


class RecallResult(BaseModel):
    conversationId: str
    nodeId: str
    messageId: str
    conversationTitle: str
    conversationUpdateAt: int
    role: str
    createdAt: int
    snippet: str
    score: float
    chunkIndex: int | None = None
    chunkStartOffset: int | None = None
    chunkEndOffset: int | None = None


class RecallResponse(BaseModel):
    results: list[RecallResult]
