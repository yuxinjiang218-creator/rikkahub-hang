package me.rerere.rikkahub.data.db.fts

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.index.IndexableText
import me.rerere.rikkahub.data.index.RecallSource
import me.rerere.rikkahub.data.model.Conversation
import java.time.Instant

data class MessageSearchResult(
    val nodeId: String,
    val messageId: String,
    val conversationId: String,
    val title: String,
    val updateAt: Instant,
    val snippet: String,
    val role: MessageRole = MessageRole.ASSISTANT,
    val createdAt: Instant = Instant.EPOCH,
    val isSelected: Boolean = true,
    // 这条结果是被哪几路召回的：词法 raw/segment/token、向量 vector
    val matchedBy: Set<RecallSource> = emptySet(),
)

enum class MessageSearchSort(val orderBy: String) {
    RELEVANCE("rank, update_at DESC"),
    NEWEST_FIRST("update_at DESC, rank"),
    OLDEST_FIRST("update_at ASC, rank"),
}

private const val TAG = "MessageFtsManager"
private const val META_EXTRACT_VERSION = "fts_extract_version"

class MessageFtsManager(private val database: AppDatabase) {

    private val db get() = database.openHelper.writableDatabase

    suspend fun indexConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        val conversationId = conversation.id.toString()
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
        conversation.messageNodes.forEach { node ->
            node.messages.forEach { message ->
                val text = message.extractFtsText()
                if (text != null) {
                    db.execSQL(
                        "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) VALUES (?, ?, ?, ?, ?, ?)",
                        arrayOf(
                            text,
                            node.id.toString(),
                            message.id.toString(),
                            conversationId,
                            conversation.title,
                            conversation.updateAt.toEpochMilli().toString(),
                        )
                    )
                }
            }
        }
    }

    suspend fun updateTitle(conversationId: String, title: String) = withContext(Dispatchers.IO) {
        db.execSQL("UPDATE message_fts SET title = ? WHERE conversation_id = ?", arrayOf(title, conversationId))
    }

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts")
    }

    /** 已建索引时使用的文本提取版本；null 表示还没记录过（需要重建） */
    suspend fun storedExtractVersion() = withContext(Dispatchers.IO) {
        runCatching {
            db.query("SELECT value FROM fts_meta WHERE key = ?", arrayOf(META_EXTRACT_VERSION)).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.toIntOrNull() else null
            }
        }.getOrNull()
    }

    suspend fun setExtractVersion(version: Int) = withContext(Dispatchers.IO) {
        db.execSQL(
            "INSERT OR REPLACE INTO fts_meta(key, value) VALUES(?, ?)",
            arrayOf(META_EXTRACT_VERSION, version.toString())
        )
    }

    suspend fun search(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        // 只搜这些助手的对话，null 表示不限
        assistantIds: Collection<String>? = null,
        excludeConversationId: String? = null,
        conversationId: String? = null,
        limit: Int = 50,
        selectedOnly: Boolean = false,
        messageRole: String? = null,
    ): List<MessageSearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<MessageSearchResult>()
        if (assistantIds != null && assistantIds.isEmpty()) return@withContext results
        val whereClauses = mutableListOf("text MATCH jieba_query(?)")
        val args = mutableListOf<Any>(keyword)
        if (assistantIds != null) {
            whereClauses += """
                EXISTS (
                SELECT 1 FROM conversationentity AS conversation
                WHERE conversation.id = message_fts.conversation_id
                  AND conversation.assistant_id IN (${assistantIds.joinToString { "?" }})
            )
            """.trimIndent()
            args.addAll(assistantIds)
        }
        if (!excludeConversationId.isNullOrBlank()) {
            whereClauses += "conversation_id != ?"
            args += excludeConversationId
        }
        if (!conversationId.isNullOrBlank()) {
            whereClauses += "conversation_id = ?"
            args += conversationId
        }
        if (selectedOnly) {
            val roleClause = if (!messageRole.isNullOrBlank()) "AND json_extract(message.value, '$.role') = ?" else ""
            whereClauses += """
                EXISTS (
                    SELECT 1
                    FROM message_node AS node, json_each(node.messages) AS message
                    WHERE node.id = message_fts.node_id
                      AND node.conversation_id = message_fts.conversation_id
                      AND CAST(message.key AS INTEGER) = node.select_index
                      AND json_extract(message.value, '$.id') = message_fts.message_id
                      $roleClause
                )
            """.trimIndent()
            if (!messageRole.isNullOrBlank()) args += messageRole
        }
        args += limit.coerceIn(1, 200)
        val cursor = db.query(
            """
            SELECT node_id, message_id, conversation_id, title, update_at,
                   simple_snippet(message_fts, 0, '[', ']', '...', 30) AS snippet
            FROM message_fts
            WHERE ${whereClauses.joinToString(" AND ")}
            ORDER BY ${sort.orderBy}
            LIMIT ?
            """.trimIndent(),
            args.toTypedArray()
        )
        Log.i(TAG, "search: $keyword")
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    MessageSearchResult(
                        nodeId = it.getString(0),
                        messageId = it.getString(1),
                        conversationId = it.getString(2),
                        title = it.getString(3),
                        updateAt = Instant.ofEpochMilli(it.getLong(4)),
                        snippet = it.getString(5),
                    )
                )
            }
        }
        results
    }
}

/**
 * 词法索引与向量索引共用同一套提取规则：只取用户/助手消息的顶层纯文本。
 *
 * 工具输入输出、思考、附件、压缩检查点在 [IndexableText] 里已经排除。
 */
private fun UIMessage.extractFtsText(): String? =
    IndexableText.extract(this)?.take(MAX_FTS_TEXT_LENGTH)

private const val MAX_FTS_TEXT_LENGTH = 10_000
