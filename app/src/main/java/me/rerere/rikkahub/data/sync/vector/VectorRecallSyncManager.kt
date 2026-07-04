package me.rerere.rikkahub.data.sync.vector

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import me.rerere.rikkahub.data.model.Conversation
import java.time.Instant
import kotlin.uuid.Uuid

private const val TAG = "VectorRecallSyncManager"

data class VectorRecallSearchOutcome(
    val results: List<MessageSearchResult>,
    val remoteAvailable: Boolean,
    val degraded: Boolean,
)

class VectorRecallSyncManager(
    private val settingsStore: SettingsStore,
    private val client: VectorRecallClient,
    private val state: VectorRecallState,
) {
    suspend fun handshake(): Boolean = withContext(Dispatchers.IO) {
        val config = settingsStore.settingsFlow.first().vectorRecallConfig
        if (!config.enabled || !client.isConfigured(config)) {
            state.markFailed()
            return@withContext false
        }
        state.markChecking()
        runCatching {
            client.handshake(config)
        }.onSuccess {
            state.markOk()
        }.onFailure { error ->
            Log.w(TAG, "handshake failed", error)
            state.markFailed(error.message)
        }.isSuccess
    }

    suspend fun syncAll(
        summaries: List<VectorRecallConversationSummary>,
        assistantIds: Set<String> = summaries.map { it.assistantId }.toSet(),
        loadConversation: suspend (Uuid) -> Conversation?,
    ) = withContext(Dispatchers.IO) {
        if (!handshake()) return@withContext
        val config = settingsStore.settingsFlow.first().vectorRecallConfig
        val summariesByAssistant = summaries.groupBy { it.assistantId }
        (assistantIds + summariesByAssistant.keys).forEach { assistantId ->
            val assistantConversations = summariesByAssistant[assistantId].orEmpty()
            val dirty = runCatching {
                client.diff(
                    config = config,
                    request = VectorDiffRequest(
                        assistantId = assistantId,
                        conversations = assistantConversations.map {
                            VectorDiffConversationDto(
                                conversationId = it.conversationId,
                                updateAt = it.updateAt,
                            )
                        },
                    ),
                ).dirty
            }.onFailure { error ->
                Log.w(TAG, "sync diff failed", error)
                state.markFailed(error.message)
            }.getOrElse { emptyList() }

            for (conversationId in dirty.mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }) {
                loadConversation(conversationId)?.let { conversation ->
                    uploadConversation(conversation)
                }
            }
        }
    }

    suspend fun uploadConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        val config = settingsStore.settingsFlow.first().vectorRecallConfig
        if (!config.enabled || !client.isConfigured(config)) return@withContext
        if (!state.handshakeOk && !handshake()) return@withContext
        val request = conversation.toVectorUploadRequest()
        runCatching {
            client.upload(config, request)
        }.onFailure { error ->
            Log.w(TAG, "upload conversation failed: ${conversation.id}", error)
            state.markFailed(error.message)
        }
    }

    suspend fun deleteConversation(conversationId: Uuid, assistantId: Uuid) = withContext(Dispatchers.IO) {
        val config = settingsStore.settingsFlow.first().vectorRecallConfig
        if (!config.enabled || !client.isConfigured(config)) return@withContext
        if (!state.handshakeOk && !handshake()) return@withContext
        runCatching {
            client.deleteConversation(
                config = config,
                request = VectorDeleteConversationRequest(
                    assistantId = assistantId.toString(),
                    conversationId = conversationId.toString(),
                ),
            )
        }.onFailure { error ->
            Log.w(TAG, "delete remote conversation failed: $conversationId", error)
            state.markFailed(error.message)
        }
    }

    suspend fun search(
        assistantId: Uuid,
        keyword: String,
        currentConversationId: Uuid?,
        focusConversationId: Uuid?,
        role: MessageRole?,
        limit: Int,
    ): VectorRecallSearchOutcome = withContext(Dispatchers.IO) {
        val config = settingsStore.settingsFlow.first().vectorRecallConfig
        if (!config.enabled) {
            return@withContext VectorRecallSearchOutcome(emptyList(), remoteAvailable = false, degraded = false)
        }
        if (!client.isConfigured(config) || !state.handshakeOk) {
            return@withContext VectorRecallSearchOutcome(emptyList(), remoteAvailable = false, degraded = true)
        }
        runCatching {
            client.search(
                config = config,
                request = VectorSearchRequest(
                    assistantId = assistantId.toString(),
                    query = keyword,
                    role = role?.name?.lowercase() ?: "any",
                    limit = limit,
                    excludeConversationId = currentConversationId?.toString(),
                    focusConversationId = focusConversationId?.toString(),
                ),
            ).results.map { it.toMessageSearchResult(assistantId.toString()) }
        }.fold(
            onSuccess = { results ->
                VectorRecallSearchOutcome(results, remoteAvailable = true, degraded = false)
            },
            onFailure = { error ->
                Log.w(TAG, "vector search failed", error)
                state.markFailed(error.message)
                VectorRecallSearchOutcome(emptyList(), remoteAvailable = false, degraded = true)
            }
        )
    }
}

internal fun Conversation.toVectorUploadRequest(): VectorUploadRequest =
    VectorUploadRequest(
        assistantId = assistantId.toString(),
        conversationId = id.toString(),
        conversationTitle = title,
        conversationUpdateAt = updateAt.toEpochMilli(),
        nodes = messageNodes.mapIndexedNotNull { nodeIndex, node ->
            val messages = node.messages.mapIndexedNotNull { messageIndex, message ->
                message.toVectorUploadMessageDto(
                    isSelected = messageIndex == node.selectIndex,
                )
            }
            if (messages.isEmpty()) {
                null
            } else {
                VectorUploadNodeDto(
                    nodeId = node.id.toString(),
                    nodeIndex = nodeIndex,
                    selectIndex = node.selectIndex,
                    messages = messages,
                )
            }
        },
    )

private fun UIMessage.toVectorUploadMessageDto(isSelected: Boolean): VectorUploadMessageDto? {
    if (role != MessageRole.USER && role != MessageRole.ASSISTANT) return null
    val text = toText().trim()
    if (text.isBlank()) return null
    return VectorUploadMessageDto(
        messageId = id.toString(),
        role = role.name.lowercase(),
        text = text,
        createdAt = createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds(),
        isSelected = isSelected,
    )
}

private fun VectorSearchResultDto.toMessageSearchResult(assistantId: String): MessageSearchResult =
    MessageSearchResult(
        nodeId = nodeId,
        messageId = messageId,
        conversationId = conversationId,
        assistantId = assistantId,
        title = conversationTitle,
        role = when (role.lowercase()) {
            "user" -> MessageRole.USER
            else -> MessageRole.ASSISTANT
        },
        createdAt = Instant.ofEpochMilli(createdAt),
        updateAt = Instant.ofEpochMilli(conversationUpdateAt),
        snippet = snippet,
        isSelected = true,
    )
