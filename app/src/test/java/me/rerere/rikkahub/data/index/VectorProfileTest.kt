package me.rerere.rikkahub.data.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * profile id 决定了「要不要清库重建」。
 *
 * 判错的代价很实在：把不该清的东西算进去，用户改一个字就得重算全部向量；
 * 把该算的漏掉，两种不兼容的向量会混在一张表里互相污染。
 */
class VectorProfileTest {
    private val base = VectorProfile(provider = "siliconflow", model = "Qwen/Qwen3-Embedding-0.6B", dimension = 1024)

    @Test
    fun `查询指令不参与 profile id`() {
        // 指令只影响查询侧编码，已存的文档向量没有变化，不该触发重建
        assertEquals(base.id, base.copy(instruction = "Instruct: 检索历史对话").id)
    }

    @Test
    fun `换模型换维度换端点都要换 profile`() {
        assertNotEquals(base.id, base.copy(model = "other/model").id)
        assertNotEquals(base.id, base.copy(dimension = 512).id)
        assertNotEquals(base.id, base.copy(provider = "api.openai.com").id)
    }

    @Test
    fun `提取规则或分块规则变了要重建`() {
        // 这两项变了，存的文本内容和边界就变了，哈希核对会对不上
        assertNotEquals(base.id, base.copy(extractVersion = base.extractVersion + 1).id)
        assertNotEquals(base.id, base.copy(chunkVersion = base.chunkVersion + 1).id)
    }

    @Test
    fun `量化参数变了要重建`() {
        // 缩放系数不同，同一段文本量化出来的字节不能混用
        assertNotEquals(base.id, base.copy(quantScale = base.quantScale * 2).id)
    }

    @Test
    fun `同样参数算出来的 id 稳定`() {
        val again = VectorProfile(provider = "siliconflow", model = "Qwen/Qwen3-Embedding-0.6B", dimension = 1024)
        assertEquals(base.id, again.id)
    }
}
