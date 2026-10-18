package me.rerere.rikkahub.data.repository

import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID as JavaUUID
import kotlin.uuid.Uuid

class ConversationHistoryRecallTest {
    @Test
    fun `history refs parse and reject malformed values`() {
        val ref = ConversationHistoryRef(Uuid.random(), Uuid.random(), Uuid.random())
        assertEquals(ref, parseConversationHistoryRef(ref.toString()))
        assertNull(parseConversationHistoryRef("not-a-ref"))
    }

    @Test
    fun `query plan keeps raw query and routes structured tokens`() {
        val plan = buildHistoryQueryPlan("summary regenerate E1234 ChatService.kt")
        assertEquals("summary regenerate E1234 ChatService.kt", plan.rawQuery)
        assertTrue(plan.segmentQueries.contains("summary"))
        assertTrue(plan.tokenQueries.contains("e1234"))
        assertTrue(plan.tokenQueries.contains("chatservice.kt"))
        assertFalse(plan.tokenQueries.contains("summary"))
    }

    @Test
    fun `read parameters are bounded and refs are deduplicated`() {
        val ref = "${Uuid.random()}:${Uuid.random()}:${Uuid.random()}"
        assertEquals(3, normalizeHistoryReadBefore(99))
        assertEquals(6, normalizeHistoryReadAfter(99))
        assertEquals(listOf(ref), normalizeHistoryReadRefs(listOf(" $ref ", ref)))
        assertEquals(3, normalizeHistoryReadRefs((1..5).map { "${Uuid.random()}:${Uuid.random()}:${Uuid.random()}" }).size)
        assertEquals(12, normalizeHistorySearchLimit(99))
    }

    @Test
    fun `search grouping keeps conversation diversity and role summaries`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val grouped = groupHistorySearchResultsByConversation(
            listOf(
                result("c1", "m1", now, MessageRole.USER),
                result("c1", "m2", now.plusSeconds(1), MessageRole.ASSISTANT),
                result("c2", "m3", now.plusSeconds(2), MessageRole.USER),
            ),
            requestedLimit = 6,
        )
        assertEquals(listOf(deterministicConversationId("c1"), deterministicConversationId("c2")), grouped.map { it.conversationId })
        assertEquals(2, grouped.first().hitCount)
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), grouped.first().rolesHit)
        assertNotNull(parseConversationHistoryRef(historySearchResultRef(result("c1", "m1", now, MessageRole.USER))))
    }

    @Test
    fun `already returned snippets are suppressed without suppressing other excerpts`() {
        val now = Instant.now()
        val repeated = result("c1", "m1", now, MessageRole.USER, "User [likes] Kotlin")
        val different = repeated.copy(snippet = "User works on RikkaHub")
        val seen = setOf(requireNotNull(historySnippetKey(historySearchResultRef(repeated), repeated.snippet)))
        assertEquals(listOf(different), filterNewHistorySearchResults(listOf(repeated, different), seen))
    }

    private fun result(
        conversationId: String,
        messageId: String,
        createdAt: Instant,
        role: MessageRole,
        snippet: String = "snippet",
    ): MessageSearchResult {
        val parsedConversationId = Uuid.parse(deterministicConversationId(conversationId))
        val parsedMessageId = Uuid.parse(deterministicConversationId(messageId))
        return MessageSearchResult(
            nodeId = Uuid.parse(deterministicConversationId("node-$messageId")).toString(),
            messageId = parsedMessageId.toString(),
            conversationId = parsedConversationId.toString(),
            title = conversationId,
            updateAt = createdAt,
            snippet = snippet,
            role = role,
            createdAt = createdAt,
        )
    }

    private fun deterministicConversationId(value: String) = JavaUUID.nameUUIDFromBytes(value.toByteArray()).toString()
}
