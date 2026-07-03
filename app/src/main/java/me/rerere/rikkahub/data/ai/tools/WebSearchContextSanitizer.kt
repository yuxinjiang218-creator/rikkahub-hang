package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

private val WEB_SEARCH_CONTEXT_TOOL_NAMES = setOf("search_web", "scrape_web")
private val CHAT_HISTORY_CONTEXT_TOOL_NAMES = setOf(
    "conversation_search",
    "read_chat_history",
    // Legacy lite name, kept so old messages are compacted too.
    "search_chat_history",
)

private const val CLEARED_TOOL_OUTPUT = """{"cleared":true}"""

fun prepareMessagesForModelContext(
    messages: List<UIMessage>,
    preserveWebSearchContext: Boolean,
    preserveChatHistoryToolContext: Boolean = false,
): List<UIMessage> {
    var prepared = messages
    if (!preserveWebSearchContext) {
        prepared = clearHistoricalWebSearchResultsForModelContext(prepared)
    }
    if (!preserveChatHistoryToolContext) {
        prepared = clearHistoricalChatHistoryResultsForModelContext(prepared)
    }
    return prepared
}

fun clearHistoricalWebSearchResultsForModelContext(messages: List<UIMessage>): List<UIMessage> =
    clearHistoricalToolOutputsForModelContext(
        messages = messages,
        toolNames = WEB_SEARCH_CONTEXT_TOOL_NAMES,
    )

fun clearHistoricalChatHistoryResultsForModelContext(messages: List<UIMessage>): List<UIMessage> =
    clearHistoricalToolOutputsForModelContext(
        messages = messages,
        toolNames = CHAT_HISTORY_CONTEXT_TOOL_NAMES,
    )

private fun clearHistoricalToolOutputsForModelContext(
    messages: List<UIMessage>,
    toolNames: Set<String>,
): List<UIMessage> {
    val latestUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
    if (latestUserIndex <= 0) return messages

    var changed = false
    val sanitized = messages.mapIndexed { index, message ->
        if (index >= latestUserIndex) {
            message
        } else {
            val sanitizedMessage = message.clearToolOutputs(toolNames)
            if (sanitizedMessage !== message) changed = true
            sanitizedMessage
        }
    }

    return if (changed) sanitized else messages
}

private fun UIMessage.clearToolOutputs(toolNames: Set<String>): UIMessage {
    var changed = false
    val sanitizedParts = parts.map { part ->
        if (part is UIMessagePart.Tool && part.toolName in toolNames && part.isExecuted) {
            changed = true
            part.copy(output = listOf(UIMessagePart.Text(CLEARED_TOOL_OUTPUT)))
        } else {
            part
        }
    }
    return if (changed) copy(parts = sanitizedParts) else this
}
