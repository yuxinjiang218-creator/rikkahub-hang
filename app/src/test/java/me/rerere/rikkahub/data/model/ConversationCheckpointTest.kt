package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.checkpointContext
import me.rerere.ai.ui.limitContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationCheckpointTest {
    @Test
    fun `context checkpoint is inserted after the anchor node without touching other nodes`() {
        val nodes = List(4) { UIMessage.user("message $it").toMessageNode() }
        val source = Conversation(assistantId = Uuid.random(), messageNodes = nodes)

        val result = source.insertContextCheckpoint(afterNodeId = nodes[1].id, summary = "summary")!!

        assertEquals(nodes.subList(0, 2), result.messageNodes.subList(0, 2))
        assertEquals(nodes.subList(2, 4), result.messageNodes.subList(3, 5))
        val checkpoint = result.messageNodes[2].currentMessage
        assertTrue(checkpoint.isContextCheckpoint)
        assertEquals("summary", checkpoint.toText())
        // 只有检查点之后的消息会继续发送给模型
        assertEquals(result.currentMessages.subList(2, 5), result.currentMessages.limitContext(0))
    }

    @Test
    fun `context checkpoint is not inserted when the anchor node is gone`() {
        val source = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(UIMessage.user("message").toMessageNode()),
        )

        assertNull(source.insertContextCheckpoint(afterNodeId = Uuid.random(), summary = "summary"))
    }

    @Test
    fun `context checkpoint stores the input boundary and retained messages`() {
        val nodes = List(4) { UIMessage.user("message $it").toMessageNode() }
        val source = Conversation(assistantId = Uuid.random(), messageNodes = nodes)

        val result = source.insertContextCheckpoint(
            afterNodeId = nodes[1].id,
            summary = "summary",
            keepRecentMessages = 1,
            sourceMessageIds = listOf(nodes[0].currentMessage.id, nodes[1].currentMessage.id),
            additionalPrompt = "focus",
            prompt = "prompt",
            promptTemplate = "template",
            modelId = Uuid.random(),
            usesChatModel = false,
            retainedMessageIds = listOf(nodes[2].currentMessage.id),
        )!!
        val checkpoint = result.currentMessages[2]

        assertEquals(1, checkpoint.contextCheckpointKeepRecentMessages)
        assertEquals(listOf(nodes[0].currentMessage.id, nodes[1].currentMessage.id), checkpoint.contextCheckpointSourceMessageIds)
        assertEquals(listOf(nodes[2].currentMessage.id), checkpoint.contextCheckpointRetainedMessageIds)
        assertEquals("focus", checkpoint.contextCheckpointAdditionalPrompt)
        assertEquals("prompt", checkpoint.contextCheckpointPrompt)
        assertEquals("template", checkpoint.contextCheckpointPromptTemplate)
        assertEquals(false, checkpoint.contextCheckpointUsesChatModel)
        assertEquals(
            listOf(
                nodes[0].currentMessage.id,
                nodes[1].currentMessage.id,
                checkpoint.id,
                nodes[2].currentMessage.id,
                nodes[3].currentMessage.id,
            ),
            result.currentMessages.map { it.id },
        )
        assertEquals(
            listOf(checkpoint, nodes[2].currentMessage, nodes[3].currentMessage),
            result.currentMessages.checkpointContext(),
        )
    }

    @Test
    fun `legacy checkpoint retained messages move after the summary`() {
        val nodes = List(5) { UIMessage.user("message $it").toMessageNode() }
        val checkpoint = UIMessage.user("summary").copy(
            isContextCheckpoint = true,
            contextCheckpointRetainedMessageIds = listOf(
                nodes[3].currentMessage.id,
                nodes[4].currentMessage.id,
            ),
        ).toMessageNode()
        val source = Conversation(
            assistantId = Uuid.random(),
            messageNodes = nodes + checkpoint,
        )

        val normalized = source.normalizeContextCheckpointOrder()

        assertEquals(
            nodes.subList(0, 3) + checkpoint + nodes.subList(3, 5),
            normalized.messageNodes,
        )
        assertEquals(normalized, normalized.normalizeContextCheckpointOrder())
    }

    @Test
    fun `checkpoint order normalization preserves one retained message and following messages`() {
        val nodes = List(4) { UIMessage.user("message $it").toMessageNode() }
        val checkpoint = UIMessage.user("summary").copy(
            isContextCheckpoint = true,
            contextCheckpointRetainedMessageIds = listOf(nodes[2].currentMessage.id),
        ).toMessageNode()
        val source = Conversation(
            assistantId = Uuid.random(),
            messageNodes = nodes.subList(0, 3) + checkpoint + nodes.subList(3, 4),
        )

        val normalized = source.normalizeContextCheckpointOrder()

        assertEquals(
            nodes.subList(0, 2) + checkpoint + nodes.subList(2, 4),
            normalized.messageNodes,
        )
        assertEquals(
            listOf(checkpoint.currentMessage, nodes[2].currentMessage, nodes[3].currentMessage),
            normalized.currentMessages.limitContext(0),
        )
    }
}
