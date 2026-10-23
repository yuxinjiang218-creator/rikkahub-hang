package me.rerere.rikkahub.data.index

import android.util.Log
import kotlinx.coroutines.delay
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.ProviderManager

/**
 * 调 OpenAI 兼容 `/embeddings` 生成向量。
 *
 * 与厂商无关：不传 `dimensions` 参数（不是所有实现都认），维度从返回里读；
 * 批量失败就二分重试，遇到限流退避。
 */
class EmbeddingClient(
    private val providerManager: ProviderManager
) {
    suspend fun embed(target: EmbeddingTarget, input: List<String>): List<FloatArray> {
        require(input.isNotEmpty()) { "embedding input cannot be empty" }
        return embedInternal(target, input, depth = 0)
    }

    private suspend fun embedInternal(
        target: EmbeddingTarget,
        input: List<String>,
        depth: Int
    ): List<FloatArray> {
        var attempt = 0
        while (true) {
            try {
                val provider = providerManager.getProviderByType(target.provider)
                val result = provider.generateEmbedding(
                    providerSetting = target.provider,
                    params = EmbeddingGenerationParams(
                        model = target.model,
                        input = input,
                        customHeaders = target.model.customHeaders,
                        customBody = target.model.customBody,
                    )
                )
                require(result.embeddings.size == input.size) {
                    "embedding 数量不匹配: 期望 ${input.size}, 实际 ${result.embeddings.size}"
                }
                return result.embeddings.map { embedding ->
                    FloatArray(embedding.size) { embedding[it] }
                }
            } catch (error: Throwable) {
                // 批量太大被服务端拒绝时二分重试；单条还失败就交给上层处理
                if (input.size > 1 && depth < MAX_SPLIT_DEPTH) {
                    val middle = input.size / 2
                    val head = embedInternal(target, input.subList(0, middle), depth + 1)
                    val tail = embedInternal(target, input.subList(middle, input.size), depth + 1)
                    return head + tail
                }
                if (attempt >= MAX_RETRIES) throw error
                attempt++
                Log.w(TAG, "embedding 失败，第 $attempt 次重试：${error.message}")
                delay(RETRY_BASE_DELAY_MS * attempt)
            }
        }
    }

    companion object {
        private const val TAG = "EmbeddingClient"
        private const val MAX_RETRIES = 3
        private const val MAX_SPLIT_DEPTH = 3
        private const val RETRY_BASE_DELAY_MS = 500L

        /** 批量大小默认值，遇错自动二分 */
        const val DEFAULT_BATCH_SIZE = 32
    }
}
