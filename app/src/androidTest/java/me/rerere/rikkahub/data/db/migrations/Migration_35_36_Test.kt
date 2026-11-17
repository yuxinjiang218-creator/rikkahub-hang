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

@RunWith(AndroidJUnit4::class)
class Migration_35_36_Test {
    private val testDb = "migration-35-36-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        RequerySQLiteOpenHelperFactory(),
    )

    @Test
    fun addsStorageMountsColumnWithDefault() {
        helper.createDatabase(testDb, 35).apply {
            execSQL(
                "INSERT INTO workspaces (id, name, root, shell_status, created_at, updated_at, last_access_at) " +
                    "VALUES ('ws', 'Local', '/rootfs', 'RUNNING', 1000, 1000, 1000)"
            )
            close()
        }

        // 同时校验迁移后的表结构与 36 的 schema 一致
        val db = helper.runMigrationsAndValidate(testDb, 36, true, Migration_35_36)

        // 既有行要拿到默认值，不能是 null：列声明为 NOT NULL DEFAULT '[]'
        val mounts = db.query("SELECT storage_mounts FROM workspaces WHERE id = 'ws'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }
        assertEquals("[]", mounts)

        // 新列可以正常写入
        db.execSQL(
            "UPDATE workspaces SET storage_mounts = ? WHERE id = 'ws'",
            arrayOf<Any?>("""[{"hostPath":"/storage/emulated/0/Documents","rootfsPath":"/rootfs/storage"}]"""),
        )
        val updated = db.query("SELECT storage_mounts FROM workspaces WHERE id = 'ws'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }
        assertTrue(updated, updated.contains("/storage/emulated/0/Documents"))
        db.close()
    }

    @Test
    fun toleratesColumnAlreadyPresent() {
        // 装过上游 3.3.0 的库：它的 34 里已经有 storage_mounts，升到 fork 的 36 时不能再 ALTER 一次
        helper.createDatabase(testDb, 35).apply {
            execSQL("ALTER TABLE workspaces ADD COLUMN storage_mounts TEXT NOT NULL DEFAULT '[]'")
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 36, true, Migration_35_36)
        val columns = db.query("PRAGMA table_info(workspaces)").use { cursor ->
            buildList {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }
        assertEquals(1, columns.count { it == "storage_mounts" })
        db.close()
    }
}
