package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 聊天历史语义检索的设置。
 *
 * 默认关闭：开启后聊天正文会被发送到所选模型的服务商去算向量，这是隐私面上的实质变化，
 * 必须由用户显式打开。
 */
@Serializable
data class EmbeddingIndexSetting(
    val enabled: Boolean = false,
    /** 只用 OpenAI 兼容 provider 里标了 EMBEDDING 的模型 */
    val modelId: Uuid? = null,
    /**
     * 纯语义命中的余弦相似度下限（0-1）。低于它的命中直接被丢掉。
     *
     * 不同模型、不同语言的相似度分布差得很远（同一对句子的分数能差 0.2 以上），
     * 所以不写成写死的常量，交给用户在自己的模型上调。
     */
    val minSimilarity: Double = DEFAULT_MIN_SIMILARITY,
) {
    companion object {
        const val DEFAULT_MIN_SIMILARITY = 0.4

        // 0 等于不设下限（任何 query 都能刨出一池噪声）；0.9 以上几乎没有东西能过，
        // 留出这头尾两个区间只会让用户把功能关掉
        const val MIN_SIMILARITY_LOWER_BOUND = 0.0
        const val MIN_SIMILARITY_UPPER_BOUND = 0.9
        const val MIN_SIMILARITY_STEP = 0.05

        fun coerceMinSimilarity(value: Double): Double =
            value.coerceIn(MIN_SIMILARITY_LOWER_BOUND, MIN_SIMILARITY_UPPER_BOUND)
    }
}
