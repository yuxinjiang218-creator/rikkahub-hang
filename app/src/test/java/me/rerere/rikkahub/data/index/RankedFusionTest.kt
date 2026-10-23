package me.rerere.rikkahub.data.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RankedFusionTest {

    private fun item(key: String, conversation: String, rank: Int, source: RecallSource) =
        RankedItem(messageKey = key, conversationId = conversation, rank = rank, source = source)

    private fun list(vararg keys: String, conversation: String = "c1", source: RecallSource = RecallSource.RAW) =
        keys.mapIndexed { index, key -> item(key, conversation, index + 1, source) }

    @Test
    fun `items in more lists rank higher`() {
        val fused = RankedFusion.fuse(
            listOf(
                list("a", "b", "c"),
                list("b", "a", source = RecallSource.VECTOR),
            )
        )
        assertEquals(listOf("a", "b", "c"), fused.map { it.messageKey })
        assertTrue(fused.first().matchedBy.containsAll(setOf(RecallSource.RAW, RecallSource.VECTOR)))
    }

    @Test
    fun `single list keeps its order`() {
        val fused = RankedFusion.fuse(listOf(list("x", "y", "z")))
        assertEquals(listOf("x", "y", "z"), fused.map { it.messageKey })
    }

    @Test
    fun `duplicate key inside one list counts once`() {
        val duplicated = listOf(
            item("a", "c1", 1, RecallSource.RAW),
            item("a", "c1", 2, RecallSource.RAW),
            item("b", "c1", 3, RecallSource.RAW),
        )
        val fused = RankedFusion.fuse(listOf(duplicated))
        assertEquals(2, fused.size)
        val a = fused.first { it.messageKey == "a" }
        assertEquals(1.0 / (RankedFusion.RRF_K + 1), a.score, 1e-9)
        assertEquals(1, a.bestRank)
    }

    @Test
    fun `conversation cap keeps results diverse`() {
        val fused = RankedFusion.fuse(
            listOf(
                listOf(
                    item("a1", "c1", 1, RecallSource.RAW),
                    item("a2", "c1", 2, RecallSource.RAW),
                    item("a3", "c1", 3, RecallSource.RAW),
                    item("a4", "c1", 4, RecallSource.RAW),
                    item("b1", "c2", 5, RecallSource.RAW),
                    item("b2", "c2", 6, RecallSource.RAW),
                    item("d1", "c3", 7, RecallSource.RAW),
                    item("e1", "c4", 8, RecallSource.RAW),
                )
            )
        )
        val picked = RankedFusion.select(fused, limit = 6)
        assertEquals(6, picked.size)
        assertEquals(2, picked.count { it.conversationId == "c1" })
        assertEquals(2, picked.count { it.conversationId == "c2" })
        assertEquals(1, picked.count { it.conversationId == "c3" })
        assertEquals(1, picked.count { it.conversationId == "c4" })
        assertTrue("at least three conversations", picked.map { it.conversationId }.toSet().size >= 3)
    }

    @Test
    fun `cap widens as limit grows`() {
        assertEquals(2, RankedFusion.perConversationCap(6))
        assertEquals(3, RankedFusion.perConversationCap(8))
        assertEquals(3, RankedFusion.perConversationCap(15))
        assertEquals(4, RankedFusion.perConversationCap(16))
        assertEquals(4, RankedFusion.perConversationCap(30))
    }

    @Test
    fun `single conversation never starves the list when cap allows`() {
        val fused = RankedFusion.fuse(
            listOf(listOf(
                item("a1", "c1", 1, RecallSource.RAW),
                item("a2", "c1", 2, RecallSource.RAW),
                item("a3", "c1", 3, RecallSource.RAW),
                item("a4", "c1", 4, RecallSource.RAW),
                item("a5", "c1", 5, RecallSource.RAW),
            ))
        )
        val picked = RankedFusion.select(fused, limit = 4)
        assertEquals(2, picked.size)
    }

    @Test
    fun `empty input gives empty output`() {
        assertTrue(RankedFusion.fuse(emptyList()).isEmpty())
        assertTrue(RankedFusion.select(emptyList(), 6).isEmpty())
        assertTrue(RankedFusion.fuse(listOf(emptyList())).isEmpty())
    }

    @Test
    fun `ties are deterministic`() {
        val first = RankedFusion.fuse(listOf(list("b", "a"), list("a", "b")))
        val second = RankedFusion.fuse(listOf(list("a", "b"), list("b", "a")))
        assertEquals(first.map { it.messageKey }.toSet(), second.map { it.messageKey }.toSet())
        // 同分时按最佳名次再按 key 兜底，两次调用结果一致
        assertEquals(first.map { it.messageKey }, RankedFusion.fuse(listOf(list("b", "a"), list("a", "b"))).map { it.messageKey })
    }
}
