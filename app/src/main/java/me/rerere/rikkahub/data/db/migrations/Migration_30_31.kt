package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val COMPRESSION_STATE_DEFAULT =
    "{\"dialogueSummaryText\":\"\",\"dialogueSummaryUpdatedAt\":\"1970-01-01T00:00:00Z\",\"lastCompressedMessageIndex\":-1,\"updatedAt\":\"1970-01-01T00:00:00Z\"}"

val Migration_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val columns = db.query("PRAGMA table_info(`ConversationEntity`)").use { cursor ->
            buildSet {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }

        if ("compression_state" !in columns) {
            db.execSQL(
                "ALTER TABLE `ConversationEntity` ADD COLUMN `compression_state` TEXT NOT NULL " +
                    "DEFAULT '$COMPRESSION_STATE_DEFAULT'"
            )
        }
        if ("compression_events" !in columns) {
            db.execSQL(
                "ALTER TABLE `ConversationEntity` ADD COLUMN `compression_events` TEXT NOT NULL DEFAULT '[]'"
            )
        }
    }
}
