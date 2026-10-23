package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 待处理的索引操作 */
enum class IndexTaskOp {
    /** 会话内容变了，需要（增量）重建它的向量 */
    UPSERT,

    /** 会话被删了，需要删掉它的向量；保留 tombstone 直到处理完 */
    DELETE
}

/**
 * 会话级别的向量索引任务。
 *
 * 与会话写入、删除处于同一个事务，所以不会漏；不加外键，会话删掉后任务还要留到处理完为止。
 * [version] 按会话单调递增：处理时先记下读到的版本，真正写向量之前再核对一次，
 * 期间又被改过就放弃这次写入，交给下一轮。
 */
@Entity(
    tableName = "conversation_index_task",
    indices = [Index(value = ["updated_at"])]
)
data class ConversationIndexTaskEntity(
    @PrimaryKey
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("op")
    val op: String,
    @ColumnInfo("version")
    val version: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
