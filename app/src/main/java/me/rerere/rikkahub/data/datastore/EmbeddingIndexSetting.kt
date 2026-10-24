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
)
