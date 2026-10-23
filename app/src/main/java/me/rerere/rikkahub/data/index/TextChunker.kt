package me.rerere.rikkahub.data.index

import java.security.MessageDigest

/**
 * 一个块在原文中的位置。
 *
 * 只记偏移和内容哈希，不存原文：向量库需要原文时按偏移回主库取。
 *
 * @param start 原文起始下标（含，UTF-16 code unit）
 * @param end 原文结束下标（不含）
 * @param contentHash 块文本的稳定哈希，用于跳过未变化的重新计算
 */
data class TextChunk(
    val index: Int,
    val start: Int,
    val end: Int,
    val contentHash: String
) {
    val length: Int get() = end - start

    fun textOf(source: String): String = source.substring(start, end)
}

/**
 * 把一段文本切成约 [TARGET_CHARS] 字符的块，块间重叠一个句子。
 *
 * 切分顺序是段落 → 句子 → 硬切：先按换行和句末标点得到句子，再把句子累加到目标长度；
 * 没有任何标点的超长句子在目标长度处硬切。切点不会落在代理对中间。
 */
object TextChunker {
    const val CHUNK_VERSION = 1

    /** 目标块长度（Unicode 字符，按 UTF-16 code unit 计） */
    const val TARGET_CHARS = 1200

    /** 单个块允许溢出的上限：最后一个句子可能把块顶过目标长度 */
    const val MAX_CHARS = TARGET_CHARS * 2

    /** 单条消息最多产出多少块，防止超长粘贴把索引撑爆 */
    const val MAX_CHUNKS_PER_MESSAGE = 64

    private val sentenceEnds = charArrayOf('。', '！', '？', '；', '…', '!', '?', ';', '\n')

    /** 太短且不是唯一一块时，并入上一块，避免出现一两个字一块 */
    private const val MIN_CHARS = 80

    fun chunk(text: String): List<TextChunk> {
        if (text.isBlank()) return emptyList()
        val spans = splitSpans(text)
        if (spans.isEmpty()) return emptyList()

        val chunks = ArrayList<TextChunk>()
        var cursor = 0
        while (cursor < spans.size && chunks.size < MAX_CHUNKS_PER_MESSAGE) {
            var last = cursor
            while (last + 1 < spans.size && spans[last + 1].end - spans[cursor].start <= TARGET_CHARS) {
                last++
            }
            val span = trimRange(text, spans[cursor].start, spans[last].end)
            if (span == null) {
                cursor = if (last == cursor) cursor + 1 else last
                continue
            }
            val nextCursor = if (last == cursor) cursor + 1 else last
            val isLastSpan = nextCursor >= spans.size
            val tooShort = span.end - span.start < MIN_CHARS
            if (tooShort && chunks.isNotEmpty() && !isLastSpan) {
                // 太短：把它并进上一块（不重叠，直接延长上一块）
                val previous = chunks.removeAt(chunks.lastIndex)
                val merged = trimRange(text, previous.start, span.end) ?: span
                chunks += TextChunk(
                    index = previous.index,
                    start = merged.start,
                    end = merged.end,
                    contentHash = hashOf(text, merged.start, merged.end)
                )
            } else {
                chunks += TextChunk(
                    index = chunks.size,
                    start = span.start,
                    end = span.end,
                    contentHash = hashOf(text, span.start, span.end)
                )
            }
            cursor = nextCursor
        }
        return chunks
    }

    /** 块的稳定哈希：SHA-256 前 32 个十六进制字符足够区分内容 */
    fun hashOf(text: String, start: Int, end: Int): String =
        hashOfText(text.substring(start, end))

    fun hashOfText(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(32)
        for (i in 0 until 16) {
            val byte = digest[i].toInt() and 0xFF
            builder.append(HEX[byte ushr 4]).append(HEX[byte and 0x0F])
        }
        return builder.toString()
    }

    private const val HEX = "0123456789abcdef"

    /** 按句子边界把文本切成首尾相接、覆盖全文的区间；超长句子在目标长度处硬切 */
    private fun splitSpans(text: String): List<IntRange> {
        val spans = ArrayList<IntRange>()
        var start = 0
        var i = 0
        while (i < text.length) {
            if (isSentenceEnd(text, i)) {
                // 句末标点归入当前句子，连续标点算一个边界
                var end = i + 1
                while (end < text.length && isSentenceEnd(text, end)) end++
                spans += start until end
                start = end
                i = end
                continue
            }
            // 硬切不包含当前位置的字符，切点落在字符之前
            if (i - start >= TARGET_CHARS && isSafeCut(text, i)) {
                spans += start until i
                start = i
            }
            i++
        }
        if (start < text.length) spans += start until text.length
        return spans
    }

    private fun isSentenceEnd(text: String, index: Int): Boolean {
        val char = text[index]
        if (sentenceEnds.any { it == char }) return true
        // 英文句点只在后面是空白或结尾时算句子边界，避免切开 3.14 / file.txt
        if (char == '.') {
            val next = text.getOrNull(index + 1) ?: return true
            return next.isWhitespace()
        }
        return false
    }

    /** 硬切时不要把代理对（emoji 等）切两半 */
    private fun isSafeCut(text: String, index: Int): Boolean {
        if (index <= 0 || index >= text.length) return true
        return !(text[index - 1].isHighSurrogate() && text[index].isLowSurrogate())
    }

    private fun trimRange(text: String, start: Int, end: Int): IntRange? {
        var left = start
        var right = end
        while (left < right && text[left].isWhitespace()) left++
        while (right > left && text[right - 1].isWhitespace()) right--
        return if (left >= right) null else left until right
    }
}
