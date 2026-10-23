package me.rerere.rikkahub.data.index

import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class HybridHistorySearchTest {

    private fun result(
        messageId: String,
        conversationId: String = "conv-1",
        snippet: String = "snippet-$messageId",
    ) = MessageSearchResult(
        nodeId = "node-$messageId",
        messageId = messageId,
        conversationId = conversationId,
        title = "title",
        updateAt = Instant.EPOCH,
        snippet = snippet,
        role = MessageRole.USER,
        createdAt = Instant.EPOCH,
    )

    @Test
    fun `vector only hits are returned`() {
        val outcome = fuseHistoryResults(
            lexicalRoutes = emptyList(),
            vectorResults = listOf(result("v1"), result("v2")),
            requestedLimit = 5,
            perConversationLimit = 4,
        )
        assertEquals(listOf("v1", "v2"), outcome.results.map { it.messageId })
        assertTrue(outcome.results.all { RecallSource.VECTOR in it.matchedBy })
        assertEquals(SemanticState.OK, outcome.semanticState)
    }

    @Test
    fun `lexical and vector hits merge with both sources`() {
        val outcome = fuseHistoryResults(
            lexicalRoutes = listOf(RecallSource.RAW to listOf(result("m1"), result("m2"))),
            vectorResults = listOf(result("m2"), result("m1")),
            requestedLimit = 5,
            perConversationLimit = 4,
        )
        assertEquals(2, outcome.results.size)
        outcome.results.forEach { item ->
            assertTrue("两条都该是双路命中", item.matchedBy.containsAll(setOf(RecallSource.RAW, RecallSource.VECTOR)))
        }
    }

    @Test
    fun `lexical snippet is preferred when both hit`() {
        val outcome = fuseHistoryResults(
            lexicalRoutes = listOf(RecallSource.RAW to listOf(result("m1", snippet = "带[关键词]的片段"))),
            vectorResults = listOf(result("m1", snippet = "纯文本片段")),
            requestedLimit = 3,
            perConversationLimit = 3,
        )
        assertEquals("带[关键词]的片段", outcome.results.single().snippet)
    }

    @Test
    fun `per conversation cap keeps results diverse`() {
        val lexical = (1..10).map { result("a$it", conversationId = "conv-a") }
        val outcome = fuseHistoryResults(
            lexicalRoutes = listOf(RecallSource.RAW to lexical),
            vectorResults = emptyList(),
            requestedLimit = 8,
            perConversationLimit = 2,
        )
        assertEquals(2, outcome.results.size)
        assertTrue(outcome.results.all { it.conversationId == "conv-a" })
        assertTrue("被截住了就说明还有更多", outcome.hasMore)
    }

    @Test
    fun `offset pages through fused results`() {
        val lexical = (1..6).map { result("m$it", conversationId = "conv-$it") }
        val first = fuseHistoryResults(
            lexicalRoutes = listOf(RecallSource.RAW to lexical),
            vectorResults = emptyList(),
            requestedLimit = 3,
            perConversationLimit = 1,
            offset = 0,
        )
        val second = fuseHistoryResults(
            lexicalRoutes = listOf(RecallSource.RAW to lexical),
            vectorResults = emptyList(),
            requestedLimit = 3,
            perConversationLimit = 1,
            offset = 3,
        )
        assertEquals(listOf("m1", "m2", "m3"), first.results.map { it.messageId })
        assertEquals(listOf("m4", "m5", "m6"), second.results.map { it.messageId })
        assertTrue(first.hasMore)
        assertFalse(second.hasMore)
    }

    @Test
    fun `empty input gives empty outcome`() {
        val outcome = fuseHistoryResults(
            lexicalRoutes = emptyList(),
            vectorResults = emptyList(),
            requestedLimit = 5,
            perConversationLimit = 2,
        )
        assertTrue(outcome.results.isEmpty())
        assertFalse(outcome.hasMore)
    }
}
