package me.rerere.rikkahub.data.index

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
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
        // conv-b 里放 3 条离查询最近的向量，但整体上它们排不进全局前 10
        val vectors = HashMap<String, FloatArray>()
        val records = ArrayList<VectorChunkRecord>()
        repeat(200) { index ->
            val vector = unitVector(random)
            vectors["m$index"] = vector
            records += record("m$index", vector, conversationId = "conv-a")
        }
        val query = unitVector(random)
        // 与查询几乎同向的三条，只存在于 conv-b
        val near = FloatArray(dimension) { query[it] * 0.999f }
        repeat(3) { index ->
            records += record("near-$index", near, conversationId = "conv-b")
        }
        store.upsert(records)

        val global = store.search(query, limit = 5)
        assertTrue("全局前 5 不应该有 conv-b", global.none { it.conversationId == "conv-b" })

        val scoped = store.search(query, limit = 5, filter = VectorFilter(conversationId = "conv-b"))
        assertEquals(3, scoped.size)
        assertTrue(scoped.all { it.conversationId == "conv-b" })

        val excluded = store.search(
            query,
            limit = 200,
            filter = VectorFilter(excludeConversationId = "conv-b")
        )
        assertTrue(excluded.none { it.conversationId == "conv-b" })
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
    fun indexesAreStoredOncePerDimension() {
        val random = Random(29)
        store.upsert((1..500).map { record("m$it", unitVector(random)) })
        val stats = store.stats()
        assertEquals(500, stats.chunkCount)
        // int8 单份：每维 1 字节，加行开销与索引；放宽到 3 倍以内即说明没有偷偷存 float32
        val int8Bytes = 500L * dimension
        assertTrue(
            "库体积 ${stats.fileSizeBytes} 应接近 int8 单份 ${int8Bytes * 2}",
            stats.fileSizeBytes < int8Bytes * 3
        )
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
