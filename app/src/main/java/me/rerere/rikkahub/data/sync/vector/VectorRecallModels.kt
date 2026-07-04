package me.rerere.rikkahub.data.sync.vector

import kotlinx.serialization.Serializable

data class VectorRecallConversationSummary(
    val conversationId: String,
    val assistantId: String,
    val updateAt: Long,
)

@Serializable
data class VectorHandshakeResponse(
    val status: String,
    val version: String,
    val username: String? = null,
)

@Serializable
data class VectorLoginRequest(
    val username: String,
    val password: String,
    val deviceName: String,
)

@Serializable
data class VectorLoginResponse(
    val deviceToken: String,
    val tokenPrefix: String,
    val createdAt: Long,
)

@Serializable
data class VectorDiffConversationDto(
    val conversationId: String,
    val updateAt: Long,
)

@Serializable
data class VectorDiffRequest(
    val assistantId: String,
    val conversations: List<VectorDiffConversationDto>,
)

@Serializable
data class VectorDiffResponse(
    val dirty: List<String> = emptyList(),
    val deleted: Int = 0,
)

@Serializable
data class VectorUploadRequest(
    val assistantId: String,
    val conversationId: String,
    val conversationTitle: String,
    val conversationUpdateAt: Long,
    val nodes: List<VectorUploadNodeDto>,
)

@Serializable
data class VectorUploadNodeDto(
    val nodeId: String,
    val nodeIndex: Int,
    val selectIndex: Int,
    val messages: List<VectorUploadMessageDto>,
)

@Serializable
data class VectorUploadMessageDto(
    val messageId: String,
    val role: String,
    val text: String,
    val createdAt: Long,
    val isSelected: Boolean,
)

@Serializable
data class VectorUploadResponse(
    val synced: Int = 0,
    val deleted: Int = 0,
)

@Serializable
data class VectorDeleteConversationRequest(
    val assistantId: String,
    val conversationId: String,
)

@Serializable
data class VectorDeleteConversationResponse(
    val deleted: Int = 0,
)

@Serializable
data class VectorSearchRequest(
    val assistantId: String,
    val query: String,
    val role: String,
    val limit: Int,
    val excludeConversationId: String? = null,
    val focusConversationId: String? = null,
)

@Serializable
data class VectorSearchResponse(
    val results: List<VectorSearchResultDto> = emptyList(),
)

@Serializable
data class VectorSearchResultDto(
    val conversationId: String,
    val nodeId: String,
    val messageId: String,
    val conversationTitle: String,
    val conversationUpdateAt: Long,
    val role: String,
    val createdAt: Long,
    val snippet: String,
    val score: Double = 0.0,
)
