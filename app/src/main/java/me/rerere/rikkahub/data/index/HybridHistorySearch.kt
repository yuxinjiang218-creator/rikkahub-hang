package me.rerere.rikkahub.data.index

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.historySearchResultRef

/**
 * 节点当前选中的消息；数据损坏（消息为空或下标越界）时返回 null 而不是抛异常。
 */
internal fun MessageNode.selectedMessageOrNull(): UIMessage? =
    if (messages.isEmpty() || selectIndex !in messages.indices) null else messages[selectIndex]

/** 语义召回在这次搜索里的状态，直接回给模型，让它知道结果是怎么来的 */
enum class SemanticState {
    /** 用户没开语义检索 */
    DISABLED,

    /** 开了但模型/服务商没配好 */
    NOT_CONFIGURED,

    /** 语义召回正常参与融合 */
    OK,

    /** 会话还在建索引，暂时从语义召回里排除 */
    INDEXING,

    /** 查询向量超时，退回到纯词法 */
    TIMEOUT,

    /** 向量接口报错，退回到纯词法 */
    FAILED,

    /** 精确标识符查询已被词法高置信命中，跳过 embedding */
    SKIPPED_EXACT
}

/** 一次历史搜索的结果与召回状态 */
data class HistorySearchOutcome(
    val results: List<MessageSearchResult>,
    val semanticState: SemanticState,
    val hasMore: Boolean = false
)

/**
 * 词法三路 + 向量一路做 RRF 融合。
 *
 * 只用名次不用分数：向量那边的 cosine 没有校准，混进排序只会乱。
 * 同一条消息在多路里出现会累加，[MessageSearchResult.matchedBy] 记录它被哪几路召回。
 */
fun fuseHistoryResults(
    lexicalRoutes: List<Pair<RecallSource, List<MessageSearchResult>>>,
    vectorResults: List<MessageSearchResult>,
    requestedLimit: Int,
    perConversationLimit: Int,
    offset: Int = 0
): HistorySearchOutcome {
    val byKey = LinkedHashMap<String, MessageSearchResult>()

    fun remember(result: MessageSearchResult) {
        byKey.putIfAbsent(historySearchResultRef(result), result)
    }

    val rankedLists = ArrayList<List<RankedItem>>()
    for ((source, results) in lexicalRoutes) {
        results.forEach(::remember)
        rankedLists += results.mapIndexed { index, result ->
            RankedItem(
                messageKey = historySearchResultRef(result),
                conversationId = result.conversationId,
                rank = index + 1,
                source = source
            )
        }
    }
    if (vectorResults.isNotEmpty()) {
        vectorResults.forEach(::remember)
        rankedLists += vectorResults.mapIndexed { index, result ->
            RankedItem(
                messageKey = historySearchResultRef(result),
                conversationId = result.conversationId,
                rank = index + 1,
                source = RecallSource.VECTOR
            )
        }
    }

    val fused = RankedFusion.fuse(rankedLists)
    if (fused.isEmpty()) {
        return HistorySearchOutcome(emptyList(), SemanticState.OK, hasMore = false)
    }
    val window = offset + requestedLimit
    val selected = RankedFusion.select(fused, limit = window, cap = perConversationLimit)
    val page = selected.drop(offset).mapNotNull { item ->
        byKey[item.messageKey]?.copy(matchedBy = item.matchedBy)
    }
    return HistorySearchOutcome(
        results = page,
        semanticState = SemanticState.OK,
        hasMore = fused.size > selected.size
    )
}
