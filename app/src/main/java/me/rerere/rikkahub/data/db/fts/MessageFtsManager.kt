package me.rerere.rikkahub.data.db.fts

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import java.time.Instant

internal const val CREATE_MESSAGE_FTS_TABLE_SQL = """
CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(
    text,
    node_id UNINDEXED,
    message_id UNINDEXED,
    conversation_id UNINDEXED,
    assistant_id UNINDEXED,
    role UNINDEXED,
    created_at UNINDEXED,
    title UNINDEXED,
    update_at UNINDEXED,
    is_selected UNINDEXED,
    tokenize = 'simple'
)
"""

internal const val CREATE_INDEX_META_TABLE_SQL = """
CREATE TABLE IF NOT EXISTS index_meta(
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
)
"""

data class MessageSearchResult(
    val nodeId: String,
    val messageId: String,
    val conversationId: String,
    val assistantId: String = "",
    val title: String,
    val role: MessageRole = MessageRole.ASSISTANT,
    val createdAt: Instant = Instant.EPOCH,
    val updateAt: Instant,
    val snippet: String,
    val isSelected: Boolean = true,
)

enum class MessageSearchSort(val orderBy: String) {
    RELEVANCE("rank, update_at DESC"),
    NEWEST_FIRST("update_at DESC, rank"),
    OLDEST_FIRST("update_at ASC, rank"),
}

private const val TAG = "MessageFtsManager"
private const val FTS_TABLE = "message_fts"
private const val META_TABLE = "index_meta"
private const val META_KEY_SCHEMA_VERSION = "message_fts_schema_version"
private const val META_KEY_READY = "message_fts_ready"
private const val FTS_SCHEMA_VERSION = 2

private val EXPECTED_FTS_COLUMNS = listOf(
    "text",
    "node_id",
    "message_id",
    "conversation_id",
    "assistant_id",
    "role",
    "created_at",
    "title",
    "update_at",
    "is_selected",
)

class MessageFtsManager(private val database: AppDatabase) {

    private val db get() = database.openHelper.writableDatabase

    suspend fun ensureSchema(): Boolean = withContext(Dispatchers.IO) {
        ensureMetaTable()
        val schemaVersion = getMeta(META_KEY_SCHEMA_VERSION)
        val currentColumns = readFtsColumns()
        val isCompatible = schemaVersion == FTS_SCHEMA_VERSION.toString() && currentColumns == EXPECTED_FTS_COLUMNS
        if (isCompatible) return@withContext true

        db.execSQL("DROP TABLE IF EXISTS $FTS_TABLE")
        db.execSQL(CREATE_MESSAGE_FTS_TABLE_SQL.trimIndent())
        putMeta(META_KEY_SCHEMA_VERSION, FTS_SCHEMA_VERSION.toString())
        putMeta(META_KEY_READY, "0")
        false
    }

    suspend fun isReady(): Boolean = withContext(Dispatchers.IO) {
        ensureMetaTable()
        getMeta(META_KEY_READY) == "1"
    }

    suspend fun markReady() = withContext(Dispatchers.IO) {
        ensureMetaTable()
        putMeta(META_KEY_READY, "1")
    }

