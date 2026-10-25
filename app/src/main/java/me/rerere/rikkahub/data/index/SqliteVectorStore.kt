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

    override fun chunkSignatures(conversationId: String): Map<String, String> = synchronized(lock) {
        checkOpen()
        val active = profile ?: return@synchronized emptyMap()
        val signatures = HashMap<String, String>()
        database.rawQuery(
            "SELECT message_id, chunk_index, content_hash FROM $TABLE_CHUNKS " +
                "WHERE profile_id = ? AND conversation_id = ?",
            arrayOf(active.id, conversationId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                signatures["${cursor.getString(0)}#${cursor.getInt(1)}"] = cursor.getString(2)
            }
        }
        signatures
    }

    override fun conversationAssistantId(conversationId: String): String? = synchronized(lock) {
        checkOpen()
        val active = profile ?: return@synchronized null
        database.rawQuery(
            "SELECT assistant_id FROM $TABLE_CHUNKS WHERE profile_id = ? AND conversation_id = ? LIMIT 1",
            arrayOf(active.id, conversationId)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }

    override fun deleteChunks(messageId: String, chunkIndexes: Collection<Int>) = synchronized(lock) {
        checkOpen()
        val active = profile ?: return@synchronized
        if (chunkIndexes.isEmpty()) return@synchronized
        database.beginTransaction()
        try {
            for (index in chunkIndexes) {
                database.execSQL(
                    "DELETE FROM $TABLE_CHUNKS WHERE profile_id = ? AND message_id = ? AND chunk_index = ?",
                    arrayOf(active.id, messageId, index.toString())
                )
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
        filter: VectorFilter,
        minSimilarity: Double,
    ): List<VectorHit> = synchronized(lock) {
        checkOpen()
        val active = requireProfile()
        // 用 check 而不是 require：维度不符是「这个查询不该在这份索引上执行」的状态错误，
        // 传进来的参数本身没有错，报 IllegalArgumentException 会误导调用方去改参数
        check(embedding.size == active.dimension) {
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
        // 相似度下限直接下推到 SQL，否则先 LIMIT 再过滤，噪声会把名额吃光，真结果反而被截掉
        if (minSimilarity > 0.0) {
            // 显式 CAST：绑定的是 TEXT，如果表达式带着「无亲和性」，比较会退化成存储类比较，过滤会静默失效
            conditions.append(" AND s.distance <= CAST(? AS REAL)")
            args += (1.0 - minSimilarity).coerceIn(0.0, 2.0).toString()
        }

        val sql = """
            SELECT cv.conversation_id, cv.node_id, cv.message_id, cv.chunk_index,
                   cv.start_offset, cv.end_offset, cv.content_hash, s.distance
            FROM $TABLE_CHUNKS AS cv
            JOIN vector_full_scan('$TABLE_CHUNKS', '$COLUMN_VECTOR', ${hexLiteral(quantized)}) AS s
              ON cv.rowid = s.rowid
            WHERE $conditions
            ORDER BY s.distance, cv.message_id, cv.chunk_index
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
        private const val MAX_LIMIT = 500

        /** 恢复包落盘后留下的标记，下次打开缓存时用它触发一次核对 */
        const val IMPORT_MARKER = "chat_vectors.imported"

        /** 核对没通过、缓存被丢掉时留下，启动维护据此补一次重建 */
        const val NEEDS_REBUILD_MARKER = "chat_vectors.needs_rebuild"

        /**
         * 打开（必要时创建）向量缓存库。
         *
         * 缓存库丢失或损坏时直接删掉重建：它只是索引，主库才是唯一事实来源。
         */
        fun open(context: Context, file: File = SQLiteConfiguration.vectorDatabaseFile(context)): SqliteVectorStore {
            file.parentFile?.mkdirs()
            discardIfForeign(file)
            return SqliteVectorStore(openDatabase(context, file), file)
        }

        /**
         * 缓存是从别处搬来的（恢复备份），和本机记录的消息可能已经对不上。
         *
         * 恢复包里的消息、向量、嵌入模型是一起搬过来的，正常情况下三者一致；
         * 但只要有任何偏差（用户在别的设备上换过模型、备份包被手工改过），
         * 就会变成"维度一样、搜索结果莫名其妙"的沉默错误，这种错误最贵：
         * 用户搜不到东西，却在为每一轮对话继续付索引费。
         *
         * 所以这里做一次低成本核对：本机有没有这些会话、块数对不对得上。
         * 对不上就整份丢掉重建，宁可花一次钱，也不要长期处于"看起来正常其实坏了"的状态。
         */
        private fun discardIfForeign(file: File) {
            val marker = File(file.parentFile, IMPORT_MARKER)
            if (!marker.isFile) return
            marker.delete()
            val reason = runCatching { verifyForeignCache(file) }.getOrElse { it.message ?: "核对失败" }
            if (reason != null) {
                Log.w(TAG, "恢复来的向量缓存不可信，丢弃重建：$reason")
                file.delete()
                for (suffix in listOf("-wal", "-shm", "-journal")) File(file.path + suffix).delete()
                // 丢掉缓存不等于完事：不重建的话语义检索会一直空转，而界面上看不出异常
                runCatching {
                    File(file.parentFile, NEEDS_REBUILD_MARKER).writeText("1")
                }
            } else {
                Log.i(TAG, "恢复来的向量缓存核对通过，直接复用")
            }
        }

        /** 返回 null 表示可信，否则返回丢弃原因 */
        private fun verifyForeignCache(file: File): String? {
            if (!file.isFile || file.length() == 0L) return "文件为空"
            var database: SQLiteDatabase? = null
            return try {
                // 不挂向量扩展：这里只读普通表，挂了反而可能因为原生库不可用而误判
                database = SQLiteDatabase.openDatabase(
                    SQLiteDatabaseConfiguration(file.path, SQLiteDatabase.OPEN_READONLY),
                    null,
                    null
                )
                val chunkCount = database.rawQuery("SELECT COUNT(*) FROM $TABLE_CHUNKS", null).use {
                    if (it.moveToFirst()) it.getLong(0) else -1L
                }
                if (chunkCount < 0L) return "读不到块数"
                null
            } catch (error: Throwable) {
                error.message ?: "缓存库打不开"
            } finally {
                runCatching { database?.close() }
            }
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
