package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_35_36"

/**
 * 给本地工作区补上共享存储挂载列。
 *
 * fork 说明：上游这轮（本地工作区挂载共享存储）把 workspaces.storage_mounts 放在它的 33→34，
 * 而 fork 的版本号已经走到 35（32 用量流水、33 索引任务、34 SSH 两列、35 消息复合索引），
 * 于是这一列顺延到 35→36。
 *
 * 逐列判断再补，是因为同一条路径上可能来两种库：
 * - 装过 fork 的：库在 35，没有这一列；
 * - 装过上游 3.3.0 的：库停在它自己的 34，那一版已经有这一列，直接 ALTER 会 duplicate column。
 */
val Migration_35_36 = object : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 35 to 36 (workspace storage mounts)")
        DatabaseMigrationTracker.onMigrationStart(35, 36)
        db.beginTransaction()
        try {
            if ("storage_mounts" !in db.columnsIn("workspaces")) {
                db.execSQL(
                    "ALTER TABLE `workspaces` ADD COLUMN `storage_mounts` TEXT NOT NULL DEFAULT '[]'"
                )
            }
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 35 to 36 success")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
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
