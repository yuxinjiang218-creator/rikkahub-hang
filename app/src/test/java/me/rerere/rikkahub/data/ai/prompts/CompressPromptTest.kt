package me.rerere.rikkahub.data.ai.prompts

import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressPromptTest {
    @Test
    fun `legacy compression prompt placeholders are expanded`() {
        val sourceMessages = listOf(
            UIMessage.user("earlier summary").copy(isContextCheckpoint = true),
            UIMessage.user("new message"),
        )

        val prompt = buildCompressPrompt(
            prompt = "{dialogue_summary_text}|{incremental_messages}|{additional_context}|{locale}|{content}|{target_tokens}",
            additionalPrompt = "keep names",
            locale = "Chinese",
            keepRecentMessages = 2,
            sourceMessages = sourceMessages,
        )

        assertTrue(prompt.contains("earlier summary"))
        assertTrue(prompt.contains("new message"))
        assertTrue(prompt.contains("keep names"))
        assertTrue(prompt.contains("Chinese"))
        assertTrue(prompt.contains("2000"))
        assertFalse(prompt.contains("{dialogue_summary_text}"))
        assertFalse(prompt.contains("{incremental_messages}"))
        assertFalse(prompt.contains("{additional_context}"))
    }
}
