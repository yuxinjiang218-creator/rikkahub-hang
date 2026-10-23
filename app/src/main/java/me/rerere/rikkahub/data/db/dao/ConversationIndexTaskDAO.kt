package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import me.rerere.rikkahub.data.db.entity.ConversationIndexTaskEntity

@Dao
interface ConversationIndexTaskDAO {
    /** 最老的一条待处理任务，先来先做，避免新会话饿死旧会话 */
    @Query("SELECT * FROM conversation_index_task ORDER BY updated_at ASC LIMIT 1")
    suspend fun nextTask(): ConversationIndexTaskEntity?

    @Query("SELECT * FROM conversation_index_task WHERE conversation_id = :conversationId")
    suspend fun find(conversationId: String): ConversationIndexTaskEntity?

    @Query("SELECT conversation_id FROM conversation_index_task")
    suspend fun pendingConversationIds(): List<String>

    @Query("SELECT COUNT(*) FROM conversation_index_task")
    suspend fun count(): Int

    @Query("SELECT version FROM conversation_index_task WHERE conversation_id = :conversationId")
    suspend fun versionOf(conversationId: String): Long?

    @Upsert
    suspend fun upsert(task: ConversationIndexTaskEntity)

    /** 只有版本没变时才删任务，避免把期间产生的新任务一起吞掉 */
    @Query("DELETE FROM conversation_index_task WHERE conversation_id = :conversationId AND version = :version")
    suspend fun deleteIfVersionMatches(conversationId: String, version: Long)

    @Query("DELETE FROM conversation_index_task WHERE conversation_id = :conversationId")
    suspend fun delete(conversationId: String)

    @Query("DELETE FROM conversation_index_task")
    suspend fun deleteAll()
}
