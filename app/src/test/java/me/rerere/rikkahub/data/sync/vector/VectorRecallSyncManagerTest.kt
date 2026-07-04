package me.rerere.rikkahub.data.sync.vector

import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class VectorRecallSyncManagerTest {
    @Test
    fun `toVectorUploadRequest marks only selectIndex message selected and includes metadata`() {
        val assistantId = Uuid.random()
        val conversationId = Uuid.random()
        val firstMessage = UIMessage(
            id = Uuid.random(),
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("old branch")),
            createdAt = LocalDateTime(2026, 1, 1, 0, 0),
        )
        val selectedMessage = UIMessage(
            id = Uuid.random(),
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("selected branch")),
            createdAt = LocalDateTime(2026, 1, 1, 0, 1),
        )
        val conversation = Conversation(
            id = conversationId,
            assistantId = assistantId,
            title = "Vector",
            messageNodes = listOf(
                MessageNode(
                    id = Uuid.random(),
                    messages = listOf(firstMessage, selectedMessage),
                    selectIndex = 1,
                )
            ),
            updateAt = Instant.ofEpochMilli(1234),
        )

        val request = conversation.toVectorUploadRequest()

        assertEquals(assistantId.toString(), request.assistantId)
        assertEquals(conversationId.toString(), request.conversationId)
        assertEquals("Vector", request.conversationTitle)
        assertEquals(1234, request.conversationUpdateAt)
        assertEquals(1, request.nodes.single().selectIndex)
        assertEquals(listOf(false, true), request.nodes.single().messages.map { it.isSelected })
        assertEquals(listOf("old branch", "selected branch"), request.nodes.single().messages.map { it.text })
    }
}
