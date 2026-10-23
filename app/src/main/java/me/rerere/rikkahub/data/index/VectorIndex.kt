package me.rerere.rikkahub.data.index

/**
 * 向量索引的 profile。
 *
 * 任何一项变化都意味着旧向量不能用：换 provider、换模型、换维度、换量化方式、
 * 改文本提取规则或分块规则，都要当成新 profile 重建。
 *
 * [id] 由这些字段算出来，只要有一项不同 id 就不同；API key 不参与、也不落库。
 */
data class VectorProfile(
    val provider: String,
    val model: String,
    val dimension: Int,
    val dtype: String = "int8",
    val extractVersion: Int = IndexableText.EXTRACT_VERSION,
    val chunkVersion: Int = TextChunker.CHUNK_VERSION,
    /**
     * 查询侧指令前缀，空串表示不加。
     *
     * 它只影响查询怎么编码，不影响已经存下来的文档向量，所以**不参与 [id]**：
     * 改它不该清库重建，否则每次调整措辞都要重算一遍全部向量。
     */
    val instruction: String = "",
    /** int8 缩放系数，按维度算出来，全库统一 */
    val quantScale: Float = VectorQuantizer.scaleFor(dimension)
) {
    val id: String = buildString {
        append(provider).append('|')
        append(model).append('|')
        append(dimension).append('|')
        append(dtype).append('|')
        append(extractVersion).append('|')
        append(chunkVersion).append('|')
        append(java.lang.Float.floatToIntBits(quantScale).toUInt().toString(16))
    }

    init {
        require(dimension > 0) { "dimension must be positive, got $dimension" }
        require(quantScale > 0f) { "quantScale must be positive, got $quantScale" }
    }
}

/** 一条待写入的块：元数据 + 已归一化的向量 */
data class VectorChunkRecord(
    val conversationId: String,
    val assistantId: String?,
    val role: String,
    val nodeId: String,
    val messageId: String,
    val chunkIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val contentHash: String,
    val embedding: FloatArray
)

/** 召回过滤条件，全部在候选截断之前生效 */
data class VectorFilter(
    /** 只搜这个会话 */
    val conversationId: String? = null,
    /** 排除当前会话 */
    val excludeConversationId: String? = null,
    /** 排除这些会话（例如还有待处理索引任务的会话） */
    val excludeConversationIds: Set<String> = emptySet(),
    /** 只搜这些助手，null 表示不限 */
    val assistantIds: Set<String>? = null,
    /** 只搜这个角色 */
    val role: String? = null
)

/** 一条命中，仍然指向原始消息与原文偏移，不携带向量本身 */
data class VectorHit(
    val conversationId: String,
    val nodeId: String,
    val messageId: String,
    val chunkIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val contentHash: String,
    val distance: Double
)

data class VectorIndexStats(
    val profileId: String?,
    val chunkCount: Int,
    val conversationCount: Int,
    val fileSizeBytes: Long
)

/**
 * 本地向量索引。
 *
 * 所有方法都是阻塞的，调用方自行切到 IO 线程；实现内部保证串行访问。
 */
interface VectorIndex {
    /** 写入或切换 profile。profile 变化时清空旧数据，由调用方负责后台重建 */
    fun ensureProfile(profile: VectorProfile)

    fun activeProfileId(): String?

    /** 批量写入，同一条消息的同一块重复写入时覆盖 */
    fun upsert(chunks: List<VectorChunkRecord>)

    /** 会话内每块的签名（"messageId#chunkIndex" -> contentHash），用于跳过没变的重新计算 */
    fun chunkSignatures(conversationId: String): Map<String, String>

    /** 该会话已有向量归属的助手 id；会话没索引过时返回 null */
    fun conversationAssistantId(conversationId: String): String?

    fun deleteConversation(conversationId: String)

    fun deleteMessage(messageId: String)

    /** 删除某条消息里指定的块（分块结果变短时用） */
    fun deleteChunks(messageId: String, chunkIndexes: Collection<Int>)

    fun search(embedding: FloatArray, limit: Int, filter: VectorFilter = VectorFilter()): List<VectorHit>

    fun clear()

    fun stats(): VectorIndexStats

    fun close()
}
