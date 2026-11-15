package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_32_33"

/**
 * 新增会话索引任务表。
 *
 * fork 说明：上游的 32→33 只是把 message_node 的索引换成复合索引，没有新表；
 * fork 的 33 多了这张排队表，装过 fork 3.1.x 的库停在 fork 的 32，缺这张表。
 * 建表带 IF NOT EXISTS，表已经在了也不会报错。
 *
 * message_node 的复合索引统一放到 34→35 做，两条血统都会经过那里。
 */
val Migration_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 32 to 33 (conversation index task)")
        DatabaseMigrationTracker.onMigrationStart(32, 33)
        db.beginTransaction()
        try {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `conversation_index_task` (" +
                    "`conversation_id` TEXT NOT NULL, `op` TEXT NOT NULL, " +
                    "`version` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`conversation_id`))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_conversation_index_task_updated_at` " +
                    "ON `conversation_index_task` (`updated_at`)"
            )
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 32 to 33 success")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
