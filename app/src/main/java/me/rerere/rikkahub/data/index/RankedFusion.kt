package me.rerere.rikkahub.data.index

/** 一条消息是由哪一路召回来的 */
enum class RecallSource {
    /** 原始关键词命中的词法召回 */
    RAW,

    /** 分词后的词法召回 */
    SEGMENT,

    /** 逐字 token 的词法召回 */
    TOKEN,

    /** 向量语义召回 */
    VECTOR
}

/** 单路召回里的一条候选，[rank] 从 1 开始 */
data class RankedItem(
    val messageKey: String,
    val conversationId: String,
    val rank: Int,
    val source: RecallSource
)

/** 融合后的一条结果 */
data class FusedItem(
    val messageKey: String,
    val conversationId: String,
    val score: Double,
    val bestRank: Int,
    val matchedBy: Set<RecallSource>
)

/**
 * Reciprocal Rank Fusion：只用名次，不用各路自己的分数，避免把 cosine 这类没校准的分数量纲混进来。
 *
 * 单会话上限随返回条数放宽：条数少时优先保证会话多样性，条数多时才允许同一会话多占位置。
 */
object RankedFusion {
    const val RRF_K = 60

    /** 返回 6 条时单会话最多 2 条，7-15 条时最多 3 条，更多才放到 4 条 */
    fun perConversationCap(limit: Int): Int = when {
        limit <= 6 -> 2
        limit <= 15 -> 3
        else -> 4
    }

    /**
     * @param lists 各路召回的候选，每条列表内部按相关性从高到低排序
     */
    fun fuse(lists: List<List<RankedItem>>, k: Int = RRF_K): List<FusedItem> {
        require(k > 0) { "k must be positive, got $k" }
        if (lists.isEmpty()) return emptyList()

        val scores = HashMap<String, Double>()
        val bestRanks = HashMap<String, Int>()
        val sources = HashMap<String, MutableSet<RecallSource>>()
        val conversations = HashMap<String, String>()

        for (list in lists) {
            val seen = HashSet<String>()
            list.forEachIndexed { index, item ->
                // 同一路里重复出现只算一次，避免重复命中给自己加分
                if (!seen.add(item.messageKey)) return@forEachIndexed
                val rank = index + 1
                scores[item.messageKey] = (scores[item.messageKey] ?: 0.0) + 1.0 / (k + rank)
                bestRanks[item.messageKey] = minOf(bestRanks[item.messageKey] ?: Int.MAX_VALUE, rank)
                sources.getOrPut(item.messageKey) { mutableSetOf() }.add(item.source)
                conversations[item.messageKey] = item.conversationId
            }
        }

        return scores.keys
            .map { key ->
                FusedItem(
                    messageKey = key,
                    conversationId = conversations.getValue(key),
                    score = scores.getValue(key),
                    bestRank = bestRanks.getValue(key),
                    matchedBy = sources.getValue(key).toSet()
                )
            }
            .sortedWith(compareByDescending<FusedItem> { it.score }.thenBy { it.bestRank }.thenBy { it.messageKey })
    }

    /**
     * 按会话多样性挑最终结果：先按融合分从高到低，再限制单会话条数。
     *
     * 会话分组排序只认组内最佳融合排名，不因为某个会话命中条数多就往前挤。
     */
    fun select(fused: List<FusedItem>, limit: Int, cap: Int = perConversationCap(limit)): List<FusedItem> {
        require(limit > 0) { "limit must be positive, got $limit" }
        if (fused.isEmpty()) return emptyList()
        val perConversation = HashMap<String, Int>()
        val picked = ArrayList<FusedItem>(minOf(limit, fused.size))
        for (item in fused) {
            if (picked.size >= limit) break
            val used = perConversation[item.conversationId] ?: 0
            if (used >= cap) continue
            perConversation[item.conversationId] = used + 1
            picked += item
        }
        return picked
    }
}
