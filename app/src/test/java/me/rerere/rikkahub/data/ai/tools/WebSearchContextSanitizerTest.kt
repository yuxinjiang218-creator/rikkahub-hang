package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchContextSanitizerTest {
    @Test
    fun `clears historical web search and scrape outputs before latest user message`() {
        val searchTool = executedTool("search_call", "search_web", """{"query":"kotlin"}""", """{"answer":"large"}""")
        val scrapeTool = executedTool("scrape_call", "scrape_web", """{"url":"https://example.com"}""", """{"content":"large"}""")
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("old prompt"))),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(searchTool, scrapeTool)),
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("new prompt"))),
        )

        val sanitized = clearHistoricalWebSearchResultsForModelContext(messages)
        val tools = sanitized[1].getTools()

        assertNotEquals(messages, sanitized)
        assertTrue(tools.all { it.isExecuted })
        assertTrue(tools.all { tool ->
            tool.output.filterIsInstance<UIMessagePart.Text>().single().text == """{"cleared":true}"""
        })
    }

    @Test
    fun `preserves non target tools and tool identity fields`() {
        val metadata = buildJsonObject { put("source", "test") }
        val searchTool = executedTool(
            toolCallId = "search_call",
            toolName = "search_web",
            input = """{"query":"kotlin"}""",
            output = """{"answer":"large"}""",
            approvalState = ToolApprovalState.Approved,
            metadata = metadata,
        )
        val memoryTool = executedTool("memory_call", "memory_tool", """{"action":"create"}""", """{"content":"keep"}""")
        val messages = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(searchTool, memoryTool)),
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("new prompt"))),
        )

        val sanitizedTools = clearHistoricalWebSearchResultsForModelContext(messages)[0].getTools()
        val sanitizedSearchTool = sanitizedTools[0]

        assertEquals(searchTool.toolCallId, sanitizedSearchTool.toolCallId)
        assertEquals(searchTool.toolName, sanitizedSearchTool.toolName)
        assertEquals(searchTool.input, sanitizedSearchTool.input)
        assertEquals(searchTool.approvalState, sanitizedSearchTool.approvalState)
        assertEquals(metadata, sanitizedSearchTool.metadata)
        assertTrue(sanitizedSearchTool.isExecuted)
        assertSame(memoryTool, sanitizedTools[1])
    }

    @Test
    fun `preserves current same turn outputs after latest user message`() {
        val searchTool = executedTool("search_call", "search_web", """{"query":"kotlin"}""", """{"answer":"current"}""")
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("current prompt"))),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(searchTool)),
        )

        val sanitized = clearHistoricalWebSearchResultsForModelContext(messages)

        assertSame(messages, sanitized)
        assertEquals(searchTool, sanitized[1].getTools().single())
    }

    @Test
    fun `clears historical chat history search and read outputs`() {
        val searchTool = executedTool(
            "history_search_call",
            "conversation_search",
            """{"query":"old decision"}""",
            """{"items":[{"snippet":"large"}]}""",
        )
        val readTool = executedTool(
            "history_read_call",
            "read_chat_history",
            """{"refs":["ref"]}""",
            """{"items":[{"messages":[{"text":"large"}]}]}""",
        )
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("old prompt"))),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(searchTool, readTool)),
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("new prompt"))),
        )

        val tools = clearHistoricalChatHistoryResultsForModelContext(messages)[1].getTools()

        assertTrue(tools.all { tool ->
            tool.output.filterIsInstance<UIMessagePart.Text>().single().text == """{"cleared":true}"""
        })
    }

    @Test
    fun `preserve web mode still clears historical chat history outputs`() {
        val historyTool = executedTool(
            "history_search_call",
            "conversation_search",
            """{"query":"old decision"}""",
            """{"items":[{"snippet":"historical"}]}""",
        )
        val messages = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(historyTool)),
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("new prompt"))),
        )

        val prepared = prepareMessagesForModelContext(messages = messages, preserveWebSearchContext = true)

        assertEquals("""{"cleared":true}""", prepared[0].getTools().single().output.single().let {
            (it as UIMessagePart.Text).text
        })
    }

    private fun executedTool(
        toolCallId: String,
        toolName: String,
        input: String,
        output: String,
        approvalState: ToolApprovalState = ToolApprovalState.Auto,
        metadata: kotlinx.serialization.json.JsonObject? = null,
    ) = UIMessagePart.Tool(
        toolCallId = toolCallId,
        toolName = toolName,
        input = input,
        output = listOf(UIMessagePart.Text(output)),
        approvalState = approvalState,
        metadata = metadata,
    )
}
