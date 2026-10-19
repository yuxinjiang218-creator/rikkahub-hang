package me.rerere.rikkahub.data.ai.tools

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

/**
 * 清空整段会话里的搜索/检索结果正文。
 *
 * 只由用户手动触发（聊天页「清除搜索结果」）：组装模型请求时不做任何自动清理，
 * 否则每发一条新消息，之前的搜索结果都会从上下文里消失，模型再也拿不回这些证据。
 */
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
