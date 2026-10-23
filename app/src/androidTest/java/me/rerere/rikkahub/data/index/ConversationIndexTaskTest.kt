package me.rerere.rikkahub.data.index

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import me.rerere.rikkahub.data.db.entity.ConversationIndexTaskEntity
import me.rerere.rikkahub.data.db.entity.IndexTaskOp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 索引任务表的行为验证：合并、版本核对、tombstone。
 *
 * 这些是「不会漏索引、也不会把用户新改的内容用旧向量盖掉」的地基，
 * 逻辑都在 SQL 里，JVM 单测替不了真库。
 */
@RunWith(AndroidJUnit4::class)
class ConversationIndexTaskTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: AppDatabase
    private val dao get() = database.conversationIndexTaskDao()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun task(
        conversationId: String = "conv-1",
        op: IndexTaskOp = IndexTaskOp.UPSERT,
        version: Long = 1L,
        updatedAt: Long = 1_000L,
    ) = ConversationIndexTaskEntity(
        conversationId = conversationId,
        op = op.name,
        version = version,
        updatedAt = updatedAt,
    )

    @Test
    fun `同一会话的任务会合并成一条`() = runBlocking {
        // 主键是会话 id，同一个会话连续编辑只会进来一次，不会堆成一串
        dao.upsert(task(version = 1L, updatedAt = 1_000L))
        dao.upsert(task(version = 2L, updatedAt = 2_000L))
        dao.upsert(task(version = 3L, updatedAt = 3_000L))

        assertEquals(1, dao.count())
        assertEquals(3L, dao.versionOf("conv-1"))
        assertEquals(3_000L, dao.nextTask()?.updatedAt)
    }

    @Test
    fun `先来先做`() = runBlocking {
        dao.upsert(task(conversationId = "conv-new", updatedAt = 5_000L))
        dao.upsert(task(conversationId = "conv-old", updatedAt = 1_000L))

        // 老会话不能被新会话饿死
        assertEquals("conv-old", dao.nextTask()?.conversationId)
        dao.deleteIfVersionMatches("conv-old", dao.versionOf("conv-old")!!)
        assertEquals("conv-new", dao.nextTask()?.conversationId)
    }

    @Test
    fun `版本变了就不许删任务`() = runBlocking {
        dao.upsert(task(version = 7L, updatedAt = 1_000L))

        // 处理这一轮时读到的是 7；期间用户又改了一次，任务升到了 8
        dao.upsert(task(version = 8L, updatedAt = 2_000L))
        dao.deleteIfVersionMatches("conv-1", 7L)

        // 任务必须还在，否则用户的最后一次修改永远等不到索引
        assertNotNull(dao.find("conv-1"))
        assertEquals(8L, dao.versionOf("conv-1"))
    }

    @Test
    fun `版本没变就清掉任务`() = runBlocking {
        dao.upsert(task(version = 4L, updatedAt = 1_000L))
        dao.deleteIfVersionMatches("conv-1", 4L)
        assertNull(dao.find("conv-1"))
        assertEquals(0, dao.count())
    }

    @Test
    fun `删除任务会覆盖成待删除`() = runBlocking {
        // 先有 upsert，然后会话被删了：同一条记录变成 DELETE，不会被两条任务拉锯
        dao.upsert(task(version = 1L, updatedAt = 1_000L))
        dao.upsert(task(op = IndexTaskOp.DELETE, version = 2L, updatedAt = 2_000L))

        val pending = dao.nextTask()!!
        assertEquals(IndexTaskOp.DELETE.name, pending.op)
        assertEquals(1, dao.count())
    }

    @Test
    fun `待处理会话列表可用于检索侧排除`() = runBlocking {
        dao.upsert(task(conversationId = "conv-a", updatedAt = 1_000L))
        dao.upsert(task(conversationId = "conv-b", updatedAt = 2_000L))
        val pending = dao.pendingConversationIds().toSet()
        assertTrue(pending.containsAll(setOf("conv-a", "conv-b")))
        assertEquals(2, pending.size)
    }

    @Test
    fun `向量缓存放在不进备份的目录`() {
        // 备份只打包 filesDir 下的文件夹和主库快照；向量缓存是可重建的派生数据，
        // 放进 noBackupFilesDir 既不会被备份，也不会被云同步带走
        val vectorFile = SQLiteConfiguration.vectorDatabaseFile(context)
        val noBackupRoot = context.noBackupFilesDir.canonicalFile
        val filesRoot = context.filesDir.canonicalFile
        val databasePath = context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME).canonicalFile

        assertTrue(vectorFile.canonicalPath.startsWith(noBackupRoot.path + File.separator))
        assertFalse(vectorFile.canonicalPath.startsWith(filesRoot.path + File.separator))
        assertFalse(vectorFile.canonicalPath == databasePath.path)
        assertTrue(vectorFile.name.endsWith(".db"))
    }
}
