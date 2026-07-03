package me.rerere.rikkahub.data.repository

import android.database.sqlite.SQLiteBlobTooBigException
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid

class ConversationRepository(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val favoriteDAO: FavoriteDAO,
    private val database: AppDatabase,
    private val filesManager: FilesManager,
    private val messageFtsManager: MessageFtsManager,
) {
    companion object {
        private const val PAGE_SIZE = 20
        private const val INITIAL_LOAD_SIZE = 40
    }

    private val messageIndexMutex = Mutex()

    suspend fun getRecentConversations(assistantId: Uuid, limit: Int = 10): List<Conversation> {
        return conversationDAO.getRecentConversationsOfAssistant(
            assistantId = assistantId.toString(),
            limit = limit
        ).map { entity ->
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes)
        }
    }

    fun getConversationsOfAssistant(assistantId: Uuid): Flow<List<Conversation>> {
        return conversationDAO
            .getConversationsOfAssistant(assistantId.toString())
            .map { flow ->
                flow.map { entity ->
                    // 列表视图不需要完整的 nodes，使用空列表
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun getConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getUnfiledConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getUnfiledConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getConversationsOfFolderPaging(folderId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfFolderPaging(folderId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    suspend fun getConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.getConversationsOfAssistantPaging(assistantId.toString())
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun searchConversationsOfAssistantPage(
        assistantId: Uuid,
        titleKeyword: String,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.searchConversationsOfAssistantPaging(
            assistantId = assistantId.toString(),
            searchText = titleKeyword
        )
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    fun searchConversations(titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversations(titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsPaging(titleKeyword: String): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.searchConversationsPaging(titleKeyword) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun searchConversationsOfAssistant(assistantId: Uuid, titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversationsOfAssistant(assistantId.toString(), titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsOfAssistantPaging(assistantId: Uuid, titleKeyword: String): Flow<PagingData<Conversation>> =
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = INITIAL_LOAD_SIZE,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                conversationDAO.searchConversationsOfAssistantPaging(
                    assistantId.toString(),
                    titleKeyword
                )
            }
        ).flow.map { pagingData ->
            pagingData.map { entity ->
                conversationSummaryToConversation(entity)
            }
        }

    suspend fun getConversationById(uuid: Uuid): Conversation? {
        val entity = conversationDAO.getConversationById(uuid.toString())
        return if (entity != null) {
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes)
        } else null
    }

    suspend fun existsConversationById(uuid: Uuid): Boolean {
        return conversationDAO.existsById(uuid.toString())
    }

    suspend fun countConversations(): Int {
        return conversationDAO.countAll()
    }

    suspend fun insertConversation(conversation: Conversation) {
        database.withTransaction {
            conversationDAO.insert(
                conversationToConversationEntity(conversation)
            )
            saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
        }
        messageIndexMutex.withLock {
            messageFtsManager.indexConversation(conversation)
            markMessageIndexReadyIfTrivialLocked()
        }
    }

    suspend fun updateConversation(conversation: Conversation) {
        database.withTransaction {
            conversationDAO.update(
                conversationToConversationEntity(conversation)
            )
            // 删除旧的节点，插入新的节点
            messageNodeDAO.deleteByConversation(conversation.id.toString())
            saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
        }
        messageIndexMutex.withLock {
            messageFtsManager.indexConversation(conversation)
            markMessageIndexReadyIfTrivialLocked()
        }
    }

    suspend fun mergeFromBackupDatabase(backupDatabase: AppDatabase): BackupDatabaseMergeResult {
        val backupConversationDAO = backupDatabase.conversationDao()
        val backupMessageNodeDAO = backupDatabase.messageNodeDao()
        val backupMemoryDAO = backupDatabase.memoryDao()
        val currentMemoryDAO = database.memoryDao()
        var inserted = 0
        var updated = 0
        var skipped = 0
        var insertedMemories = 0
        var skippedMemories = 0

        database.withTransaction {
            backupConversationDAO.getAllIds().forEach { conversationId ->
                val backupConversation = backupConversationDAO.getConversationById(conversationId)
                    ?: return@forEach
                val currentConversation = conversationDAO.getConversationById(conversationId)
                if (!shouldImportBackupConversation(currentConversation?.updateAt, backupConversation.updateAt)) {
                    skipped++
                    return@forEach
                }

                val backupNodes = backupMessageNodeDAO.getNodesOfConversation(conversationId)
                if (currentConversation == null) {
                    conversationDAO.insert(backupConversation)
                    inserted++
                } else {
                    conversationDAO.update(backupConversation)
                    messageNodeDAO.deleteByConversation(conversationId)
                    updated++
                }
                if (backupNodes.isNotEmpty()) {
                    messageNodeDAO.insertAll(backupNodes)
                }
            }

            val existingMemoryKeys = currentMemoryDAO.getAllMemories()
                .mapTo(hashSetOf()) { memory ->
                    memory.assistantId to normalizeMemoryContent(memory.content)
                }
            backupMemoryDAO.getAllMemories().forEach { backupMemory ->
                val normalizedContent = normalizeMemoryContent(backupMemory.content)
                val memoryKey = backupMemory.assistantId to normalizedContent
                if (!existingMemoryKeys.add(memoryKey)) {
                    skippedMemories++
                    return@forEach
                }
                currentMemoryDAO.insertMemory(
                    backupMemory.copy(
                        id = 0,
                        content = normalizedContent,
                    )
                )
                insertedMemories++
            }
        }

        val changed = inserted + updated
        if (changed > 0) {
            rebuildAllIndexes()
        }
        return BackupDatabaseMergeResult(
            conversations = ConversationMergeResult(
                inserted = inserted,
                updated = updated,
                skipped = skipped,
            ),
            memories = MemoryMergeResult(
                inserted = insertedMemories,
                updated = 0,
                skipped = skippedMemories,
            ),
        )
    }

    suspend fun deleteConversation(conversation: Conversation) {
        // 获取完整的 Conversation（包含 messageNodes）以正确清理文件
        val fullConversation = if (conversation.messageNodes.isEmpty()) {
            getConversationById(conversation.id) ?: conversation
        } else {
            conversation
        }
        messageIndexMutex.withLock {
            messageFtsManager.deleteConversation(conversation.id.toString())
        }
        database.withTransaction {
            // message_node 会通过 CASCADE 自动删除
            conversationDAO.delete(
                conversationToConversationEntity(conversation)
            )
        }
        filesManager.deleteChatFiles(fullConversation.files)
    }

    suspend fun searchMessages(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
    ) = messageIndexMutex.withLock {
        if (keyword.isBlank()) return@withLock emptyList()
        ensureMessageIndexReadyLocked()
        messageFtsManager.search(keyword, sort)
    }

    suspend fun searchAssistantHistory(
        assistantId: Uuid,
        keyword: String,
        currentConversationId: Uuid? = null,
        focusConversationId: Uuid? = null,
        role: MessageRole? = null,
        limit: Int = DEFAULT_HISTORY_SEARCH_LIMIT,
        excludeSnippetKeys: Set<String> = emptySet(),
    ): List<MessageSearchResult> = messageIndexMutex.withLock {
        if (keyword.isBlank()) return@withLock emptyList()
        ensureMessageIndexReadyLocked()
        val normalizedLimit = normalizeHistorySearchLimit(limit)
        val plan = buildHistoryQueryPlan(keyword)
        val perQueryFetchLimit = (normalizedLimit * 4)
            .coerceAtLeast(24)
            .coerceAtMost(MAX_HISTORY_SEARCH_FETCH_LIMIT)
        val assistantIdValue = assistantId.toString()
        val conversationIdValue = currentConversationId?.toString()
        val focusConversationIdValue = focusConversationId?.toString()
        val roleValue = role?.name?.lowercase()

        val rawResults = if (plan.rawQuery.isBlank()) {
            emptyList()
        } else {
            messageFtsManager.searchAssistantHistory(
                assistantId = assistantIdValue,
                keyword = plan.rawQuery,
                excludeConversationId = conversationIdValue,
                conversationId = focusConversationIdValue,
                role = roleValue,
                limit = perQueryFetchLimit,
                selectedOnly = true,
            )
        }
        val segmentResults = plan.segmentQueries.map { query ->
            messageFtsManager.searchAssistantHistory(
                assistantId = assistantIdValue,
                keyword = query,
                excludeConversationId = conversationIdValue,
                conversationId = focusConversationIdValue,
                role = roleValue,
                limit = perQueryFetchLimit,
                selectedOnly = true,
            )
        }
        val tokenResults = plan.tokenQueries.map { query ->
            messageFtsManager.searchAssistantHistory(
                assistantId = assistantIdValue,
                keyword = query,
                excludeConversationId = conversationIdValue,
                conversationId = focusConversationIdValue,
                role = roleValue,
                limit = perQueryFetchLimit,
                selectedOnly = true,
            )
        }

        val mergedResults = mergeHistoryRouteCandidates(
            rawCandidates = buildRouteCandidates(
                route = HistorySearchRoute.RAW,
                routeResults = listOf(rawResults),
            ),
            segmentCandidates = buildRouteCandidates(
                route = HistorySearchRoute.SEGMENT,
                routeResults = segmentResults,
            ),
            tokenCandidates = buildRouteCandidates(
                route = HistorySearchRoute.TOKEN,
                routeResults = tokenResults,
                totalTokenCount = plan.tokenQueries.size,
            ),
        )
        val filteredResults = filterNewHistorySearchResults(
            results = mergedResults,
            seenSnippetKeys = excludeSnippetKeys,
        )
        selectHistorySearchCandidates(
            results = filteredResults,
            requestedLimit = normalizedLimit,
            perConversationLimit = if (focusConversationId == null) {
                HISTORY_SEARCH_PER_CONVERSATION_LIMIT
            } else {
                normalizedLimit
            },
        )
    }

    suspend fun readAssistantHistory(
        refs: List<String>,
        before: Int = DEFAULT_HISTORY_READ_BEFORE,
        after: Int = DEFAULT_HISTORY_READ_AFTER,
    ): List<ConversationHistoryReadResult> {
        val normalizedRefs = normalizeHistoryReadRefs(refs)
        if (normalizedRefs.isEmpty()) return emptyList()

        val parsedRefs = normalizedRefs.mapNotNull(::parseConversationHistoryRef)
        if (parsedRefs.isEmpty()) return emptyList()

        val normalizedBefore = normalizeHistoryReadBefore(before)
        val normalizedAfter = normalizeHistoryReadAfter(after)
        val conversationsById = mutableMapOf<Uuid, Conversation>()
        parsedRefs.map { it.conversationId }
            .distinct()
            .forEach { conversationId ->
                getConversationById(conversationId)?.let { conversation ->
                    conversationsById[conversationId] = conversation
                }
            }

        return parsedRefs.mapNotNull { ref ->
            val conversation = conversationsById[ref.conversationId] ?: return@mapNotNull null
            val nodeIndex = conversation.messageNodes.indexOfFirst { it.id == ref.nodeId }
            if (nodeIndex == -1) return@mapNotNull null

            val node = conversation.messageNodes[nodeIndex]
            val selectedMessage = node.currentMessage
            if (selectedMessage.id != ref.messageId) return@mapNotNull null

            val startIndex = (nodeIndex - normalizedBefore).coerceAtLeast(0)
            val endIndex = (nodeIndex + normalizedAfter).coerceAtMost(conversation.messageNodes.lastIndex)
            val messages = conversation.messageNodes.subList(startIndex, endIndex + 1)
                .mapIndexedNotNull { index, messageNode ->
                    val message = messageNode.currentMessage
                    val text = message.toText().trim()
                    if (text.isBlank()) return@mapIndexedNotNull null
                    ConversationHistoryReadMessage(
                        role = message.role,
                        createdAt = message.createdAt.toString(),
                        text = text,
                        isMatch = startIndex + index == nodeIndex,
                    )
                }
            if (messages.none { it.isMatch }) return@mapNotNull null

            ConversationHistoryReadResult(
                ref = ref.toString(),
                conversationId = conversation.id,
                title = conversation.title,
                messages = messages,
            )
        }
    }

    suspend fun rebuildAllIndexes(onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }) {
        messageIndexMutex.withLock {
            rebuildAllIndexesLocked(onProgress)
        }
    }

    private suspend fun ensureMessageIndexReadyLocked() {
        val schemaReady = messageFtsManager.ensureSchema()
        if (!schemaReady || !messageFtsManager.isReady()) {
            rebuildAllIndexesLocked()
        }
    }

    private suspend fun markMessageIndexReadyIfTrivialLocked() {
        if (conversationDAO.countAll() <= 1) {
            messageFtsManager.markReady()
        }
    }

    private suspend fun rebuildAllIndexesLocked(onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }) {
        messageFtsManager.deleteAll()
        val allIds = conversationDAO.getAllIds()
        val total = allIds.size
        allIds.forEachIndexed { index, id ->
            val entity = conversationDAO.getConversationById(id) ?: return@forEachIndexed
            val nodes = loadMessageNodes(entity.id)
            val conversation = conversationEntityToConversation(entity, nodes)
            messageFtsManager.indexConversation(conversation)
            onProgress(index + 1, total)
        }
        messageFtsManager.markReady()
    }

    suspend fun deleteConversationOfAssistant(assistantId: Uuid) {
        getConversationsOfAssistant(assistantId).first().forEach { conversation ->
            deleteConversation(conversation)
        }
    }

    fun conversationToConversationEntity(conversation: Conversation): ConversationEntity {
        require(conversation.messageNodes.none { it.messages.any { message -> message.hasBase64Part() } })
        return ConversationEntity(
            id = conversation.id.toString(),
            title = conversation.title,
            nodes = "[]",  // nodes 现在存储在单独的表中
            createAt = conversation.createAt.toEpochMilli(),
            updateAt = conversation.updateAt.toEpochMilli(),
            assistantId = conversation.assistantId.toString(),
            chatSuggestions = JsonInstant.encodeToString(conversation.chatSuggestions),
            isPinned = conversation.isPinned,
            customSystemPrompt = conversation.customSystemPrompt ?: "",
            modeInjectionIds = JsonInstant.encodeToString(conversation.modeInjectionIds),
            lorebookIds = JsonInstant.encodeToString(conversation.lorebookIds),
            workspaceCwd = conversation.workspaceCwd ?: "",
            folderId = conversation.folderId?.toString() ?: "",
        )
    }

    fun conversationEntityToConversation(
        conversationEntity: ConversationEntity,
        messageNodes: List<MessageNode>
    ): Conversation {
        return Conversation(
            id = Uuid.parse(conversationEntity.id),
            title = conversationEntity.title,
            messageNodes = messageNodes.filter { it.messages.isNotEmpty() },
            createAt = Instant.ofEpochMilli(conversationEntity.createAt),
            updateAt = Instant.ofEpochMilli(conversationEntity.updateAt),
            assistantId = Uuid.parse(conversationEntity.assistantId),
            chatSuggestions = JsonInstant.decodeFromString(conversationEntity.chatSuggestions),
            isPinned = conversationEntity.isPinned,
            customSystemPrompt = conversationEntity.customSystemPrompt.ifEmpty { null },
            modeInjectionIds = JsonInstant.decodeFromString(conversationEntity.modeInjectionIds),
            lorebookIds = JsonInstant.decodeFromString(conversationEntity.lorebookIds),
            workspaceCwd = conversationEntity.workspaceCwd.ifEmpty { null },
            folderId = conversationEntity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
        )
    }

    fun getPinnedConversations(): Flow<List<Conversation>> {
        return conversationDAO
            .getPinnedConversations()
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    suspend fun togglePinStatus(conversationId: Uuid) {
        conversationDAO.updatePinStatus(
            id = conversationId.toString(),
            isPinned = !(getConversationById(conversationId)?.isPinned ?: false)
        )
    }

    /**
     * 单列更新会话的文件夹归属，folderId 为 null 表示移出文件夹（未归类）。
     */
    suspend fun updateConversationFolderId(conversationId: Uuid, folderId: Uuid?) {
        conversationDAO.updateFolderId(
            id = conversationId.toString(),
            folderId = folderId?.toString() ?: ""
        )
    }

    private fun conversationSummaryToConversation(entity: LightConversationEntity): Conversation {
        return Conversation(
            id = Uuid.parse(entity.id),
            assistantId = Uuid.parse(entity.assistantId),
            title = entity.title,
            isPinned = entity.isPinned,
            createAt = Instant.ofEpochMilli(entity.createAt),
            updateAt = Instant.ofEpochMilli(entity.updateAt),
            messageNodes = emptyList(),
            folderId = entity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
        )
    }

    private suspend fun loadMessageNodes(conversationId: String): List<MessageNode> {
        val favoriteNodeIds = favoriteDAO
            .getFavoriteNodeIdsOfConversation(conversationId)
            .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
            .toSet()

        return database.withTransaction {
            val nodes = mutableListOf<MessageNode>()
            var offset = 0
            val pageSize = 64
            while (true) {
                val page = try {
                    messageNodeDAO.getNodesOfConversationPaged(conversationId, pageSize, offset)
                } catch (e: SQLiteBlobTooBigException) {
                    e.printStackTrace()
                    offset += pageSize
                    continue
                } catch (e: IllegalStateException) {
                    e.printStackTrace()
                    offset += pageSize
                    continue
                }
                if (page.isEmpty()) break
                page.forEach { entity ->
                    val messages = JsonInstant.decodeFromString<List<UIMessage>>(entity.messages)
                    val nodeId = Uuid.parse(entity.id)
                    nodes.add(
                        MessageNode(
                            id = nodeId,
                            messages = messages,
                            selectIndex = entity.selectIndex,
                            isFavorite = favoriteNodeIds.contains(nodeId)
                        )
                    )
                }
                offset += page.size
            }
            nodes
        }
    }

    private suspend fun saveMessageNodes(conversationId: String, nodes: List<MessageNode>) {
        val entities = nodes.mapIndexed { index, node ->
            MessageNodeEntity(
                id = node.id.toString(),
                conversationId = conversationId,
                nodeIndex = index,
                messages = JsonInstant.encodeToString(node.messages),
                selectIndex = node.selectIndex
            )
        }
        messageNodeDAO.insertAll(entities)
    }
}

/**
 * 轻量级的会话查询结果，不包含 nodes 和 suggestions 字段
 */
data class LightConversationEntity(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long,
    val folderId: String = "",
)

data class ConversationPageResult(
    val items: List<Conversation>,
    val nextOffset: Int?,
)

data class BackupDatabaseMergeResult(
    val conversations: ConversationMergeResult,
    val memories: MemoryMergeResult,
)

data class ConversationMergeResult(
    val inserted: Int,
    val updated: Int,
    val skipped: Int,
)

data class MemoryMergeResult(
    val inserted: Int,
    val updated: Int,
    val skipped: Int,
)

internal fun normalizeMemoryContent(content: String): String =
    content.trim().replace(Regex("\\s+"), " ")

internal fun shouldImportBackupConversation(currentUpdateAt: Long?, backupUpdateAt: Long): Boolean {
    return currentUpdateAt == null || backupUpdateAt > currentUpdateAt
}
