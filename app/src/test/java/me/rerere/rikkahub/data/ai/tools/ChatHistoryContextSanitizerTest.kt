package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatHistoryContextSanitizerTest {
    @Test
    fun `clears older history tool output but preserves the latest turn`() {
        val oldOutput = """{"groups":[{"top_snippets":[{"ref":"c:n:m","snippet":"old result"}]}]}"""
        val messages = listOf(
            message(MessageRole.USER, UIMessagePart.Text("first question")),
            message(MessageRole.ASSISTANT, tool("conversation_search", oldOutput)),
            message(MessageRole.USER, UIMessagePart.Text("next question")),
            message(MessageRole.ASSISTANT, tool("read_chat_history", "latest result")),
        )

        val sanitized = clearHistoricalChatHistoryResultsForModelContext(messages)

        assertEquals("""{"cleared":true}""", (sanitized[1].parts.single() as UIMessagePart.Tool).output.single().let { (it as UIMessagePart.Text).text })
        assertEquals("latest result", (sanitized[3].parts.single() as UIMessagePart.Tool).output.single().let { (it as UIMessagePart.Text).text })
    }

    private fun message(role: MessageRole, part: UIMessagePart) = UIMessage(role = role, parts = listOf(part))

    private fun tool(name: String, output: String) = UIMessagePart.Tool(
        toolCallId = name,
        toolName = name,
        input = "{}",
        output = listOf(UIMessagePart.Text(output)),
    )
}
