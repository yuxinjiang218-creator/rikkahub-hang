package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.GenerationAttemptTracker
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class GenerationRetryPolicyTest {
    @Test
    fun `retry limit is clamped`() {
        assertEquals(0, normalizedGenerationRetryLimit(-1))
        assertEquals(3, normalizedGenerationRetryLimit(3))
        assertEquals(10, normalizedGenerationRetryLimit(99))
    }

    @Test
    fun `http transient statuses are retryable`() {
        listOf(408, 409, 425, 429, 500, 502, 503, 599).forEach { status ->
            val tracker = GenerationAttemptTracker().apply {
                beginAttempt(0)
                recordHttpStatus(status)
            }
            assertTrue("status $status should retry", isRetryableGenerationError(Exception("HTTP $status"), tracker))
        }
    }

    @Test
    fun `configuration statuses are not retryable`() {
        listOf(400, 401, 403, 404, 422).forEach { status ->
            val tracker = GenerationAttemptTracker().apply {
                beginAttempt(0)
                recordHttpStatus(status)
            }
            assertFalse("status $status should not retry", isRetryableGenerationError(Exception("HTTP $status"), tracker))
        }
    }

    @Test
    fun `temporary transport errors are retryable`() {
        assertTrue(isRetryableGenerationError(IOException("connection reset")))
        assertTrue(isRetryableGenerationError(Exception("provider is overloaded, try again")))
        assertTrue(isRetryableGenerationError(Exception("rate limit exceeded")))
    }

    @Test
    fun `cancellation and configuration text are not retryable`() {
        assertFalse(isRetryableGenerationError(CancellationException("cancelled")))
        assertFalse(isRetryableGenerationError(Exception("invalid api key")))
        assertFalse(isRetryableGenerationError(Exception("model not found")))
    }

    @Test
    fun `generated assistant content detection is conservative`() {
        assertFalse(hasGeneratedAssistantContent(listOf(UIMessage.user("hello"))))
        assertFalse(
            hasGeneratedAssistantContent(
                listOf(
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Text(""))
                    )
                )
            )
        )
        assertTrue(hasGeneratedAssistantContent(listOf(UIMessage.assistant("partial"))))
    }
}
