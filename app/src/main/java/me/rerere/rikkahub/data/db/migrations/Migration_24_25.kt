package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

val Migration_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(24, 25)
        try {
            db.execSQL(
                """
                ALTER TABLE conversationentity
                ADD COLUMN compression_state TEXT NOT NULL DEFAULT '{"dialogueSummaryText":"","dialogueSummaryUpdatedAt":"1970-01-01T00:00:00Z","lastCompressedMessageIndex":-1,"updatedAt":"1970-01-01T00:00:00Z"}'
                """.trimIndent()
            )
            db.execSQL(
                """
                ALTER TABLE conversationentity
                ADD COLUMN compression_events TEXT NOT NULL DEFAULT '[]'
                """.trimIndent()
            )
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
