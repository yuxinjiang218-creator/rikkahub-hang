package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val COMPRESSION_STATE_DEFAULT =
    "{\"dialogueSummaryText\":\"\",\"dialogueSummaryUpdatedAt\":\"1970-01-01T00:00:00Z\",\"lastCompressedMessageIndex\":-1,\"updatedAt\":\"1970-01-01T00:00:00Z\"}"

val Migration_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val conversationColumns = db.columnsIn("ConversationEntity")

        if ("compression_state" !in conversationColumns) {
            db.execSQL(
                "ALTER TABLE `ConversationEntity` ADD COLUMN `compression_state` TEXT NOT NULL " +
                    "DEFAULT '$COMPRESSION_STATE_DEFAULT'"
            )
        }
        if ("compression_events" !in conversationColumns) {
            db.execSQL(
                "ALTER TABLE `ConversationEntity` ADD COLUMN `compression_events` TEXT NOT NULL DEFAULT '[]'"
            )
        }

        if ("shell_compatibility_mode" !in db.columnsIn("workspaces")) {
            db.execSQL(
                "ALTER TABLE `workspaces` ADD COLUMN `shell_compatibility_mode` " +
                    "INTEGER NOT NULL DEFAULT 0"
            )
        }
    }
}

private fun SupportSQLiteDatabase.columnsIn(tableName: String): Set<String> =
    query("PRAGMA table_info(`$tableName`)").use { cursor ->
        buildSet {
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) add(cursor.getString(nameIndex))
        }
    }
