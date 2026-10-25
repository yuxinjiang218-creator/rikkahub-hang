package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_33_34"

/**
 * 补齐 workspace 的 SSH 相关列。
 *
 * fork 说明：fork 的 32 拿去放用量流水表、33 放会话索引任务表，上游夹在中间的 32 新增了
 * workspaces.type / workspaces.ssh_config，于是顺延到 33→34。
 *
 * 这里逐列判断再补，是因为同一条路径上可能来两种库：
 * - 装过 fork 3.1.1/3.1.2 的：库停在 fork 的 33，两列都没有；
 * - 装过上游 3.1.1 的：库停在上游的 32，两列已经在（但版本号比 33 小），
 *   走到这里时直接 ALTER 会 duplicate column 报错。
 */
val Migration_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 33 to 34 (workspace ssh columns)")
        DatabaseMigrationTracker.onMigrationStart(33, 34)
        db.beginTransaction()
        try {
            val workspaceColumns = db.columnsIn("workspaces")
            if ("type" !in workspaceColumns) {
                db.execSQL(
                    "ALTER TABLE `workspaces` ADD COLUMN `type` TEXT NOT NULL DEFAULT 'LOCAL'"
                )
            }
            if ("ssh_config" !in workspaceColumns) {
                db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `ssh_config` TEXT")
            }
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 33 to 34 success")
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
