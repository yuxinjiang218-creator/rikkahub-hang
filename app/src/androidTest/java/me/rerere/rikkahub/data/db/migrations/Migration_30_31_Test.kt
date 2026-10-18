package me.rerere.rikkahub.data.db.migrations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_30_31_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun addsMissingCompressionColumnsAndPreservesConversation() {
        helper.createDatabase(TEST_DB, 30).use { it.insertConversation() }

        helper.runMigrationsAndValidate(TEST_DB, 31, true, Migration_30_31).use { db ->
            db.query("SELECT title, compression_state, compression_events FROM ConversationEntity").use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()
                assertEquals("kept", cursor.getString(0))
                assertEquals(COMPRESSION_STATE_DEFAULT, cursor.getString(1))
                assertEquals("[]", cursor.getString(2))
            }
        }
    }

    @Test
    fun preservesExistingCompressionColumnsAndValues() {
        helper.createDatabase(TEST_DB, 30).use { db ->
            db.execSQL(
                "ALTER TABLE `ConversationEntity` ADD COLUMN `compression_state` TEXT NOT NULL " +
                    "DEFAULT '$COMPRESSION_STATE_DEFAULT'"
            )
            db.execSQL(
                "ALTER TABLE `ConversationEntity` ADD COLUMN `compression_events` TEXT NOT NULL DEFAULT '[]'"
            )
            db.insertConversation()
            db.execSQL(
                "UPDATE `ConversationEntity` SET `compression_state` = 'legacy-state', " +
                    "`compression_events` = '[{\"type\":\"legacy\"}]' WHERE `id` = 'conversation'"
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 31, true, Migration_30_31).use { db ->
            db.query("SELECT compression_state, compression_events FROM ConversationEntity").use { cursor ->
                cursor.moveToFirst()
                assertEquals("legacy-state", cursor.getString(0))
                assertEquals("[{\"type\":\"legacy\"}]", cursor.getString(1))
            }
        }
    }

    @Test
    fun addsMissingWorkspaceShellCompatibilityColumnAndPreservesWorkspace() {
        helper.createDatabase(TEST_DB, 30).use { db ->
            db.execSQL("ALTER TABLE `workspaces` DROP COLUMN `shell_compatibility_mode`")
            val values = ContentValues().apply {
                put("id", "workspace")
                put("name", "kept")
                put("root", "/workspace")
                put("shell_status", "DISABLED")
                put("created_at", 1L)
                put("updated_at", 2L)
                put("tool_approvals", "{}")
            }
            db.insert("workspaces", SQLiteDatabase.CONFLICT_ABORT, values)
        }

        helper.runMigrationsAndValidate(TEST_DB, 31, true, Migration_30_31).use { db ->
            db.query("SELECT name, shell_compatibility_mode FROM workspaces").use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()
                assertEquals("kept", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
            }
        }
    }

    private fun SupportSQLiteDatabase.insertConversation() {
        val values = ContentValues().apply {
            put("id", "conversation")
            put("assistant_id", "assistant")
            put("title", "kept")
            put("nodes", "[]")
            put("create_at", 1L)
            put("update_at", 2L)
            put("suggestions", "[]")
            put("is_pinned", 0)
        }
        insert("ConversationEntity", SQLiteDatabase.CONFLICT_ABORT, values)
    }

    private companion object {
        const val TEST_DB = "migration-30-31-test"
        const val COMPRESSION_STATE_DEFAULT =
            "{\"dialogueSummaryText\":\"\",\"dialogueSummaryUpdatedAt\":\"1970-01-01T00:00:00Z\",\"lastCompressedMessageIndex\":-1,\"updatedAt\":\"1970-01-01T00:00:00Z\"}"
    }
}
