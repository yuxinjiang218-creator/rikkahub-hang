package me.rerere.rikkahub.data.index

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings

/**
 * 语义检索实际要用的模型。
 *
 * 只认 OpenAI 兼容 provider + 标了 [ModelType.EMBEDDING] 的模型：
 * 别的 provider 的模型即使打了 EMBEDDING 标记也不代表能调 `/embeddings`。
 *
 * [fingerprint] 进 profile 用于区分端点，不含 API key。
 */
data class EmbeddingTarget(
    val providerName: String,
    val provider: ProviderSetting.OpenAI,
    val model: Model
) {
    /**
     * 进 profile 的端点指纹。
     *
     * 除了端点，还要带上模型的自定义 headers/body：同样的模型 id 配上不同的
     * `dimensions`、任务前缀之类的参数，出来的向量根本不是一个空间。
     */
    val fingerprint: String = buildString {
        append(providerName).append(':').append(provider.baseUrl)
        if (model.customHeaders.isNotEmpty()) {
            append("|h:").append(model.customHeaders.hashCode().toUInt().toString(16))
        }
        if (model.customBodies.isNotEmpty()) {
            append("|b:").append(model.customBodies.hashCode().toUInt().toString(16))
        }
    }
}

/** 从设置里解析出可用的 embedding 目标，未启用或模型不合法时返回 null */
fun Settings.resolveEmbeddingTarget(): EmbeddingTarget? {
    val index = embeddingIndex
    if (!index.enabled) return null
    val modelId = index.modelId ?: return null
    val provider = providers.firstOrNull { provider ->
        provider.models.any { it.id == modelId }
    } as? ProviderSetting.OpenAI ?: return null
    val model = provider.models.firstOrNull { it.id == modelId } ?: return null
    if (model.type != ModelType.EMBEDDING) return null
    return EmbeddingTarget(providerName = "openai", provider = provider, model = model)
}

/** 该设置项是否已经配置齐（用于设置页提示与搜索侧状态） */
fun Settings.isEmbeddingIndexReady(): Boolean = resolveEmbeddingTarget() != null
