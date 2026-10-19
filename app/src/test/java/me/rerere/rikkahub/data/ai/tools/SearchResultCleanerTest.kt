package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchResultCleanerTest {
    @Test
    fun `clears web and chat history search outputs across all message branches`() {
        val conversation = Conversation.ofId(
            id = kotlin.uuid.Uuid.random(),
            assistantId = kotlin.uuid.Uuid.random(),
            messages = listOf(
                MessageNode(
                    id = kotlin.uuid.Uuid.random(),
                    messages = listOf(message(MessageRole.ASSISTANT, tool("search_web", "web result"))),
                ),
                MessageNode(
                    id = kotlin.uuid.Uuid.random(),
                    messages = listOf(
                        message(MessageRole.ASSISTANT, tool("conversation_search", "history result")),
                        message(MessageRole.ASSISTANT, tool("other_tool", "keep this")),
                    ),
                ),
            ),
        )

        val cleared = conversation.clearSearchResults()

        assertEquals("""{"cleared":true}""", toolOutput(cleared.messageNodes[0].currentMessage, "search_web"))
        assertEquals("""{"cleared":true}""", toolOutput(cleared.messageNodes[1].messages[0], "conversation_search"))
        assertEquals("keep this", toolOutput(cleared.messageNodes[1].messages[1], "other_tool"))
    }

    @Test
    fun `clearing is idempotent and keeps conversation when nothing to clear`() {
        val untouched = Conversation.ofId(
            id = kotlin.uuid.Uuid.random(),
            assistantId = kotlin.uuid.Uuid.random(),
            messages = listOf(
                MessageNode(
                    id = kotlin.uuid.Uuid.random(),
                    messages = listOf(message(MessageRole.ASSISTANT, tool("other_tool", "keep this"))),
                ),
            ),
        )

        assertEquals(untouched, untouched.clearSearchResults())
    }

    private fun toolOutput(message: UIMessage, name: String) =
        (message.parts.single { it is UIMessagePart.Tool && it.toolName == name } as UIMessagePart.Tool)
            .output.single().let { (it as UIMessagePart.Text).text }

    private fun message(role: MessageRole, part: UIMessagePart) = UIMessage(role = role, parts = listOf(part))

    private fun tool(name: String, output: String) = UIMessagePart.Tool(
        toolCallId = name,
        toolName = name,
        input = "{}",
        output = listOf(UIMessagePart.Text(output)),
    )
}
