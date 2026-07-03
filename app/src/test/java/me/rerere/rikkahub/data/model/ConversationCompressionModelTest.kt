package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class ConversationCompressionModelTest {
    @Test
    fun `updateCurrentMessages keeps nodes before compression boundary untouched`() {
        val node0 = UIMessage.user("u0").toMessageNode()
        val node1 = UIMessage.assistant("a1").toMessageNode()
        val node2 = UIMessage.user("u2").toMessageNode()
        val node3 = UIMessage.assistant("a3").toMessageNode()
        val conversation = Conversation.ofId(
            id = Uuid.random(),
            messages = listOf(node0, node1, node2, node3),
        )

        val updated = conversation.updateCurrentMessages(
            messages = listOf(
                UIMessage.user("u2-new"),
                UIMessage.assistant("a3-new"),
            ),
            startIndex = 2,
        )

        assertSame(node0, updated.messageNodes[0])
        assertSame(node1, updated.messageNodes[1])
        assertEquals(node2.id, updated.messageNodes[2].id)
        assertEquals(node3.id, updated.messageNodes[3].id)
        assertEquals("u2-new", updated.messageNodes[2].currentMessage.toText())
        assertEquals("a3-new", updated.messageNodes[3].currentMessage.toText())
    }

    @Test
    fun `updateCurrentMessages appends when writeback starts after last node`() {
        val conversation = Conversation.ofId(
            id = Uuid.random(),
            messages = listOf(
                UIMessage.user("u0").toMessageNode(),
                UIMessage.assistant("a1").toMessageNode(),
            ),
        )

        val updated = conversation.updateCurrentMessages(
            messages = listOf(UIMessage.user("u2")),
            startIndex = 2,
        )

        assertEquals(3, updated.messageNodes.size)
        assertEquals("u2", updated.messageNodes[2].currentMessage.toText())
    }

    @Test
    fun `latestCompressionEvent returns newest event by time and id`() {
        val baseTime = Instant.parse("2026-01-01T00:00:00Z")
        val latest = CompressionEvent(id = 3, boundaryIndex = 4, createdAt = baseTime.plusSeconds(1))
        val events = listOf(
            CompressionEvent(id = 1, boundaryIndex = 1, createdAt = baseTime),
            latest,
            CompressionEvent(id = 2, boundaryIndex = 2, createdAt = baseTime),
        )

        assertEquals(latest, events.latestCompressionEvent())
    }
}
