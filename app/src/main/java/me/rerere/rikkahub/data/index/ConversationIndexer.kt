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
    private val statusTracker: IndexStatusTracker,
) {
    /**
     * 每个“端点+模型配置”一进程只探一次维度。
     *
     * 不能只按模型 id 缓存：同一个模型 id 配上不同的 `dimensions` 参数就能改变维度，
     * 沿用旧维度会让新向量反复写失败。指纹里带了 headers/body，正好能区分。
     */
    private val dimensionCache = ConcurrentHashMap<String, Int>()

    /** FTS 提取规则升级后需要重建，由 worker 在索引前顺手做掉 */
    suspend fun ensureFtsReady() = withContext(Dispatchers.IO) {
        conversationRepository.ensureFtsIndexUpToDate()
    }

    /**
     * 处理一批任务。
     *
     * 失败的会话会在本轮被跳过（不是死等），剩下的任务照常做完，
     * 所以一个探测失败的会话不会把整条队列饿死。
     *
     * [ProcessResult.hasMore] 只表示“还有现在就能干的活”，批次上限截断时仍为 true，
     * 调用方可以接着下一批；[ProcessResult.deferred] 表示本轮有会话失败，
     * 需要退避后重试。
     */
    suspend fun processPending(maxConversations: Int = DEFAULT_MAX_CONVERSATIONS): ProcessResult =
        withContext(Dispatchers.IO) {
            val settings = settingsStore.awaitLoaded()
            val target = settings.resolveEmbeddingTarget()
            if (target == null) {
                Log.i(TAG, "embedding 未配置，跳过索引")
                return@withContext ProcessResult(hasMore = false, deferred = false)
            }
            val store = vectorIndexProvider.get()
            // 没有待办就直接收工，不要闪一下“正在建立索引”
            val pendingTotal = conversationIndexTaskDao.count()
            if (pendingTotal <= 0) {
                return@withContext ProcessResult(hasMore = false, deferred = false)
            }
            // 总数只在本次执行开头定一次：任务处理完会被删掉，每批重新 count 会让分母缩水、进度倒退
            statusTracker.startIfNeeded(pendingTotal)
            var processed = 0
            val deferred = HashSet<String>()
            while (processed < maxConversations) {
                val task = if (deferred.isEmpty()) {
                    conversationIndexTaskDao.nextTask()
                } else {
                    conversationIndexTaskDao.nextTaskExcluding(deferred)
                } ?: break
                val handled = when (task.op) {
                    IndexTaskOp.DELETE.name -> handleDelete(task, store)
                    else -> handleUpsert(task, settings, target, store)
                }
                if (!handled) {
                    deferred += task.conversationId
                    continue
                }
                processed++
                statusTracker.advance()
            }
            // 只要队列里还有“不是本轮失败项”的任务，就说明这轮是被批次上限截断的，
            // 应该接着干；只有剩下的全是失败项时才收工
            val remaining = if (deferred.isEmpty()) {
                conversationIndexTaskDao.count() > 0
            } else {
                conversationIndexTaskDao.pendingConversationIds().any { it !in deferred }
            }
            // 这里只记原因，不结束执行：worker 可能还要接着下一批
            if (deferred.isNotEmpty()) {
                Log.i(TAG, "本批跳过 ${deferred.size} 个会话，等待退避重试")
                statusTracker.noteError("${deferred.size} 个会话本次没能完成，稍后自动重试")
            }
            ProcessResult(hasMore = remaining, deferred = deferred.isNotEmpty())
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
        return indexConversation(conversation, settings, target, store, task.version)
    }

    /** 返回 true 表示这次确实写完了（任务可以销）；false 表示本轮放弃，任务留着下次再来 */
    private suspend fun indexConversation(
        conversation: Conversation,
        settings: Settings,
        target: EmbeddingTarget,
        store: VectorIndex,
        expectedVersion: Long
    ): Boolean {
        val conversationKey = conversation.id.toString()
        val dimension = ensureDimension(settings, target)
        if (dimension == null) {
            Log.w(TAG, "无法确定 embedding 维度，放弃本轮")
            return false
        }
        val profile = VectorProfile(
            provider = target.fingerprint,
            model = target.model.modelId,
            dimension = dimension,
            instruction = settings.embeddingIndex.instruction
        )
        // 先定 profile：profile 变了会清库，之后读到的签名才是当前的
        store.ensureProfile(profile)
        if (store.activeProfileId() != profile.id) {
            Log.w(TAG, "profile 在准备阶段被切走，放弃本轮")
            return false
        }
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
            embedAndWrite(conversationKey, pending, target, store, expectedVersion, profile.id)
        }

        // 这一步很关键：没写成就绝不能销任务，否则网络抖一下这段历史就永远缺向量了
        if (!embedded) return false

        deleteStaleChunks(conversationKey, existing.keys, currentKeys, store)
        conversationIndexTaskDao.deleteIfVersionMatches(conversationKey, expectedVersion)
        Log.i(
            TAG,
            "indexed conversation=$conversationKey chunks=${currentKeys.size} " +
                "embedded=${pending.size} profile=${profile.id}"
        )
        return true
    }

    private suspend fun embedAndWrite(
        conversationKey: String,
        pending: List<PendingChunk>,
        target: EmbeddingTarget,
        store: VectorIndex,
        expectedVersion: Long,
        expectedProfileId: String,
    ): Boolean {
        for (batch in pending.chunked(EmbeddingClient.DEFAULT_BATCH_SIZE)) {
            // 每一批之前都重新确认：用户可能刚把开关关掉，或者换了模型；
            // 这时候继续发请求既没用也可能把旧模型的向量写进新库
            val latest = settingsStore.awaitLoaded().resolveEmbeddingTarget()
            if (latest == null || latest.model.id != target.model.id) {
                Log.i(TAG, "embedding 已被关闭或改配，停止本会话剩余批次")
                statusTracker.noteError("语义检索已关闭或更换了模型，索引已暂停")
                return false
            }
            if (store.activeProfileId() != expectedProfileId) {
                Log.i(TAG, "profile 在处理期间被切走，放弃本轮写入")
                return false
            }
            val vectors = try {
                embeddingClient.embed(target, batch.map { it.text })
            } catch (error: Throwable) {
                Log.w(TAG, "embedding 失败，本轮放弃: ${error.message}")
                statusTracker.noteError("生成向量失败：${error.message ?: error::class.simpleName}")
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
        dimensionCache[target.fingerprint]?.let { return it }
        return try {
            val probe = embeddingClient.embed(target, listOf(DIMENSION_PROBE_TEXT))
            probe.firstOrNull()?.size?.takeIf { it > 0 }?.also { dimensionCache[target.fingerprint] = it }
        } catch (error: Throwable) {
            Log.w(TAG, "探测 embedding 维度失败: ${error.message}")
            statusTracker.noteError("获取向量维度失败：${error.message ?: error::class.simpleName}")
            null
        }
    }

    /** 清空索引并重建：先清掉旧数据与任务，再给所有会话排队 */
    suspend fun rebuildAll() = withContext(Dispatchers.IO) {
        val store = vectorIndexProvider.get()
        store.clear()
        conversationIndexTaskDao.deleteAll()
        // 用当前时间当版本起点：正在跑的旧任务手里的版本（可能是 1、2、3）
        // 绝不可能和它撞上，否则旧任务会误以为自己还有效，把旧配置的向量写回来
        val generation = System.currentTimeMillis()
        val ids = conversationRepository.getAllConversationIds()
        for (id in ids) {
            conversationIndexTaskDao.upsert(
                ConversationIndexTaskEntity(
                    conversationId = id,
                    op = IndexTaskOp.UPSERT.name,
                    version = generation,
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

    /**
     * @param hasMore 还有现在就能处理的工作
     * @param deferred 本轮有会话失败，需要退避后重试
     */
    data class ProcessResult(val hasMore: Boolean, val deferred: Boolean)

    companion object {
        private const val TAG = "ConversationIndexer"
        private const val DEFAULT_MAX_CONVERSATIONS = 8
        private const val DIMENSION_PROBE_TEXT = "dimension probe"
    }
}