    suspend fun indexConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        ensureSchema()
        db.beginTransaction()
        try {
            indexConversationUnchecked(conversation)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun indexConversations(conversations: List<Conversation>) = withContext(Dispatchers.IO) {
        if (conversations.isEmpty()) return@withContext
        ensureSchema()
        db.beginTransaction()
        try {
            conversations.forEach(::indexConversationUnchecked)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        ensureSchema()
        db.execSQL("DELETE FROM $FTS_TABLE WHERE conversation_id = ?", arrayOf(conversationId))
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        ensureSchema()
        db.execSQL("DELETE FROM $FTS_TABLE")
        putMeta(META_KEY_READY, "0")
    }

    suspend fun search(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
    ): List<MessageSearchResult> = withContext(Dispatchers.IO) {
        ensureSchema()
        searchInternal(
            sql = """
                SELECT node_id, message_id, conversation_id, assistant_id, role, created_at, title, update_at, is_selected,
                       simple_snippet($FTS_TABLE, 0, '[', ']', '...', 30) AS snippet
                FROM $FTS_TABLE
                WHERE text MATCH jieba_query(?)
                ORDER BY ${sort.orderBy}
                LIMIT 50
            """.trimIndent(),
            args = arrayOf(keyword),
            logMessage = "search: $keyword",
        )
    }

    suspend fun searchAssistantHistory(
        assistantId: String,
        keyword: String,
        excludeConversationId: String? = null,
        conversationId: String? = null,
        role: String? = null,
        limit: Int,
        selectedOnly: Boolean = true,
    ): List<MessageSearchResult> = withContext(Dispatchers.IO) {
        ensureSchema()
        val whereClauses = mutableListOf(
            "assistant_id = ?",
            "text MATCH jieba_query(?)",
        )
        val args = mutableListOf<Any>(assistantId, keyword)

        if (!excludeConversationId.isNullOrBlank()) {
            whereClauses += "conversation_id != ?"
            args += excludeConversationId
        }
        if (!conversationId.isNullOrBlank()) {
            whereClauses += "conversation_id = ?"
            args += conversationId
        }
        if (!role.isNullOrBlank()) {
            whereClauses += "role = ?"
            args += role
        }
        if (selectedOnly) {
            whereClauses += "is_selected = 1"
        }
        args += limit.toString()

        searchInternal(
            sql = """
                SELECT node_id, message_id, conversation_id, assistant_id, role, created_at, title, update_at, is_selected,
                       simple_snippet($FTS_TABLE, 0, '[', ']', '...', 30) AS snippet
                FROM $FTS_TABLE
                WHERE ${whereClauses.joinToString(separator = "\nAND ")}
                ORDER BY rank, created_at DESC, update_at DESC
                LIMIT ?
            """.trimIndent(),
            args = args.toTypedArray(),
            logMessage = "searchAssistantHistory: assistant=$assistantId query=$keyword role=${role ?: "any"} limit=$limit",
        )
    }

    private fun searchInternal(
        sql: String,
        args: Array<Any>,
        logMessage: String,
    ): List<MessageSearchResult> {
        val results = mutableListOf<MessageSearchResult>()
        val cursor = db.query(sql, args)
        Log.i(TAG, logMessage)
        cursor.use {
            while (it.moveToNext()) {
                results += MessageSearchResult(
                    nodeId = it.getString(0),
                    messageId = it.getString(1),
                    conversationId = it.getString(2),
                    assistantId = it.getString(3),
                    role = it.getString(4).toMessageRole(),
                    createdAt = Instant.ofEpochMilli(it.getLong(5)),
                    title = it.getString(6),
                    updateAt = Instant.ofEpochMilli(it.getLong(7)),
                    isSelected = it.getLong(8) == 1L,
                    snippet = it.getString(9),
                )
            }
        }
        return results
    }

    private fun insertNodes(
        conversation: Conversation,
        nodes: List<MessageNode>,
    ) {
        val conversationId = conversation.id.toString()
        val assistantId = conversation.assistantId.toString()
        nodes.forEach { node ->
            node.messages.forEachIndexed { messageIndex, message ->
                val text = message.extractFtsText()
                if (text.isBlank()) return@forEachIndexed
                db.execSQL(
            """
                    INSERT INTO $FTS_TABLE(
                        text, node_id, message_id, conversation_id, assistant_id, role, created_at, title, update_at, is_selected
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
                    arrayOf<Any>(
                        text,
                        node.id.toString(),
                        message.id.toString(),
                        conversationId,
                        assistantId,
                        message.role.name.lowercase(),
                        message.createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds().toString(),
                        conversation.title,
                        conversation.updateAt.toEpochMilli().toString(),
                        if (messageIndex == node.selectIndex) 1 else 0,
                    )
                )
            }
        }
    }

    private fun indexConversationUnchecked(conversation: Conversation) {
        val conversationId = conversation.id.toString()
        db.execSQL("DELETE FROM $FTS_TABLE WHERE conversation_id = ?", arrayOf(conversationId))
        insertNodes(
            conversation = conversation,
            nodes = conversation.messageNodes,
        )
    }

    private fun ensureMetaTable() {
        db.execSQL(CREATE_INDEX_META_TABLE_SQL.trimIndent())
    }

    private fun readFtsColumns(): List<String> {
        val cursor = db.query("PRAGMA table_info($FTS_TABLE)")
        cursor.use {
            if (it.count == 0) return emptyList()
            val columns = mutableListOf<String>()
            while (it.moveToNext()) {
                columns += it.getString(it.getColumnIndexOrThrow("name"))
            }
            return columns
        }
    }

    private fun getMeta(key: String): String? {
        val cursor = db.query("SELECT value FROM $META_TABLE WHERE key = ?", arrayOf(key))
        cursor.use {
            if (!it.moveToFirst()) return null
            return it.getString(0)
        }
    }

    private fun putMeta(key: String, value: String) {
        db.execSQL(
            "INSERT OR REPLACE INTO $META_TABLE(key, value) VALUES (?, ?)",
            arrayOf(key, value)
        )
    }
}

private fun UIMessage.extractFtsText(): String =
    parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .take(10_000)

private fun String.toMessageRole(): MessageRole = when (lowercase()) {
    "system" -> MessageRole.SYSTEM
    "user" -> MessageRole.USER
    "tool" -> MessageRole.TOOL
    else -> MessageRole.ASSISTANT
}
