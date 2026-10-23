package me.rerere.rikkahub.data.index

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 向量索引的设备级验证：扩展能不能加载、过滤是否先于截断、profile 隔离、缓存重建。
 *
 * 这些行为依赖原生库和真实 SQLite，只能在设备/模拟器上验，JVM 单测覆盖不了。
 */
@RunWith(AndroidJUnit4::class)
class SqliteVectorStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var databaseFile: File
    private lateinit var store: SqliteVectorStore

    private val dimension = 64

    private fun profile(id: String = "test-profile", dim: Int = dimension) = VectorProfile(
        provider = "test-provider",
        model = id,
        dimension = dim
    )

    private fun unitVector(random: Random, dim: Int = dimension): FloatArray {
        val raw = FloatArray(dim) { random.nextFloat() * 2f - 1f }
        var sum = 0.0
        for (value in raw) sum += value.toDouble() * value
        val norm = sqrt(sum).toFloat()
        return FloatArray(dim) { (raw[it] / norm).toFloat() }
    }

    private fun record(
        messageId: String,
        embedding: FloatArray,
        conversationId: String = "conv-1",
        chunkIndex: Int = 0,
        assistantId: String? = "assistant-1",
        role: String = "assistant"
    ) = VectorChunkRecord(
        conversationId = conversationId,
        assistantId = assistantId,
        role = role,
        nodeId = "node-$messageId",
        messageId = messageId,
        chunkIndex = chunkIndex,
        startOffset = 0,
        endOffset = 100,
        contentHash = TextChunker.hashOfText(messageId),
        embedding = embedding
    )

    @Before
    fun setUp() {
        databaseFile = File(context.cacheDir, "vector-test-${System.nanoTime()}/chat_vectors.db")
        store = SqliteVectorStore.open(context, databaseFile)
        store.ensureProfile(profile())
    }

    @After
    fun tearDown() {
        runCatching { store.close() }
        databaseFile.parentFile?.deleteRecursively()
    }

    @Test
    fun extensionLoadsAndAnswersQueries() {
        store.upsert(listOf(record("m1", unitVector(Random(1)))))
        assertEquals(1, store.stats().chunkCount)
        val hits = store.search(unitVector(Random(1)), limit = 5)
        assertEquals(1, hits.size)
        assertEquals("m1", hits.first().messageId)
    }

    @Test
    fun roundTripFindsTheSameVectorFirst() {
        val random = Random(7)
        val vectors = (1..50).map { unitVector(random) }
        store.upsert(vectors.mapIndexed { index, vector -> record("m$index", vector) })

        val target = vectors[17]
        val hits = store.search(target, limit = 3)
        assertEquals("m17", hits.first().messageId)
        assertTrue("自匹配距离应接近 0，实际 ${hits.first().distance}", hits.first().distance < 0.2)
    }

    @Test
    fun filtersApplyBeforeTruncation() {
        val random = Random(11)
        val records = ArrayList<VectorChunkRecord>()
        repeat(200) { index ->
            records += record("m$index", unitVector(random), conversationId = "conv-a")
        }
        val query = unitVector(random)
        // 与查询几乎同向的三条只放在 conv-b：它们必然是全局最近的三条
        val near = FloatArray(dimension) { query[it] }
        repeat(3) { index ->
            records += record("near-$index", near, conversationId = "conv-b")
        }
        store.upsert(records)

        val global = store.search(query, limit = 5)
        assertEquals(3, global.count { it.conversationId == "conv-b" })

        // 排除 conv-b 之后仍要凑满 5 条：说明排除发生在截断之前，
        // 而不是先取全局前 5 再过滤（那样只会剩 2 条）
        val excluded = store.search(query, limit = 5, filter = VectorFilter(excludeConversationId = "conv-b"))
        assertEquals(5, excluded.size)
        assertTrue(excluded.none { it.conversationId == "conv-b" })

        // 只搜 conv-b：过滤后仍返回它的三条
        val scoped = store.search(query, limit = 5, filter = VectorFilter(conversationId = "conv-b"))
        assertEquals(3, scoped.size)
        assertTrue(scoped.all { it.conversationId == "conv-b" })

        // 待处理索引任务的会话从召回里排除
        val pending = store.search(
            query,
            limit = 5,
            filter = VectorFilter(excludeConversationIds = setOf("conv-b"))
        )
        assertEquals(5, pending.size)
        assertTrue(pending.none { it.conversationId == "conv-b" })
    }

    @Test
    fun assistantAndRoleFiltersWork() {
        val random = Random(13)
        store.upsert(
            listOf(
                record("u1", unitVector(random), assistantId = "a1", role = "user"),
                record("u2", unitVector(random), assistantId = "a2", role = "user"),
                record("u3", unitVector(random), assistantId = "a1", role = "assistant"),
            )
        )
        val query = unitVector(random)
        val byAssistant = store.search(query, 10, VectorFilter(assistantIds = setOf("a1")))
        assertEquals(setOf("u1", "u3"), byAssistant.map { it.messageId }.toSet())

        val byRole = store.search(query, 10, VectorFilter(role = "user"))
        assertEquals(setOf("u1", "u2"), byRole.map { it.messageId }.toSet())

        val emptyAssistant = store.search(query, 10, VectorFilter(assistantIds = emptySet()))
        assertTrue(emptyAssistant.isEmpty())
    }

    @Test
    fun profileSwitchClearsOldVectors() {
        val random = Random(17)
        store.upsert(listOf(record("m1", unitVector(random))))
        assertEquals(1, store.stats().chunkCount)

        store.ensureProfile(profile(id = "another-model"))
        assertEquals(0, store.stats().chunkCount)
        assertNotEquals(null, store.activeProfileId())
    }

    @Test
    fun dimensionMismatchIsRejected() {
        val wrong = unitVector(Random(19), dim = 32)
        assertThrows(IllegalArgumentException::class.java) {
            store.upsert(listOf(record("bad", wrong)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.search(wrong, limit = 5)
        }
    }

    @Test
    fun conversationAssistantIdReflectsStoredRows() {
        store.ensureProfile(profile())
        assertNull("没索引过的会话应该是 null", store.conversationAssistantId("conv-1"))

        store.upsert(
            listOf(
                record("m1", unitVector(Random(1), dimension), assistantId = "assistant-7"),
                record("m2", unitVector(Random(2), dimension), assistantId = "assistant-7"),
            )
        )

        // 会话换助手时要靠它判断旧向量得整体作废
        assertEquals("assistant-7", store.conversationAssistantId("conv-1"))
        store.deleteConversation("conv-1")
        assertNull(store.conversationAssistantId("conv-1"))
    }

    @Test
    fun deleteConversationAndMessageRemoveRows() {
        val random = Random(23)
        store.upsert(
            listOf(
                record("m1", unitVector(random), conversationId = "conv-a"),
                record("m2", unitVector(random), conversationId = "conv-a"),
                record("m3", unitVector(random), conversationId = "conv-b"),
            )
        )
        store.deleteMessage("m3")
        assertEquals(2, store.stats().chunkCount)

        store.deleteConversation("conv-a")
        assertEquals(0, store.stats().chunkCount)
    }

    @Test
    fun vectorsAreStoredOncePerDimension() {
        // 固定行数、只改维度，用体积差值判断每维占几字节：
        // 差值是 192 字节/行说明每维 1 字节（int8 单份），768 则说明偷偷存了 float32
        val rows = 300
        val smallDimension = 64
        val largeDimension = 256
        val random = Random(29)

        val smallFile = File(databaseFile.parentFile, "small.db")
        val largeFile = File(databaseFile.parentFile, "large.db")
        val smallStore = SqliteVectorStore.open(context, smallFile)
        val largeStore = SqliteVectorStore.open(context, largeFile)
        try {
            smallStore.ensureProfile(profile(id = "small", dim = smallDimension))
            largeStore.ensureProfile(profile(id = "large", dim = largeDimension))
            smallStore.upsert((1..rows).map { record("m$it", unitVector(random, smallDimension)) })
            largeStore.upsert((1..rows).map { record("m$it", unitVector(random, largeDimension)) })
            assertEquals(rows, smallStore.stats().chunkCount)
            assertEquals(rows, largeStore.stats().chunkCount)

            val delta = largeStore.stats().fileSizeBytes - smallStore.stats().fileSizeBytes
            val expectedInt8 = rows.toLong() * (largeDimension - smallDimension)
            val expectedFloat32 = expectedInt8 * 4
            Log.i("SqliteVectorStoreTest", "delta=$delta expectedInt8=$expectedInt8 expectedF32=$expectedFloat32")
            assertTrue(
                "体积差值 $delta 应接近 int8 单份 $expectedInt8，而不是 float32 的 $expectedFloat32",
                delta < expectedInt8 * 2
            )
        } finally {
            runCatching { smallStore.close() }
            runCatching { largeStore.close() }
        }
    }

    @Test
    fun cacheDatabaseCanBeRebuiltAfterLoss() {
        val random = Random(31)
        val first = unitVector(random)
        store.upsert(listOf(record("m1", first)))
        assertEquals(1, store.stats().chunkCount)

        store.close()
        assertTrue("缓存库应当被删除", databaseFile.delete() || !databaseFile.exists())

        store = SqliteVectorStore.open(context, databaseFile)
        store.ensureProfile(profile())
        assertEquals(0, store.stats().chunkCount)
        store.upsert(listOf(record("m1", first)))
        assertEquals(1, store.search(first, 5).size)
    }

    @Test
    fun recoveredProfileKeepsDimension() {
        val random = Random(37)
        val vector = unitVector(random)
        store.upsert(listOf(record("m1", vector)))
        val profileId = store.activeProfileId()
        store.close()

        store = SqliteVectorStore.open(context, databaseFile)
        assertEquals(profileId, store.activeProfileId())
        // 复用同一个 profile 不应该清空数据
        store.ensureProfile(profile())
        assertEquals(1, store.stats().chunkCount)
        assertNotNull(store.search(vector, 3).firstOrNull())
        Log.i("SqliteVectorStoreTest", "recovered profileId=$profileId")
    }
}
