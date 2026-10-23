package me.rerere.rikkahub.data.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {

    private fun sentences(count: Int, length: Int = 40): String =
        (1..count).joinToString(separator = "") { "第${it}句话".padEnd(length, '的') + "。" }

    @Test
    fun `blank text produces no chunks`() {
        assertTrue(TextChunker.chunk("").isEmpty())
        assertTrue(TextChunker.chunk("   \n\t ").isEmpty())
    }

    @Test
    fun `short text produces one chunk covering trimmed text`() {
        val text = "  你好，今天怎么样？  "
        val chunks = TextChunker.chunk(text)
        assertEquals(1, chunks.size)
        assertEquals("你好，今天怎么样？", chunks[0].textOf(text))
        assertEquals(0, chunks[0].index)
    }

    @Test
    fun `chunks map back to the original text`() {
        val text = sentences(120)
        val chunks = TextChunker.chunk(text)
        assertTrue("expected multiple chunks, got ${chunks.size}", chunks.size > 1)
        chunks.forEach { chunk ->
            val slice = chunk.textOf(text)
            assertTrue(slice.isNotBlank())
            assertTrue(chunk.start in text.indices)
            assertTrue(chunk.end <= text.length)
        }
    }

    @Test
    fun `chunk length stays near target`() {
        val text = sentences(200)
        val chunks = TextChunker.chunk(text)
        assertTrue(chunks.isNotEmpty())
        chunks.dropLast(1).forEach { chunk ->
            assertTrue(
                "chunk too long: ${chunk.length}",
                chunk.length <= TextChunker.MAX_CHARS
            )
        }
        assertTrue(chunks.first().length >= TextChunker.TARGET_CHARS / 2)
    }

    @Test
    fun `consecutive chunks overlap by one sentence`() {
        val text = sentences(200)
        val chunks = TextChunker.chunk(text)
        assertTrue(chunks.size > 1)
        var overlapped = false
        for (i in 0 until chunks.size - 1) {
            val current = chunks[i]
            val next = chunks[i + 1]
            assertTrue("chunks must advance", next.start > current.start)
            if (next.start < current.end) {
                overlapped = true
                val shared = text.substring(next.start, current.end)
                assertTrue("overlap should be a whole sentence trailing the previous chunk", shared.endsWith("。"))
            }
            assertTrue("chunks must not skip text", next.start <= current.end)
        }
        assertTrue("expected sentence overlap", overlapped)
    }

    @Test
    fun `text without punctuation is hard split`() {
        val text = "啊".repeat(TextChunker.TARGET_CHARS * 2 + 300)
        val chunks = TextChunker.chunk(text)
        assertTrue(chunks.size >= 2)
        chunks.forEach { chunk ->
            assertTrue(chunk.length <= TextChunker.MAX_CHARS)
        }
        assertEquals(text.length, chunks.last().end)
        chunks.zipWithNext().forEach { (left, right) ->
            assertEquals("no sentence to overlap, so chunks must be contiguous", left.end, right.start)
        }
    }

    @Test
    fun `surrogate pairs are never split`() {
        val emoji = "😀"
        val text = emoji.repeat(TextChunker.TARGET_CHARS + 200)
        val chunks = TextChunker.chunk(text)
        assertTrue(chunks.size >= 2)
        chunks.forEach { chunk ->
            val slice = chunk.textOf(text)
            assertTrue(
                "chunk must not start with a low surrogate",
                !slice.first().isLowSurrogate()
            )
            assertTrue(
                "chunk must not end with a high surrogate",
                !slice.last().isHighSurrogate()
            )
        }
    }

    @Test
    fun `chunk count is capped`() {
        val text = sentences(2000, length = 120)
        val chunks = TextChunker.chunk(text)
        assertEquals(TextChunker.MAX_CHUNKS_PER_MESSAGE, chunks.size)
    }

    @Test
    fun `hash is stable and content sensitive`() {
        val text = sentences(20)
        val first = TextChunker.chunk(text)
        val second = TextChunker.chunk(text)
        assertEquals(first.map { it.contentHash }, second.map { it.contentHash })

        val changed = TextChunker.chunk(text.replace("第1句话", "第1句改编"))
        assertNotEquals(first.first().contentHash, changed.first().contentHash)

        assertEquals(
            TextChunker.hashOfText("一样的文本"),
            TextChunker.hashOfText("一样的文本")
        )
    }

    @Test
    fun `tiny trailing fragment merges into previous chunk`() {
        // 第一块已经超过目标长度，剩下的零碎句子单独成块会短于下限，应当并回上一块
        val text = "啊".repeat(TextChunker.TARGET_CHARS) + "。" + "短。" + "尾"
        val chunks = TextChunker.chunk(text)
        assertEquals(1, chunks.size)
        assertEquals(text.length, chunks.last().end)
    }

    @Test
    fun `chunk indexes are sequential`() {
        val chunks = TextChunker.chunk(sentences(200))
        assertEquals(chunks.indices.toList(), chunks.map { it.index })
    }
}
