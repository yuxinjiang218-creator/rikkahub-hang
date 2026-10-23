package me.rerere.rikkahub.data.index

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexableTextTest {

    private fun message(
        role: MessageRole,
        vararg parts: UIMessagePart,
        checkpoint: Boolean = false
    ) = UIMessage(role = role, parts = parts.toList(), isContextCheckpoint = checkpoint)

    @Test
    fun `user text is indexed`() {
        val text = IndexableText.extract(message(MessageRole.USER, UIMessagePart.Text("你好，今天怎么样")))
        assertEquals("你好，今天怎么样", text)
    }

    @Test
    fun `assistant top level text is indexed`() {
        val text = IndexableText.extract(
            message(
                MessageRole.ASSISTANT,
                UIMessagePart.Reasoning("先想一想"),
                UIMessagePart.Text("我很好"),
            )
        )
        assertEquals("我很好", text)
    }

    @Test
    fun `system and tool roles are skipped`() {
        assertNull(IndexableText.extract(message(MessageRole.SYSTEM, UIMessagePart.Text("系统提示"))))
        assertNull(IndexableText.extract(message(MessageRole.TOOL, UIMessagePart.Text("工具输出"))))
    }

    @Test
    fun `reasoning only message is skipped`() {
        assertNull(IndexableText.extract(message(MessageRole.ASSISTANT, UIMessagePart.Reasoning("思考内容"))))
    }

    @Test
    fun `tool parts and their output are skipped`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "read_file",
            input = """{"path":"/etc/passwd"}""",
            output = listOf(UIMessagePart.Text("root:x:0:0"))
        )
        assertNull(IndexableText.extract(message(MessageRole.ASSISTANT, tool)))
    }

    @Test
    fun `server tool input and output are skipped`() {
        val part = UIMessagePart.ServerTool(
            toolCallId = "srv-1",
            toolName = "web_search",
            status = ServerToolStatus.COMPLETED,
        )
        assertNull(IndexableText.extract(message(MessageRole.ASSISTANT, part)))
    }

    @Test
    fun `attachments are skipped but text stays`() {
        val text = IndexableText.extract(
            message(
                MessageRole.USER,
                UIMessagePart.Text("看看这张图"),
                UIMessagePart.Image(url = "file:///sdcard/a.png"),
            )
        )
        assertEquals("看看这张图", text)
    }

    @Test
    fun `context checkpoint is skipped`() {
        val checkpoint = message(
            MessageRole.ASSISTANT,
            UIMessagePart.Text("这是前面所有对话的摘要"),
            checkpoint = true,
        )
        assertNull(IndexableText.extract(checkpoint))
    }

    @Test
    fun `blank text is skipped`() {
        assertNull(IndexableText.extract(message(MessageRole.USER, UIMessagePart.Text("   "))))
        assertNull(IndexableText.extract(message(MessageRole.USER)))
    }

    @Test
    fun `multiple text parts are joined`() {
        val text = IndexableText.extract(
            message(
                MessageRole.USER,
                UIMessagePart.Text("第一段"),
                UIMessagePart.Text("第二段"),
            )
        )
        assertNotNull(text)
        assertEquals("第一段\n第二段", text)
    }
}
