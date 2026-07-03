package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.CompressionEvent
import me.rerere.rikkahub.service.collectCompressionSummaryFromStream
import me.rerere.rikkahub.service.shouldFallbackToNonStreamingCompression
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ChatCompressionSupportTest {
    @Test
    fun `renderedListIndexForMessage accounts for inserted compression cards`() {
        val compressionEvents = listOf(
            CompressionEvent(id = 1L, boundaryIndex = 0, createdAt = Instant.EPOCH),
            CompressionEvent(id = 2L, boundaryIndex = 2, createdAt = Instant.EPOCH),
            CompressionEvent(id = 3L, boundaryIndex = 2, createdAt = Instant.EPOCH),
        )

        assertEquals(1, renderedListIndexForMessage(0, compressionEvents))
        assertEquals(2, renderedListIndexForMessage(1, compressionEvents))
        assertEquals(5, renderedListIndexForMessage(2, compressionEvents))
    }

    @Test
    fun `findCompressionListIndex returns inserted card position`() {
        val compressionEvents = listOf(
            CompressionEvent(id = 10L, boundaryIndex = 0, createdAt = Instant.EPOCH),
            CompressionEvent(id = 20L, boundaryIndex = 2, createdAt = Instant.EPOCH),
        )

        assertEquals(0, findCompressionListIndex(10L, compressionEvents, messageCount = 3))
        assertEquals(3, findCompressionListIndex(20L, compressionEvents, messageCount = 3))
        assertNull(findCompressionListIndex(99L, compressionEvents, messageCount = 3))
    }

    @Test
    fun `streaming compression chunks are merged into final summary`() = runBlocking {
        val summary = collectCompressionSummaryFromStream(
            prompt = "compress",
            model = Model(modelId = "test"),
            chunks = flowOf(
                assistantDelta("first "),
                assistantDelta("second"),
                usageOnlyChunk(),
            ),
        )

        assertEquals("first second", summary)
    }

    @Test
    fun `usage only compression stream does not create summary`() {
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                collectCompressionSummaryFromStream(
                    prompt = "compress",
                    model = Model(modelId = "test"),
                    chunks = flowOf(usageOnlyChunk()),
                )
            }
        }

        assertEquals("Failed to generate compressed summary", error.message)
    }

    @Test
    fun `compression stream cancellation interrupts collection`() {
        var chunkCount = 0

        assertThrows(CancellationException::class.java) {
            runBlocking {
                collectCompressionSummaryFromStream(
                    prompt = "compress",
                    model = Model(modelId = "test"),
                    chunks = flow {
                        emit(assistantDelta("partial"))
                        emit(assistantDelta(" ignored"))
                    },
                    onChunk = {
                        chunkCount++
                        throw CancellationException("cancelled")
                    },
                )
            }
        }

        assertEquals(1, chunkCount)
    }

    @Test
    fun `response api compression does not fallback to non streaming`() {
        val responseProvider = ProviderSetting.OpenAI(useResponseApi = true)
        val chatCompletionsProvider = ProviderSetting.OpenAI(useResponseApi = false)
        val unsupported = UnsupportedOperationException("stream is not supported")

        assertFalse(shouldFallbackToNonStreamingCompression(responseProvider, unsupported))
        assertTrue(shouldFallbackToNonStreamingCompression(chatCompletionsProvider, unsupported))
        assertFalse(
            shouldFallbackToNonStreamingCompression(
                chatCompletionsProvider,
                CancellationException("cancelled"),
            )
        )
    }

    private fun assistantDelta(text: String): MessageChunk = MessageChunk(
        id = "chunk",
        model = "test",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(text)),
                ),
                message = null,
                finishReason = null,
            )
        ),
    )

    private fun usageOnlyChunk(): MessageChunk = MessageChunk(
        id = "usage",
        model = "test",
        choices = emptyList(),
        usage = TokenUsage(promptTokens = 1, completionTokens = 2, totalTokens = 3),
    )
}
