package me.rerere.rikkahub.data.index

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * 决定一条消息里哪些内容进索引。
 *
 * 词法索引(FTS)和向量索引共用这一份实现，避免两边口径漂移：
 * 只取用户与助手消息的**顶层纯文本**。工具消息、系统消息、思考内容、工具调用与结果、
 * 服务端工具、附件内容、上下文压缩检查点一律不进索引。
 *
 * 改动规则时要同时递增 [EXTRACT_VERSION]，让索引任务知道需要重建。
 */
object IndexableText {
    const val EXTRACT_VERSION = 1

    private val indexableRoles = setOf(MessageRole.USER, MessageRole.ASSISTANT)

    /** 返回该消息要索引的文本；返回 null 表示这条消息不进索引。 */
    fun extract(message: UIMessage): String? {
        if (message.role !in indexableRoles) return null
        if (message.isContextCheckpoint) return null
        val text = message.parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString(separator = "\n") { it.text }
        return text.takeIf { it.isNotBlank() }
    }
}
