package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_34_35"

/**
 * message_node 的索引从 (conversation_id) 换成 (conversation_id, node_index)。
 *
 * 加载会话时按 node_index 排序分页读取，只有 conversation_id 索引时每一页都要把整个会话的行取出来排序，
 * 耗时随消息数平方增长，几千条消息的会话要好几秒才能打开。
 * 复合索引以 conversation_id 开头，外键级联删除和按会话的查询照样能用，旧索引不再需要。
 *
 * fork 说明：上游在它的 32→33 做这件事，fork 的 32/33 被用量流水表和会话索引任务表占掉，
 * 于是这里的 33→34 只补了 workspace 的 SSH 两列，索引优化顺延到 34→35。
 * 建索引和删索引都带 IF (NOT) EXISTS，上游 3.1.x 升上来的库走到这里时索引已经是新的，不会报错。
 */
val Migration_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 34 to 35 (message_node index)")
        DatabaseMigrationTracker.onMigrationStart(34, 35)
        try {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_message_node_conversation_id_node_index` " +
                    "ON `message_node` (`conversation_id`, `node_index`)"
            )
            db.execSQL("DROP INDEX IF EXISTS `index_message_node_conversation_id`")
            Log.i(TAG, "migrate: migrate from 34 to 35 success")
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
