package me.rerere.rikkahub.data.index

import android.content.Context
import android.util.Log
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import java.io.File

/**
 * 基于 SQLite 向量扩展（`ai.sqlite:vector`）的本地向量索引，缓存库放在 noBackupFilesDir。
 *
 * 几个实现上的硬约束，都是实测出来的：
 * - 扩展是**连接级**的：每条连接都要跑一次 `vector_init`，所以这里只用一条连接并串行。
 * - 向量按 int8 存一份（每维 1 字节）。官方的 `vector_quantize` 会额外留一份 float32，
 *   磁盘变成 1.38 倍，所以量化自己做。
 * - 召回必须写 `ORDER BY distance`：流式扫描不带排序直接 `LIMIT` 拿到的是扫描顺序，
 *   不是最近的几条。加上排序后过滤才真正先于截断，代价约 20%。
 */
class SqliteVectorStore private constructor(
    private val database: SQLiteDatabase,
    private val databaseFile: File
) : VectorIndex {
    private val lock = Any()
    private var profile: VectorProfile? = null
    private var storedProfileId: String? = null
    private var closed = false

    init {
        enableWal()
        createSchema()
        storedProfileId = readMeta(META_PROFILE_ID)
        profile = loadProfile()
        profile?.let { registerVectorIndex(it) }
    }

    override fun ensureProfile(profile: VectorProfile) = synchronized(lock) {
        checkOpen()
        if (storedProfileId == profile.id) {
            this.profile = profile
            return@synchronized
        }
        // profile 变了：旧向量一律作废，直接清空重来，不做迁移
        database.beginTransaction()
        try {
            database.execSQL("DELETE FROM $TABLE_CHUNKS")
            writeMeta(META_PROFILE_ID, profile.id)
            writeMeta(META_PROFILE_DIMENSION, profile.dimension.toString())
            writeMeta(META_PROFILE_PROVIDER, profile.provider)
            writeMeta(META_PROFILE_MODEL, profile.model)
            writeMeta(META_PROFILE_DTYPE, profile.dtype)
            writeMeta(META_PROFILE_EXTRACT, profile.extractVersion.toString())
            writeMeta(META_PROFILE_CHUNK, profile.chunkVersion.toString())
            writeMeta(META_PROFILE_INSTRUCTION, profile.instruction)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        this.profile = profile
        storedProfileId = profile.id
        registerVectorIndex(profile)
        Log.i(TAG, "profile switched: ${profile.id}")
    }

    override fun activeProfileId(): String? = synchronized(lock) { storedProfileId }

    override fun upsert(chunks: List<VectorChunkRecord>) = synchronized(lock) {
        checkOpen()
        if (chunks.isEmpty()) return@synchronized
        val active = requireProfile()
        database.beginTransaction()
        try {
            val statement = database.compileStatement(
                """
                INSERT OR REPLACE INTO $TABLE_CHUNKS(
                    profile_id, conversation_id, assistant_id, role, node_id,
                    message_id, chunk_index, start_offset, end_offset, content_hash, q8
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            )
            try {
                for (chunk in chunks) {
                    require(chunk.embedding.size == active.dimension) {
                        "维度不匹配：profile=${active.dimension}, 实际=${chunk.embedding.size}"
                    }
                    statement.clearBindings()
                    statement.bindString(1, active.id)
                    statement.bindString(2, chunk.conversationId)
                    if (chunk.assistantId == null) statement.bindNull(3) else statement.bindString(3, chunk.assistantId)
                    statement.bindString(4, chunk.role)
                    statement.bindString(5, chunk.nodeId)
                    statement.bindString(6, chunk.messageId)
                    statement.bindLong(7, chunk.chunkIndex.toLong())
                    statement.bindLong(8, chunk.startOffset.toLong())
                    statement.bindLong(9, chunk.endOffset.toLong())
                    statement.bindString(10, chunk.contentHash)
                    statement.bindBlob(
                        11,
                        VectorQuantizer.quantize(VectorQuantizer.normalize(chunk.embedding), active.quantScale)
                    )
                    statement.executeInsert()
                }
            } finally {
                statement.close()
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    override fun deleteConversation(conversationId: String) = synchronized(lock) {
        checkOpen()
        val active = profile ?: return@synchronized
        database.execSQL(
            "DELETE FROM $TABLE_CHUNKS WHERE profile_id = ? AND conversation_id = ?",
            arrayOf(active.id, conversationId)
        )
    }

    override fun deleteMessage(messageId: String) = synchronized(lock) {
        checkOpen()
        val active = profile ?: return@synchronized
        database.execSQL(
            "DELETE FROM $TABLE_CHUNKS WHERE profile_id = ? AND message_id = ?",
            arrayOf(active.id, messageId)
        )
    }

    override fun search(
        embedding: FloatArray,
        limit: Int,
        filter: VectorFilter
    ): List<VectorHit> = synchronized(lock) {
        checkOpen()
        val active = requireProfile()
        require(embedding.size == active.dimension) {
            "维度不匹配：profile=${active.dimension}, 查询=${embedding.size}"
        }
        if (filter.assistantIds != null && filter.assistantIds.isEmpty()) return@synchronized emptyList<VectorHit>()
        val cappedLimit = limit.coerceIn(1, MAX_LIMIT)
        val quantized = VectorQuantizer.quantize(VectorQuantizer.normalize(embedding), active.quantScale)

        val conditions = StringBuilder("cv.profile_id = ?")
        val args = ArrayList<String>(4 + filter.excludeConversationIds.size + (filter.assistantIds?.size ?: 0))
        args += active.id
        filter.conversationId?.let {
            conditions.append(" AND cv.conversation_id = ?")
            args += it
        }
        filter.excludeConversationId?.let {
            conditions.append(" AND cv.conversation_id != ?")
            args += it
        }
        if (filter.excludeConversationIds.isNotEmpty()) {
            conditions.append(" AND cv.conversation_id NOT IN (${placeholders(filter.excludeConversationIds.size)})")
            args.addAll(filter.excludeConversationIds)
        }
        filter.assistantIds?.let { assistantIds ->
            conditions.append(" AND cv.assistant_id IN (${placeholders(assistantIds.size)})")
            args.addAll(assistantIds)
        }
        filter.role?.let {
            conditions.append(" AND cv.role = ?")
            args += it
        }

        val sql = """
            SELECT cv.conversation_id, cv.node_id, cv.message_id, cv.chunk_index,
                   cv.start_offset, cv.end_offset, cv.content_hash, s.distance
            FROM $TABLE_CHUNKS AS cv
            JOIN vector_full_scan('$TABLE_CHUNKS', '$COLUMN_VECTOR', ${hexLiteral(quantized)}) AS s
              ON cv.rowid = s.rowid
            WHERE $conditions
            ORDER BY s.distance
            LIMIT $cappedLimit
        """.trimIndent()

        val hits = ArrayList<VectorHit>(cappedLimit)
        database.rawQuery(sql, args.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                hits += VectorHit(
                    conversationId = cursor.getString(0),
                    nodeId = cursor.getString(1),
                    messageId = cursor.getString(2),
                    chunkIndex = cursor.getInt(3),
                    startOffset = cursor.getInt(4),
                    endOffset = cursor.getInt(5),
                    contentHash = cursor.getString(6),
                    distance = cursor.getDouble(7)
                )
            }
        }
        hits
    }

    override fun clear() = synchronized(lock) {
        checkOpen()
        database.beginTransaction()
        try {
            database.execSQL("DELETE FROM $TABLE_CHUNKS")
            database.execSQL("DELETE FROM $TABLE_META")
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        profile = null
        storedProfileId = null
    }

    override fun stats(): VectorIndexStats = synchronized(lock) {
        checkOpen()
        val active = profile
        var chunkCount = 0
        var conversationCount = 0
        if (active != null) {
            database.rawQuery(
                "SELECT COUNT(*), COUNT(DISTINCT conversation_id) FROM $TABLE_CHUNKS WHERE profile_id = ?",
                arrayOf(active.id)
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    chunkCount = cursor.getInt(0)
                    conversationCount = cursor.getInt(1)
                }
            }
        }
        VectorIndexStats(
            profileId = active?.id,
            chunkCount = chunkCount,
            conversationCount = conversationCount,
            fileSizeBytes = databaseFile.length() + File(databaseFile.path + "-wal").length()
        )
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        database.close()
    }

    private fun enableWal() {
        database.rawQuery("PRAGMA journal_mode=WAL", null).use { cursor ->
            if (cursor.moveToFirst()) Log.i(TAG, "journal_mode=${cursor.getString(0)}")
        }
        database.execSQL("PRAGMA synchronous=NORMAL")
    }

    private fun createSchema() {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_META(
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_CHUNKS(
                rowid INTEGER PRIMARY KEY AUTOINCREMENT,
                profile_id TEXT NOT NULL,
                conversation_id TEXT NOT NULL,
                assistant_id TEXT,
                role TEXT NOT NULL,
                node_id TEXT NOT NULL,
                message_id TEXT NOT NULL,
                chunk_index INTEGER NOT NULL,
                start_offset INTEGER NOT NULL,
                end_offset INTEGER NOT NULL,
                content_hash TEXT NOT NULL,
                q8 BLOB NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS idx_chunk_vector_message
            ON $TABLE_CHUNKS(profile_id, message_id, chunk_index)
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_chunk_vector_conversation
            ON $TABLE_CHUNKS(profile_id, conversation_id)
            """.trimIndent()
        )
    }

    /** 扩展的索引配置是连接级的，换维度后必须重新注册 */
    private fun registerVectorIndex(profile: VectorProfile) {
        database.rawQuery(
            "SELECT vector_init('$TABLE_CHUNKS', '$COLUMN_VECTOR', ?)",
            arrayOf("type=INT8,dimension=${profile.dimension},distance=COSINE")
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val result = cursor.getString(0)
                if (!result.isNullOrEmpty()) error("vector_init 失败: $result")
            }
        }
    }

    /**
     * 恢复已落库的 profile 配置。
     *
     * 只用于拿到维度和量化系数，profile 的身份以 [storedProfileId] 为准，
     * 所以这里不需要（也不能）重算出同一个 profile id。
     */
    private fun loadProfile(): VectorProfile? {
        val dimension = readMeta(META_PROFILE_DIMENSION)?.toIntOrNull() ?: return null
        if (dimension <= 0) return null
        return VectorProfile(
            provider = readMeta(META_PROFILE_PROVIDER) ?: "unknown",
            model = readMeta(META_PROFILE_MODEL) ?: "unknown",
            dimension = dimension,
            dtype = readMeta(META_PROFILE_DTYPE) ?: "int8",
            extractVersion = readMeta(META_PROFILE_EXTRACT)?.toIntOrNull() ?: IndexableText.EXTRACT_VERSION,
            chunkVersion = readMeta(META_PROFILE_CHUNK)?.toIntOrNull() ?: TextChunker.CHUNK_VERSION,
            instruction = readMeta(META_PROFILE_INSTRUCTION) ?: "",
            quantScale = VectorQuantizer.scaleFor(dimension)
        )
    }

    private fun readMeta(key: String): String? {
        database.rawQuery("SELECT value FROM $TABLE_META WHERE key = ?", arrayOf(key)).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun writeMeta(key: String, value: String) {
        database.execSQL(
            "INSERT OR REPLACE INTO $TABLE_META(key, value) VALUES(?, ?)",
            arrayOf(key, value)
        )
    }

    private fun requireProfile(): VectorProfile =
        profile ?: error("向量索引尚未配置 profile，先调用 ensureProfile")

    private fun checkOpen() {
        check(!closed) { "向量索引已关闭" }
    }

    private fun placeholders(count: Int): String = List(count) { "?" }.joinToString(", ")

    companion object {
        private const val TAG = "SqliteVectorStore"
        private const val TABLE_CHUNKS = "chunk_vector"
        private const val TABLE_META = "index_meta"
        private const val COLUMN_VECTOR = "q8"
        private const val META_PROFILE_ID = "profile_id"
        private const val META_PROFILE_DIMENSION = "profile_dimension"
        private const val META_PROFILE_PROVIDER = "profile_provider"
        private const val META_PROFILE_MODEL = "profile_model"
        private const val META_PROFILE_DTYPE = "profile_dtype"
        private const val META_PROFILE_EXTRACT = "profile_extract_version"
        private const val META_PROFILE_CHUNK = "profile_chunk_version"
        private const val META_PROFILE_INSTRUCTION = "profile_instruction"
        private const val MAX_LIMIT = 500

        /**
         * 打开（必要时创建）向量缓存库。
         *
         * 缓存库丢失或损坏时直接删掉重建：它只是索引，主库才是唯一事实来源。
         */
        fun open(context: Context, file: File = SQLiteConfiguration.vectorDatabaseFile(context)): SqliteVectorStore {
            file.parentFile?.mkdirs()
            return SqliteVectorStore(openDatabase(context, file), file)
        }

        private fun openDatabase(context: Context, file: File): SQLiteDatabase {
            val flags = SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY
            val configuration = SQLiteConfiguration.configureVector(
                context,
                SQLiteDatabaseConfiguration(file.path, flags)
            )
            return SQLiteDatabase.openDatabase(configuration, null) {
                // 缓存库损坏：删掉重建，不向上抛，搜索侧会降级回词法召回
                Log.e(TAG, "vector cache database corrupted, recreating: ${file.path}")
                file.delete()
            }
        }

        /** 向量以十六进制字面量内联，避免 rawQuery 只能绑定字符串的限制 */
        private fun hexLiteral(bytes: ByteArray): String {
            val builder = StringBuilder(bytes.size * 2 + 3)
            builder.append("X'")
            for (byte in bytes) {
                val value = byte.toInt() and 0xFF
                builder.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
            }
            builder.append('\'')
            return builder.toString()
        }

        private const val HEX = "0123456789ABCDEF"
    }
}
