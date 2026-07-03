package me.rerere.rikkahub.data.model

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.InstantSerializer
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import java.time.Instant
import kotlin.uuid.Uuid

@Serializable
data class Conversation(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val title: String = "",
    val messageNodes: List<MessageNode>,
    val chatSuggestions: List<String> = emptyList(),
    val isPinned: Boolean = false,
    val compressionState: ConversationCompressionState = ConversationCompressionState(),
    val compressionEvents: List<CompressionEvent> = emptyList(),
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updateAt: Instant = Instant.now(),
    val customSystemPrompt: String? = null,
    val modeInjectionIds: Set<Uuid> = emptySet(),
    val lorebookIds: Set<Uuid> = emptySet(),
    // Absolute path inside the workspace rootfs
    val workspaceCwd: String? = null,
    // 所属文件夹（助手内分组），null 表示未归入任何文件夹
    val folderId: Uuid? = null,
    @Transient
    val newConversation: Boolean = false
) {
    val files: List<Uri>
        get() = messageNodes
            .flatMap { node -> node.messages.flatMap { it.parts } }
            .collectAllParts()
            .mapNotNull { it.fileUri() }

    /**
     *  当前选中的 message
     */
    val currentMessages
        get(): List<UIMessage> {
            return messageNodes.map { node -> node.messages[node.selectIndex] }
        }

    fun getMessageNodeByMessage(message: UIMessage): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.contains(message) }
    }

    fun getMessageNodeByMessageId(messageId: Uuid): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.any { it.id == messageId } }
    }

    fun updateCurrentMessages(messages: List<UIMessage>): Conversation {
        return updateCurrentMessages(messages = messages, startIndex = 0)
    }

    fun updateCurrentMessage(message: UIMessage, targetIndex: Int): Conversation {
        require(targetIndex >= 0) { "targetIndex must be >= 0" }
        val newNodes = this.messageNodes.toMutableList()
        val node = newNodes
            .getOrElse(targetIndex) { message.toMessageNode() }
        val newNode = node.withSelectedMessage(message)

        if (targetIndex > newNodes.lastIndex) {
            newNodes.add(newNode)
        } else {
            newNodes[targetIndex] = newNode
        }

        return this.copy(messageNodes = newNodes)
    }

    fun updateCurrentMessages(messages: List<UIMessage>, startIndex: Int): Conversation {
        require(startIndex >= 0) { "startIndex must be >= 0" }
        val newNodes = this.messageNodes.toMutableList()
        messages.forEachIndexed { index, message ->
            val targetIndex = startIndex + index
            val node = newNodes
                .getOrElse(targetIndex) { message.toMessageNode() }
            val newNode = node.withSelectedMessage(message)

            // 更新newNodes
            if (targetIndex > newNodes.lastIndex) {
                newNodes.add(newNode)
            } else {
                newNodes[targetIndex] = newNode
            }
        }

        return this.copy(
            messageNodes = newNodes
        )
    }

    companion object {
        fun ofId(
            id: Uuid,
            assistantId: Uuid = DEFAULT_ASSISTANT_ID,
            messages: List<MessageNode> = emptyList(),
            newConversation: Boolean = false
        ) = Conversation(
            id = id,
            assistantId = assistantId,
            messageNodes = messages,
            newConversation = newConversation,
        )
    }
}

@Serializable
data class ConversationCompressionState(
    val dialogueSummaryText: String = "",
    @Serializable(with = InstantSerializer::class)
    val dialogueSummaryUpdatedAt: Instant = Instant.EPOCH,
    val lastCompressedMessageIndex: Int = -1,
    @Serializable(with = InstantSerializer::class)
    val updatedAt: Instant = Instant.EPOCH
) {
    val hasSummary: Boolean
        get() = dialogueSummaryText.isNotBlank()
}

@Serializable
data class CompressionEvent(
    val id: Long = 0L,
    val boundaryIndex: Int,
    val dialogueSummaryText: String = "",
    val dialogueSummaryPreview: String = "",
    val targetTokens: Int = 0,
    val compressStartIndex: Int = 0,
    val compressEndIndex: Int = -1,
    val keepRecentMessages: Int = 0,
    val trigger: String = "",
    val additionalPrompt: String = "",
    val baseDialogueSummaryText: String = "",
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant = Instant.now()
)

val compressionEventOrder: Comparator<CompressionEvent> =
    compareBy<CompressionEvent>({ it.createdAt }, { it.id })

fun List<CompressionEvent>.latestCompressionEvent(): CompressionEvent? =
    maxWithOrNull(compressionEventOrder)

private fun MessageNode.withSelectedMessage(message: UIMessage): MessageNode {
    val newMessages = messages.toMutableList()
    var newMessageIndex = selectIndex
    if (newMessages.any { it.id == message.id }) {
        newMessages[newMessages.indexOfFirst { it.id == message.id }] = message
    } else {
        newMessages.add(message)
        newMessageIndex = newMessages.lastIndex
    }

    return copy(
        messages = newMessages,
        selectIndex = newMessageIndex
    )
}

@Serializable
data class MessageNode(
    val id: Uuid = Uuid.random(),
    val messages: List<UIMessage>,
    val selectIndex: Int = 0,
    @Transient
    val isFavorite: Boolean = false,
) {
    val currentMessage get() = if (messages.isEmpty() || selectIndex !in messages.indices) {
        throw IllegalStateException("MessageNode has no valid current message: messages.size=${messages.size}, selectIndex=$selectIndex")
    } else {
        messages[selectIndex]
    }

    val role get() = messages.firstOrNull()?.role ?: MessageRole.USER

    companion object {
        fun of(message: UIMessage) = MessageNode(
            messages = listOf(message),
            selectIndex = 0
        )
    }
}

fun UIMessage.toMessageNode(): MessageNode {
    return MessageNode(
        messages = listOf(this),
        selectIndex = 0
    )
}

/**
 * 递归展开所有 parts，包括工具调用结果中的嵌套 parts。
 */
private fun List<UIMessagePart>.collectAllParts(): List<UIMessagePart> =
    this + filterIsInstance<UIMessagePart.Tool>().flatMap { it.output.collectAllParts() }

/**
 * 提取 part 中引用的本地文件 URI，新增文件类型时只需在此处添加。
 */
private fun UIMessagePart.fileUri(): Uri? = when (this) {
    is UIMessagePart.Image -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Document -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Video -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Audio -> url.takeIf { it.startsWith("file://") }?.toUri()
    else -> null
}
