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
import kotlin.uuid.Uuid

class ConversationHistoryRecallTest {
    @Test
    fun `parseConversationHistoryRef round trips valid ref`() {
        val ref = ConversationHistoryRef(
            conversationId = Uuid.random(),
            nodeId = Uuid.random(),
            messageId = Uuid.random(),
        )

        val parsed = parseConversationHistoryRef(ref.toString())

        assertNotNull(parsed)
        assertEquals(ref, parsed)
    }

    @Test
    fun `parseConversationHistoryRef rejects invalid ref`() {
        assertNull(parseConversationHistoryRef("broken-ref"))
    }

    @Test
    fun `selectHistorySearchCandidates keeps diversity across conversations`() {
        val now = Instant.now()
        val results = listOf(
            messageResult("c1", "n1", "m1", now.plusSeconds(5)),
            messageResult("c1", "n2", "m2", now.plusSeconds(4)),
            messageResult("c1", "n3", "m3", now.plusSeconds(3)),
            messageResult("c1", "n4", "m4", now.plusSeconds(2)),
            messageResult("c2", "n5", "m5", now.plusSeconds(1)),
            messageResult("c3", "n6", "m6", now),
        )

        val selected = selectHistorySearchCandidates(
            results = results,
            requestedLimit = 5,
            perConversationLimit = 2,
        )

        assertEquals(listOf("m1", "m2", "m5", "m6"), selected.map { it.messageId })
    }

    @Test
    fun `buildHistoryQueryPlan keeps model query shape and extracts structured tokens`() {
        val plan = buildHistoryQueryPlan("summary regenerate cancel E1234 ChatService.kt")

        assertEquals("summary regenerate cancel E1234 ChatService.kt", plan.rawQuery)
        assertTrue(plan.segmentQueries.contains("summary"))
        assertTrue(plan.segmentQueries.contains("regenerate"))
        assertTrue(plan.segmentQueries.contains("cancel"))
        assertTrue(plan.tokenQueries.contains("e1234"))
        assertTrue(plan.tokenQueries.contains("chatservice.kt"))
        assertFalse(plan.tokenQueries.contains("summary"))
    }

    @Test
    fun `minimumHistoryTokenMatches scales with token count`() {
        assertEquals(0, minimumHistoryTokenMatches(0))
        assertEquals(1, minimumHistoryTokenMatches(2))
        assertEquals(2, minimumHistoryTokenMatches(5))
        assertEquals(3, minimumHistoryTokenMatches(6))
    }

    @Test
    fun `buildRouteCandidates filters weak token matches by minimum threshold`() {
        val now = Instant.now()
        val tokenRoute = buildRouteCandidates(
            route = HistorySearchRoute.TOKEN,
            routeResults = listOf(
                listOf(messageResult("c1", "n1", "m1", now.plusSeconds(2))),
                listOf(messageResult("c1", "n1", "m1", now.plusSeconds(2))),
                listOf(messageResult("c2", "n2", "m2", now.plusSeconds(1))),
            ),
            totalTokenCount = 3,
        )

        assertEquals(listOf("m1"), tokenRoute.map { it.result.messageId })
        assertEquals(2, tokenRoute.first().matchedTokenCount)
    }

    @Test
    fun `mergeHistoryRouteCandidates rewards multi route hits but keeps raw route priority`() {
        val now = Instant.now()
        val rawOnly = messageResult("c1", "n1", "m1", now.plusSeconds(3))
        val multiHit = messageResult("c2", "n2", "m2", now.plusSeconds(2))

        val merged = mergeHistoryRouteCandidates(
            rawCandidates = listOf(
                HistoryRouteCandidate(rawOnly, HistorySearchRoute.RAW, routeHits = 1),
                HistoryRouteCandidate(multiHit, HistorySearchRoute.RAW, routeHits = 1),
            ),
            segmentCandidates = listOf(
                HistoryRouteCandidate(multiHit, HistorySearchRoute.SEGMENT, routeHits = 2),
            ),
            tokenCandidates = listOf(
                HistoryRouteCandidate(multiHit, HistorySearchRoute.TOKEN, routeHits = 2, matchedTokenCount = 2),
            ),
        )

        assertEquals(listOf("m2", "m1"), merged.map { it.messageId })
    }

