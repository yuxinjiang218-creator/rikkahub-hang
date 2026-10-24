package me.rerere.rikkahub.data.index

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.EmbeddingIndexSetting
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语义检索目标解析：只有「OpenAI 兼容 provider + EMBEDDING 类型模型 + 已开启」才算就绪。
 *
 * 这些判断决定了搜索时是否走向量召回、以及会不会把聊天正文发出去，
 * 所以边界比功能本身更重要。
 */
class EmbeddingTargetTest {
    private val embeddingModel = Model(
        modelId = "Qwen/Qwen3-Embedding-0.6B",
        displayName = "Qwen3 Embedding",
        type = ModelType.EMBEDDING,
    )

    private val chatModel = Model(
        modelId = "gpt-4o-mini",
        displayName = "chat",
        type = ModelType.CHAT,
    )

    private fun settings(
        enabled: Boolean = true,
        modelId: kotlin.uuid.Uuid? = embeddingModel.id,
        providers: List<ProviderSetting> = listOf(
            ProviderSetting.OpenAI(
                name = "SiliconFlow",
                baseUrl = "https://api.siliconflow.cn/v1",
                apiKey = "sk-should-not-leak",
                models = listOf(embeddingModel, chatModel),
            )
        )
    ) = Settings(
        init = true,
        providers = providers,
        embeddingIndex = EmbeddingIndexSetting(enabled = enabled, modelId = modelId),
    )

    @Test
    fun `开关关闭时不解析目标`() {
        val resolved = settings(enabled = false).resolveEmbeddingTarget()
        assertNull(resolved)
        assertFalse(settings(enabled = false).isEmbeddingIndexReady())
    }

    @Test
    fun `没有选模型时解析为空`() {
        assertNull(settings(modelId = null).resolveEmbeddingTarget())
    }

    @Test
    fun `选中的是聊天模型时不算就绪`() {
        // 聊天模型不能调 /embeddings，宁可不索引也不要线上报错
        assertNull(settings(modelId = chatModel.id).resolveEmbeddingTarget())
    }

    @Test
    fun `选中的模型不存在时不算就绪`() {
        assertNull(settings(modelId = kotlin.uuid.Uuid.random()).resolveEmbeddingTarget())
    }

    @Test
    fun `就绪时带回 provider 与模型`() {
        val resolved = settings().resolveEmbeddingTarget()
        assertEquals("Qwen/Qwen3-Embedding-0.6B", resolved?.model?.modelId)
        assertEquals("https://api.siliconflow.cn/v1", resolved?.provider?.baseUrl)
        assertTrue(settings().isEmbeddingIndexReady())
    }

    @Test
    fun `指纹包含端点但不包含密钥`() {
        val resolved = settings().resolveEmbeddingTarget()!!
        assertTrue(resolved.fingerprint.contains("api.siliconflow.cn"))
        // 指纹会进 profile id 并落到磁盘，泄露密钥就是实打实的隐私事故
        assertFalse(resolved.fingerprint.contains("sk-should-not-leak"))
        assertFalse(resolved.fingerprint.contains("apiKey"))
    }

    @Test
    fun `换端点会换指纹`() {
        val a = settings().resolveEmbeddingTarget()!!
        val b = settings(
            providers = listOf(
                ProviderSetting.OpenAI(
                    name = "SiliconFlow",
                    baseUrl = "https://example.com/v1",
                    apiKey = "sk-should-not-leak",
                    models = listOf(embeddingModel),
                )
            )
        ).resolveEmbeddingTarget()!!
        assertTrue(a.fingerprint != b.fingerprint)
    }

    @Test
    fun `模型自定义参数不同就是不同的向量空间`() {
        // 同样的模型 id 配上不同的 dimensions/任务前缀，出来的向量不能混用
        val plain = settings().resolveEmbeddingTarget()!!
        val withBody = settings(
            providers = listOf(
                ProviderSetting.OpenAI(
                    name = "SiliconFlow",
                    baseUrl = "https://api.siliconflow.cn/v1",
                    models = listOf(
                        embeddingModel.copy(
                            customBodies = listOf(
                                CustomBody(key = "dimensions", value = JsonPrimitive(512))
                            )
                        )
                    ),
                )
            )
        ).resolveEmbeddingTarget()!!
        val withHeader = settings(
            providers = listOf(
                ProviderSetting.OpenAI(
                    name = "SiliconFlow",
                    baseUrl = "https://api.siliconflow.cn/v1",
                    models = listOf(
                        embeddingModel.copy(
                            customHeaders = listOf(CustomHeader(name = "X-Task", value = "query"))
                        )
                    ),
                )
            )
        ).resolveEmbeddingTarget()!!
        assertTrue(plain.fingerprint != withBody.fingerprint)
        assertTrue(plain.fingerprint != withHeader.fingerprint)
    }

    @Test
    fun `默认设置是不开启的`() {
        val default = EmbeddingIndexSetting()
        assertFalse(default.enabled)
        assertNull(default.modelId)
    }
}
