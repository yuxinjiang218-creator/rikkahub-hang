package me.rerere.rikkahub.data.index

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.ConversationIndexTaskDAO
import me.rerere.rikkahub.data.db.entity.ConversationIndexTaskEntity
import me.rerere.rikkahub.data.db.entity.IndexTaskOp
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * 把待处理任务变成向量。
 *
 * 关键约束：
 * - 内容哈希没变的块不重新算 embedding（省钱也省电）；
 * - 每次真正写向量之前重新核对任务版本，期间会话被改过就放弃这一轮；
 * - 只有版本没变才删任务，删任务保留 tombstone 语义。
 */
class ConversationIndexer(
    private val conversationRepository: ConversationRepository,
    private val conversationIndexTaskDao: ConversationIndexTaskDAO,
    private val settingsStore: SettingsStore,
    private val embeddingClient: EmbeddingClient,
    private val vectorIndexProvider: VectorIndexProvider,
) {
    /** 每个模型一进程只探一次维度：省一次请求，也避免 profile id 来回抖 */
    private val dimensionCache = ConcurrentHashMap<Uuid, Int>()

    /** FTS 提取规则升级后需要重建，由 worker 在索引前顺手做掉 */
    suspend fun ensureFtsReady() = withContext(Dispatchers.IO) {
        conversationRepository.ensureFtsIndexUpToDate()
    }

    /** 处理一批任务；返回是否还有剩余工作 */
    suspend fun processPending(maxConversations: Int = DEFAULT_MAX_CONVERSATIONS): Boolean =
        withContext(Dispatchers.IO) {
            val settings = settingsStore.awaitLoaded()
            val target = settings.resolveEmbeddingTarget()
            if (target == null) {
                Log.i(TAG, "embedding 未配置，跳过索引")
                return@withContext false
            }
            val store = vectorIndexProvider.get()
            var processed = 0
            var moreWork = false
            while (processed < maxConversations) {
                val task = conversationIndexTaskDao.nextTask() ?: break
                val handled = when (task.op) {
                    IndexTaskOp.DELETE.name -> handleDelete(task, store)
                    else -> handleUpsert(task, settings, target, store)
                }
                if (!handled) {
                    moreWork = true
                    break
                }
                processed++
            }
            if (conversationIndexTaskDao.count() > 0) moreWork = true
            moreWork
        }

    /** 会话被删：删掉向量再销任务 */
    private suspend fun handleDelete(task: ConversationIndexTaskEntity, store: VectorIndex): Boolean {
        store.deleteConversation(task.conversationId)
        conversationIndexTaskDao.deleteIfVersionMatches(task.conversationId, task.version)
        return true
    }

    private suspend fun handleUpsert(
        task: ConversationIndexTaskEntity,
        settings: Settings,
        target: EmbeddingTarget,
        store: VectorIndex
    ): Boolean {
        val conversationId = runCatching { Uuid.parse(task.conversationId) }.getOrNull() ?: run {
            conversationIndexTaskDao.deleteIfVersionMatches(task.conversationId, task.version)
            return true
        }
        val conversation = conversationRepository.getConversationById(conversationId) ?: run {
            // 会话已经没了，按删除处理
            store.deleteConversation(task.conversationId)
            conversationIndexTaskDao.deleteIfVersionMatches(task.conversationId, task.version)
            return true
        }
        indexConversation(conversation, settings, target, store, task.version)
        return true
    }

    private suspend fun indexConversation(
        conversation: Conversation,
        settings: Settings,
        target: EmbeddingTarget,
        store: VectorIndex,
        expectedVersion: Long
    ) {
        val conversationKey = conversation.id.toString()
        val dimension = ensureDimension(settings, target)
        if (dimension == null) {
            Log.w(TAG, "无法确定 embedding 维度，放弃本轮")
            return
        }
        val profile = VectorProfile(
            provider = target.fingerprint,
            model = target.model.modelId,
            dimension = dimension,
            instruction = settings.embeddingIndex.instruction
        )
        // 先定 profile：profile 变了会清库，之后读到的签名才是当前的
        store.ensureProfile(profile)

        // 会话换助手会改变它的可见范围（检索按 assistant 过滤），旧记录必须整体作废
        val existingAssistant = store.conversationAssistantId(conversationKey)
        if (existingAssistant != null && existingAssistant != conversation.assistantId.toString()) {
            Log.i(TAG, "会话 $conversationKey 换了助手，清掉旧向量重来")
            store.deleteConversation(conversationKey)
        }
        val existing = store.chunkSignatures(conversationKey)
        val currentKeys = HashSet<String>()
        val pending = ArrayList<PendingChunk>()

        for (node in conversation.messageNodes) {
            val message = node.selectedMessageOrNull() ?: continue
            val text = IndexableText.extract(message) ?: continue
            val chunks = TextChunker.chunk(text)
            if (chunks.isEmpty()) continue
            for (chunk in chunks) {
                val key = chunkKey(message.id.toString(), chunk.index)
                currentKeys += key
                if (existing[key] == chunk.contentHash) continue
                pending += PendingChunk(
                    key = key,
                    record = VectorChunkRecord(
                        conversationId = conversationKey,
                        assistantId = conversation.assistantId.toString(),
                        role = message.role.name.lowercase(),
                        nodeId = node.id.toString(),
                        messageId = message.id.toString(),
                        chunkIndex = chunk.index,
                        startOffset = chunk.start,
                        endOffset = chunk.end,
                        contentHash = chunk.contentHash,
                        embedding = FloatArray(0)
                    ),
                    text = chunk.textOf(text)
                )
            }
        }

        val embedded = if (pending.isEmpty()) {
            true
        } else {
            embedAndWrite(conversationKey, pending, settings, target, store, expectedVersion)
        }

        if (embedded) {
            deleteStaleChunks(conversationKey, existing.keys, currentKeys, store)
        }
        conversationIndexTaskDao.deleteIfVersionMatches(conversationKey, expectedVersion)
        Log.i(
            TAG,
            "indexed conversation=$conversationKey chunks=${currentKeys.size} " +
                "embedded=${pending.size} profile=${profile.id}"
        )
    }

    private suspend fun embedAndWrite(
        conversationKey: String,
        pending: List<PendingChunk>,
        settings: Settings,
        target: EmbeddingTarget,
        store: VectorIndex,
        expectedVersion: Long
    ): Boolean {
        for (batch in pending.chunked(EmbeddingClient.DEFAULT_BATCH_SIZE)) {
            val vectors = try {
                embeddingClient.embed(target, batch.map { it.text })
            } catch (error: Throwable) {
                Log.w(TAG, "embedding 失败，本轮放弃: ${error.message}")
                return false
            }
            if (!isVersionCurrent(conversationKey, expectedVersion)) {
                Log.i(TAG, "会话 $conversationKey 在处理期间被修改，放弃本轮写入")
                return false
            }
            store.upsert(
                batch.mapIndexed { index, item ->
                    item.record.copy(embedding = vectors[index])
                }
            )
        }
        return true
    }

    private fun deleteStaleChunks(
        conversationKey: String,
        existingKeys: Set<String>,
        currentKeys: Set<String>,
        store: VectorIndex
    ) {
        val stale = existingKeys - currentKeys
        if (stale.isEmpty()) return
        val byMessage = HashMap<String, MutableList<Int>>()
        for (key in stale) {
            val messageId = key.substringBeforeLast('#')
            val chunkIndex = key.substringAfterLast('#').toIntOrNull() ?: continue
            byMessage.getOrPut(messageId) { mutableListOf() } += chunkIndex
        }
        byMessage.forEach { (messageId, indexes) -> store.deleteChunks(messageId, indexes) }
        Log.d(TAG, "conversation=$conversationKey 清理过期块 ${stale.size} 个")
    }

    private suspend fun isVersionCurrent(conversationId: String, expectedVersion: Long): Boolean {
        val latest = conversationIndexTaskDao.find(conversationId) ?: return false
        return latest.version == expectedVersion
    }

    /** 维度从第一次返回里读，之后进程内缓存；探一次只为确定 profile */
    private suspend fun ensureDimension(settings: Settings, target: EmbeddingTarget): Int? {
        dimensionCache[target.model.id]?.let { return it }
        return try {
            val probe = embeddingClient.embed(target, listOf(DIMENSION_PROBE_TEXT))
            probe.firstOrNull()?.size?.takeIf { it > 0 }?.also { dimensionCache[target.model.id] = it }
        } catch (error: Throwable) {
            Log.w(TAG, "探测 embedding 维度失败: ${error.message}")
            null
        }
    }

    /** 清空索引并重建：先清掉旧数据与任务，再给所有会话排队 */
    suspend fun rebuildAll() = withContext(Dispatchers.IO) {
        val store = vectorIndexProvider.get()
        store.clear()
        conversationIndexTaskDao.deleteAll()
        val ids = conversationRepository.getAllConversationIds()
        for (id in ids) {
            conversationIndexTaskDao.upsert(
                ConversationIndexTaskEntity(
                    conversationId = id,
                    op = IndexTaskOp.UPSERT.name,
                    version = 1L,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        Log.i(TAG, "rebuild queued for ${ids.size} conversations")
    }

    /** 清空索引与任务 */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        vectorIndexProvider.get().clear()
        conversationIndexTaskDao.deleteAll()
    }

    suspend fun pendingCount(): Int = withContext(Dispatchers.IO) {
        conversationIndexTaskDao.count()
    }

    private data class PendingChunk(
        val key: String,
        val record: VectorChunkRecord,
        val text: String
    )

    private fun chunkKey(messageId: String, chunkIndex: Int) = "$messageId#$chunkIndex"

    companion object {
        private const val TAG = "ConversationIndexer"
        private const val DEFAULT_MAX_CONVERSATIONS = 8
        private const val DIMENSION_PROBE_TEXT = "dimension probe"
    }
}