    @Test
    fun `mergeHistoryRouteCandidates ranks vector route above raw when refs differ`() {
        val now = Instant.now()
        val raw = messageResult("c1", "n1", "m1", now.plusSeconds(2))
        val vector = messageResult("c2", "n2", "m2", now.plusSeconds(1))

        val merged = mergeHistoryRouteCandidates(
            rawCandidates = listOf(HistoryRouteCandidate(raw, HistorySearchRoute.RAW, routeHits = 1)),
            segmentCandidates = emptyList(),
            tokenCandidates = emptyList(),
            vectorCandidates = listOf(HistoryRouteCandidate(vector, HistorySearchRoute.VECTOR, routeHits = 1)),
        )

        assertEquals(listOf("m2", "m1"), merged.map { it.messageId })
    }

    @Test
    fun `mergeHistoryRouteCandidates deduplicates vector and local refs`() {
        val now = Instant.now()
        val local = messageResult("c1", "n1", "m1", now, snippet = "local snippet")
        val vector = messageResult("c1", "n1", "m1", now.plusSeconds(1), snippet = "vector snippet")

        val merged = mergeHistoryRouteCandidates(
            rawCandidates = listOf(HistoryRouteCandidate(local, HistorySearchRoute.RAW, routeHits = 1)),
            segmentCandidates = emptyList(),
            tokenCandidates = emptyList(),
            vectorCandidates = listOf(HistoryRouteCandidate(vector, HistorySearchRoute.VECTOR, routeHits = 1)),
        )

        assertEquals(listOf("m1"), merged.map { it.messageId })
        assertEquals("vector snippet", merged.single().snippet)
    }

    @Test
    fun `historySnippetKey normalizes highlight markers and whitespace`() {
        val key = historySnippetKey("c1:n1:m1", "...User [prefers]\n brief replies...")

        assertEquals("c1:n1:m1:user prefers brief replies", key)
    }

    @Test
    fun `filterNewHistorySearchResults removes only repeated snippets`() {
        val now = Instant.now()
        val first = messageResult("c1", "n1", "m1", now, snippet = "User [likes] Kotlin")
        val repeated = messageResult("c1", "n1", "m1", now, snippet = "User likes Kotlin")
        val differentSnippetSameMessage = messageResult("c1", "n1", "m1", now, snippet = "User works on RikkaHub")
        val seen = setOf(requireNotNull(historySnippetKey(historySearchResultRef(first), first.snippet)))

        val filtered = filterNewHistorySearchResults(
            results = listOf(repeated, differentSnippetSameMessage),
            seenSnippetKeys = seen,
        )

        assertEquals(listOf("User works on RikkaHub"), filtered.map { it.snippet })
    }

    @Test
    fun `groupHistorySearchResultsByConversation returns conversation level groups`() {
        val now = Instant.now()
        val groups = groupHistorySearchResultsByConversation(
            results = listOf(
                messageResult("c1", "n1", "m1", now.plusSeconds(1), role = MessageRole.USER),
                messageResult("c2", "n2", "m2", now.plusSeconds(3), role = MessageRole.ASSISTANT),
                messageResult("c1", "n3", "m3", now.plusSeconds(2), role = MessageRole.ASSISTANT),
            ),
            requestedLimit = 6,
        )

        assertEquals(listOf("c1", "c2"), groups.map { it.conversationId })
        assertEquals(2, groups.first().hitCount)
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), groups.first().rolesHit)
        assertEquals(listOf("m1", "m3"), groups.first().topResults.map { it.messageId })
    }

    private fun messageResult(
        conversationId: String,
        nodeId: String,
        messageId: String,
        createdAt: Instant,
        role: MessageRole = MessageRole.USER,
        snippet: String = "snippet",
    ) = MessageSearchResult(
        nodeId = nodeId,
        messageId = messageId,
        conversationId = conversationId,
        assistantId = "assistant",
        title = conversationId,
        role = role,
        createdAt = createdAt,
        updateAt = createdAt,
        snippet = snippet,
        isSelected = true,
    )
}
