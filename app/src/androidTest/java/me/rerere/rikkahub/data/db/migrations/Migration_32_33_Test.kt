package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 32→33 在 fork 里是补会话索引任务表；message_node 的复合索引顺延到 34→35 才建。
 */
@RunWith(AndroidJUnit4::class)
class Migration_32_33_Test {
    private val testDb = "migration-32-33-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        RequerySQLiteOpenHelperFactory(),
    )

    @Test
    fun createsIndexTaskTable() {
        helper.createDatabase(testDb, 32).close()

        val db = helper.runMigrationsAndValidate(testDb, 33, true, Migration_32_33)

        val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        assertTrue(tables.toString(), "conversation_index_task" in tables)

        db.execSQL(
            "INSERT INTO conversation_index_task (conversation_id, op, version, updated_at) " +
                "VALUES ('conv', 'UPSERT', 3, 1000)"
        )
        val count = db.query("SELECT COUNT(*) FROM conversation_index_task").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
        assertEquals(1, count)
        db.close()
    }
}
