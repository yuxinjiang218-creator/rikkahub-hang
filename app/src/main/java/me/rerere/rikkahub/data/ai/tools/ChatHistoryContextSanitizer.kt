package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation

internal val HISTORY_TOOL_NAMES = setOf(
    "conversation_search",
    "read_chat_history",
    "read_chat_context",
    "conversation_read",
    "search_chat_history",
)
internal val SEARCH_RESULT_TOOL_NAMES = HISTORY_TOOL_NAMES + "search_web"
private const val CLEARED_OUTPUT = """{"cleared":true}"""

fun clearHistoricalChatHistoryResultsForModelContext(messages: List<UIMessage>): List<UIMessage> {
    val latestUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
    if (latestUserIndex <= 0) return messages
    var changed = false
    val sanitized = messages.mapIndexed { index, message ->
        if (index >= latestUserIndex) message else {
            var messageChanged = false
            val parts = message.parts.map { part ->
                if (part is UIMessagePart.Tool && part.toolName in HISTORY_TOOL_NAMES && part.isExecuted) {
                    messageChanged = true
                    part.copy(output = listOf(UIMessagePart.Text(CLEARED_OUTPUT)))
                } else part
            }
            if (messageChanged) {
                changed = true
                message.copy(parts = parts)
            } else message
        }
    }
    return if (changed) sanitized else messages
}

fun Conversation.clearSearchResults(): Conversation {
    var changed = false
    val updatedNodes = messageNodes.map { node ->
        var nodeChanged = false
        val updatedMessages = node.messages.map { message ->
            var messageChanged = false
            val updatedParts = message.parts.map { part ->
                if (part is UIMessagePart.Tool && part.toolName in SEARCH_RESULT_TOOL_NAMES && part.isExecuted) {
                    messageChanged = true
                    part.copy(output = listOf(UIMessagePart.Text(CLEARED_OUTPUT)))
                } else part
            }
            if (messageChanged) {
                changed = true
                nodeChanged = true
                message.copy(parts = updatedParts)
            } else message
        }
        if (nodeChanged) node.copy(messages = updatedMessages) else node
    }
    return if (changed) copy(messageNodes = updatedNodes) else this
}
