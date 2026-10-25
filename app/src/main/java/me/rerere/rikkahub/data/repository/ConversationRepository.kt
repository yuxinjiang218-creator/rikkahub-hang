package me.rerere.rikkahub.data.repository

import android.database.sqlite.SQLiteBlobTooBigException
import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.ConversationSortOrder
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.ConversationIndexTaskDAO
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.ConversationIndexTaskEntity
import me.rerere.rikkahub.data.db.entity.IndexTaskOp
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.index.EmbeddingClient
import me.rerere.rikkahub.data.index.EmbeddingTarget
import me.rerere.rikkahub.data.index.HistorySearchOutcome
import me.rerere.rikkahub.data.index.IndexTaskScheduler
import me.rerere.rikkahub.data.index.IndexableText
import me.rerere.rikkahub.data.index.RankedFusion
import me.rerere.rikkahub.data.index.RecallSource
import me.rerere.rikkahub.data.index.SemanticState
import me.rerere.rikkahub.data.index.StructuralQuery
import me.rerere.rikkahub.data.index.TextChunker
import me.rerere.rikkahub.data.index.VectorFilter
import me.rerere.rikkahub.data.index.VectorIndexProvider
import me.rerere.rikkahub.data.index.VectorProfile
import me.rerere.rikkahub.data.index.fuseHistoryResults
import me.rerere.rikkahub.data.index.resolveEmbeddingTarget
import me.rerere.rikkahub.data.index.selectedMessageOrNull
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant
import kotlin.time.toJavaInstant
import kotlin.uuid.Uuid

class ConversationRepository(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val favoriteDAO: FavoriteDAO,
    private val database: AppDatabase,
    private val filesManager: FilesManager,
    private val messageFtsManager: MessageFtsManager,
    private val conversationIndexTaskDao: ConversationIndexTaskDAO,
    private val indexTaskScheduler: IndexTaskScheduler,
    private val settingsStore: SettingsStore,
    private val vectorIndexProvider: VectorIndexProvider,
    private val embeddingClient: EmbeddingClient,
) {
    companion object {
        private const val TAG = "ConversationRepository"

        // 800ms 对云端 embedding 太抠了，跳一下就白丢一轮召回，宽松一点
        private const val QUERY_EMBEDDING_TIMEOUT_MS = 3000L

        // 当前会话永远排在索引队列里（每轮都在写新消息），为它单独发一句
        // “结果不含它们” 等于 100% 误报，所以先把当前会话排掉，再看积压是否真的值得提
        private const val INDEXING_BACKLOG_THRESHOLD = 8
        private const val QUERY_EMBEDDING_CACHE_SIZE = 32
        private const val PAGE_SIZE = 20
        private const val INITIAL_LOAD_SIZE = 40
    }

    /** 查询向量缓存，避免重复点搜索时反复打接口 */
    private val queryEmbeddingCache = java.util.concurrent.ConcurrentHashMap<String, FloatArray>()

    suspend fun hasFileReference(fileUrl: String): Boolean =
        messageNodeDAO.hasFileReference(JsonInstant.encodeToString(fileUrl))

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

    suspend fun getTitlesOfAssistant(assistantId: Uuid): Set<String> =
        conversationDAO.getTitlesOfAssistant(assistantId.toString()).toSet()

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

    fun getUnfiledConversationsOfAssistantPaging(
        assistantId: Uuid,
        sortOrder: ConversationSortOrder = ConversationSortOrder.UPDATE_TIME,
    ): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = {
            conversationDAO.getUnfiledConversationsOfAssistantPaging(
                assistantId = assistantId.toString(),
                sortByCreateTime = sortOrder == ConversationSortOrder.CREATE_TIME,
            )
        }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getConversationsOfFolderPaging(
        folderId: Uuid,
        sortOrder: ConversationSortOrder = ConversationSortOrder.UPDATE_TIME,
    ): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = {
            conversationDAO.getConversationsOfFolderPaging(
                folderId = folderId.toString(),
                sortByCreateTime = sortOrder == ConversationSortOrder.CREATE_TIME,
            )
        }
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

    suspend fun getUnfiledConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult = loadConversationPage(
        conversationDAO.getUnfiledConversationsOfAssistantPaging(assistantId.toString(), sortByCreateTime = false),
        offset,
        limit,
    )

    suspend fun getConversationsOfFolderPage(
        folderId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult = loadConversationPage(
        conversationDAO.getConversationsOfFolderPaging(folderId.toString(), sortByCreateTime = false),
        offset,
        limit,
    )

    private suspend fun loadConversationPage(
        pagingSource: PagingSource<Int, LightConversationEntity>,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
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
        } else {
            null
        }
    }

    suspend fun existsConversationById(uuid: Uuid): Boolean {
        return conversationDAO.existsById(uuid.toString())
    }

    suspend fun countConversations(): Int {
        return conversationDAO.countAll()
    }

    suspend fun countConversationsByAssistant(): Map<Uuid, Int> {
        return conversationDAO.countByAssistant().mapNotNull { row ->
            runCatching { Uuid.parse(row.assistantId) }.getOrNull()?.let { it to row.count }
        }.toMap()
    }

    suspend fun insertConversation(conversation: Conversation) {
        database.withTransaction {
            conversationDAO.insert(
                conversationToConversationEntity(conversation)
            )
            saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
            enqueueIndexTask(conversation.id.toString(), IndexTaskOp.UPSERT)
        }
        messageFtsManager.indexConversation(conversation)
        indexTaskScheduler.schedule()
    }

    suspend fun updateConversation(conversation: Conversation) {
        database.withTransaction {
            conversationDAO.update(
                conversationToConversationEntity(conversation)
            )
            // 删除旧的节点，插入新的节点
            messageNodeDAO.deleteByConversation(conversation.id.toString())
            saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
            enqueueIndexTask(conversation.id.toString(), IndexTaskOp.UPSERT)
        }
        messageFtsManager.indexConversation(conversation)
        indexTaskScheduler.schedule()
    }

    suspend fun deleteConversation(conversation: Conversation) {
        // 获取完整的 Conversation（包含 messageNodes）以正确清理文件
        val fullConversation = if (conversation.messageNodes.isEmpty()) {
            getConversationById(conversation.id) ?: conversation
        } else {
            conversation
        }
        messageFtsManager.deleteConversation(conversation.id.toString())
        database.withTransaction {
            // message_node 会通过 CASCADE 自动删除
            conversationDAO.delete(
                conversationToConversationEntity(conversation)
            )
            // 删除任务保留到 worker 处理完，避免并发请求把已删内容写回来
            enqueueIndexTask(conversation.id.toString(), IndexTaskOp.DELETE)
        }
        indexTaskScheduler.schedule()
        filesManager.deleteChatFiles(fullConversation.files)
    }

    suspend fun searchMessages(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        assistantId: Uuid? = null,
    ) = messageFtsManager.search(keyword, sort, assistantId?.let { listOf(it.toString()) })

    /** 只在 [assistantIds] 这些助手的对话里搜索 */
    suspend fun searchMessagesOfAssistants(keyword: String, assistantIds: Set<Uuid>) =
        messageFtsManager.search(keyword, MessageSearchSort.RELEVANCE, assistantIds.map { it.toString() })

    /** 词法三路召回的原始候选（每路内部已排序去重），供纯词法与混合检索共用 */
    private suspend fun collectHistoryRouteCandidates(
        assistantId: Uuid,
        keyword: String,
        currentConversationId: Uuid,
        focusConversationId: Uuid?,
        role: MessageRole?,
        fetchLimit: Int,
    ): List<Pair<HistorySearchRoute, List<HistoryRouteCandidate>>> {
        val plan = buildHistoryQueryPlan(keyword)
        val conversations = mutableMapOf<Uuid, Conversation?>()

        suspend fun searchRoute(query: String): List<MessageSearchResult> {
            val rawResults = messageFtsManager.search(
                keyword = query,
                sort = MessageSearchSort.RELEVANCE,
                assistantIds = listOf(assistantId.toString()),
                excludeConversationId = currentConversationId.toString(),
                conversationId = focusConversationId?.toString(),
                limit = fetchLimit,
                selectedOnly = true,
                messageRole = role?.name?.lowercase(),
            )
            return rawResults.mapNotNull { result ->
                val conversationId = runCatching { Uuid.parse(result.conversationId) }.getOrNull()
                    ?: return@mapNotNull null
                val conversation = if (conversations.containsKey(conversationId)) {
                    conversations[conversationId]
                } else {
                    getConversationById(conversationId).also { conversations[conversationId] = it }
                } ?: return@mapNotNull null
                val nodeId = runCatching { Uuid.parse(result.nodeId) }.getOrNull()
                    ?: return@mapNotNull null
                val messageId = runCatching { Uuid.parse(result.messageId) }.getOrNull()
                    ?: return@mapNotNull null
                val node = conversation.messageNodes.firstOrNull { it.id == nodeId } ?: return@mapNotNull null
                val message = node.currentMessage
                if (message.id != messageId || (role != null && message.role != role)) return@mapNotNull null
                result.copy(
                    role = message.role,
                    createdAt = message.createdAt.toInstant(TimeZone.currentSystemDefault()).toJavaInstant(),
                    isSelected = true,
                )
            }
        }

        val rawCandidates = if (plan.rawQuery.isBlank()) {
            emptyList()
        } else {
            buildRouteCandidates(HistorySearchRoute.RAW, listOf(searchRoute(plan.rawQuery)))
        }
        val segmentResults = mutableListOf<List<MessageSearchResult>>()
        for (query in plan.segmentQueries) segmentResults += searchRoute(query)
        val segmentCandidates = buildRouteCandidates(
            HistorySearchRoute.SEGMENT,
            segmentResults,
        )
        val tokenResults = mutableListOf<List<MessageSearchResult>>()
        for (query in plan.tokenQueries) tokenResults += searchRoute(query)
        val tokenCandidates = buildRouteCandidates(
            HistorySearchRoute.TOKEN,
            tokenResults,
            totalTokenCount = plan.tokenQueries.size,
        )
        return listOf(
            HistorySearchRoute.RAW to rawCandidates,
            HistorySearchRoute.SEGMENT to segmentCandidates,
            HistorySearchRoute.TOKEN to tokenCandidates,
        )
    }

    /**
     * 搜索页用的跨会话混合检索。
     *
     * 和工具那条路的区别：不限助手、不排除当前会话、不分页，
     * 按时间排序时直接返回纯词法结果——把向量揉进「按时间」的列表里只会把顺序搞乱。
     */
    suspend fun searchMessagesHybrid(
        keyword: String,
        sort: MessageSearchSort,
        assistantId: Uuid?,
    ): HistorySearchOutcome {
        if (keyword.isBlank()) return HistorySearchOutcome(emptyList(), SemanticState.DISABLED)
        val lexical = searchMessages(keyword, sort, assistantId)
        val settings = settingsStore.awaitLoaded()
        val target = settings.resolveEmbeddingTarget()
        if (target == null) {
            return HistorySearchOutcome(
                results = lexical,
                semanticState = if (settings.embeddingIndex.enabled) {
                    SemanticState.NOT_CONFIGURED
                } else {
                    SemanticState.DISABLED
                },
            )
        }
        val pending = runCatching { conversationIndexTaskDao.pendingConversationIds().toSet() }
            .getOrDefault(emptySet())
        // 界面搜索：语义这一段出问题就静默退回纯词法，用户不需要知道
        val recall = recallByEmbedding(
            keyword = keyword,
            target = target,
            assistantId = assistantId,
            currentConversationId = null,
            focusConversationId = null,
            role = null,
            fetchLimit = historyFetchLimit(lexical.size.coerceAtLeast(DEFAULT_HISTORY_SEARCH_LIMIT)),
            excludeConversationIds = pending,
            minSimilarity = settings.embeddingIndex.minSimilarity,
        )
        val vectorResults = recall.results
        val state = recall.state
        if (state != SemanticState.OK && state != SemanticState.INDEXING) {
            return HistorySearchOutcome(lexical, state)
        }
        if (vectorResults.isEmpty()) return HistorySearchOutcome(lexical, state)

        // 按时间排序时，向量只负责“把漏掉的找出来”，最终顺序仍然严格按时间，
        // 否则用户选了「最新优先」却看到旧记录插在中间
        if (sort != MessageSearchSort.RELEVANCE) {
            val merged = (lexical + vectorResults)
                .distinctBy { historySearchResultRef(it) }
                .sortedWith(
                    if (sort == MessageSearchSort.NEWEST_FIRST) {
                        compareByDescending<MessageSearchResult> { it.updateAt }
                    } else {
                        compareBy<MessageSearchResult> { it.updateAt }
                    }
                )
            return HistorySearchOutcome(merged, state)
        }

        // 搜索页是平铺列表，不做每会话限制，否则用户会以为记录丢了
        val fused = fuseHistoryResults(
            lexicalRoutes = if (lexical.isEmpty()) {
                emptyList()
            } else {
                listOf(RecallSource.RAW to lexical)
            },
            vectorResults = vectorResults,
            requestedLimit = lexical.size + vectorResults.size,
            perConversationLimit = Int.MAX_VALUE,
        )
        return fused.copy(semanticState = state)
    }

    suspend fun searchAssistantHistory(
        assistantId: Uuid,
        keyword: String,
        currentConversationId: Uuid,
        focusConversationId: Uuid? = null,
        role: MessageRole? = null,
        limit: Int = DEFAULT_HISTORY_SEARCH_LIMIT,
        excludeSnippetKeys: Set<String> = emptySet(),
    ): List<MessageSearchResult> {
        if (keyword.isBlank()) return emptyList()
        val normalizedLimit = normalizeHistorySearchLimit(limit)
        val routes = collectHistoryRouteCandidates(
            assistantId = assistantId,
            keyword = keyword,
            currentConversationId = currentConversationId,
            focusConversationId = focusConversationId,
            role = role,
            fetchLimit = historyFetchLimit(normalizedLimit),
        )
        val candidates = routes.associate { (route, list) -> route to list }
        val merged = mergeHistoryRouteCandidates(
            rawCandidates = candidates[HistorySearchRoute.RAW].orEmpty(),
            segmentCandidates = candidates[HistorySearchRoute.SEGMENT].orEmpty(),
            tokenCandidates = candidates[HistorySearchRoute.TOKEN].orEmpty(),
        )
        val fresh = filterNewHistorySearchResults(merged, excludeSnippetKeys)
        return selectHistorySearchCandidates(
            fresh,
            requestedLimit = normalizedLimit,
            perConversationLimit = if (focusConversationId ==
                null) {
                HISTORY_SEARCH_PER_CONVERSATION_LIMIT
            } else {
                normalizedLimit
            },
        )
    }

    /**
     * 混合检索：词法三路 + 向量一路做 RRF 融合。
     *
     * 语义召回不可用时自动退回纯词法，并把原因放进 [HistorySearchOutcome.semanticState]。
     * 所有硬约束（助手、排除当前会话、指定会话、角色、选中分支、待处理索引任务）都在候选截断前生效。
     */
    suspend fun searchAssistantHistoryHybrid(
        assistantId: Uuid,
        keyword: String,
        currentConversationId: Uuid,
        focusConversationId: Uuid? = null,
        role: MessageRole? = null,
        limit: Int = DEFAULT_HISTORY_SEARCH_LIMIT,
        offset: Int = 0,
        excludeSnippetKeys: Set<String> = emptySet(),
    ): HistorySearchOutcome {
        if (keyword.isBlank()) return HistorySearchOutcome(emptyList(), SemanticState.DISABLED)
        val normalizedLimit = normalizeHistorySearchLimit(limit)
        val normalizedOffset = offset.coerceAtLeast(0)
        // 翻页要的是第 offset+limit 名，取数上限也必须照这个来，否则第二页会空
        val fetchLimit = historyFetchLimit(normalizedLimit + normalizedOffset)
        val routes = collectHistoryRouteCandidates(
            assistantId = assistantId,
            keyword = keyword,
            currentConversationId = currentConversationId,
            focusConversationId = focusConversationId,
            role = role,
            fetchLimit = fetchLimit,
        ).map { (route, candidates) ->
            // 已经返回过的片段在进融合之前就要剔掉，否则 RRF 还会把它们排上来
            route to candidates.filter { candidate ->
                val key = historySnippetKey(
                    historySearchResultRef(candidate.result),
                    candidate.result.snippet
                )
                key == null || key !in excludeSnippetKeys
            }
        }
        val lexicalRoutes = routes.map { (route, candidates) ->
            route.toRecallSource() to candidates.map { candidate -> candidate.result }
        }
        val lexicalMerged = mergeHistoryRouteCandidates(
            rawCandidates = routes.first { it.first == HistorySearchRoute.RAW }.second,
            segmentCandidates = routes.first { it.first == HistorySearchRoute.SEGMENT }.second,
            tokenCandidates = routes.first { it.first == HistorySearchRoute.TOKEN }.second,
        )
        val filtered = filterNewHistorySearchResults(lexicalMerged, excludeSnippetKeys)

        val settings = settingsStore.awaitLoaded()
        val target = settings.resolveEmbeddingTarget()
        if (target == null) {
            return fallbackOutcome(
                filtered,
                normalizedLimit,
                focusConversationId,
                normalizedOffset,
                if (settings.embeddingIndex.enabled) SemanticState.NOT_CONFIGURED else SemanticState.DISABLED
            )
        }
        // 路径、错误码这类精确查询已经被词法高置信命中，不用再花一次 embedding
        if (StructuralQuery.isStructural(keyword) && filtered.isNotEmpty()) {
            return fallbackOutcome(
                filtered,
                normalizedLimit,
                focusConversationId,
                normalizedOffset,
                SemanticState.SKIPPED_EXACT
            )
        }

        val pending = runCatching { conversationIndexTaskDao.pendingConversationIds().toSet() }
            .getOrDefault(emptySet())
        val vectorOutcome = recallByEmbedding(
            keyword = keyword,
            target = target,
            assistantId = assistantId,
            currentConversationId = currentConversationId,
            focusConversationId = focusConversationId,
            role = role,
            fetchLimit = fetchLimit,
            excludeConversationIds = pending,
            minSimilarity = settings.embeddingIndex.minSimilarity,
        )
        // 工具路径：模型要知道语义这一段为什么没参与，否则它只会以为「搜不到」
        if (vectorOutcome.state != SemanticState.OK && vectorOutcome.state != SemanticState.INDEXING) {
            return fallbackOutcome(
                lexical = filtered,
                limit = normalizedLimit,
                focusConversationId = focusConversationId,
                offset = normalizedOffset,
                state = vectorOutcome.state,
                detail = vectorOutcome.detail,
            )
        }
        val vectorResults = filterNewHistorySearchResults(vectorOutcome.results, excludeSnippetKeys)
        if (vectorResults.isEmpty() && filtered.isEmpty()) {
            return HistorySearchOutcome(
                emptyList(),
                vectorOutcome.state,
                semanticDetail = vectorOutcome.detail,
            )
        }
        val fused = fuseHistoryResults(
            lexicalRoutes = lexicalRoutes,
            vectorResults = vectorResults,
            requestedLimit = normalizedLimit,
            // 指定会话时要翻页，块内上限必须跟着 offset 一起放大，否则第二页直接空
            perConversationLimit = if (focusConversationId == null) {
                RankedFusion.perConversationCap(normalizedLimit + normalizedOffset)
            } else {
                normalizedLimit + normalizedOffset
            },
            offset = normalizedOffset,
        )
        return fused.copy(
            semanticState = vectorOutcome.state,
            semanticDetail = vectorOutcome.detail,
        )
    }

    private fun fallbackOutcome(
        lexical: List<MessageSearchResult>,
        limit: Int,
        focusConversationId: Uuid?,
        offset: Int,
        state: SemanticState,
        detail: String? = null,
    ): HistorySearchOutcome {
        val selected = selectHistorySearchCandidates(
            lexical,
            // 池子整段展开才能给出准确的 total 和真实的翻页终点
            requestedLimit = Int.MAX_VALUE,
            perConversationLimit = if (focusConversationId == null) {
                RankedFusion.perConversationCap(limit + offset)
            } else {
                limit + offset
            },
            maxLimit = Int.MAX_VALUE,
        )
        val page = selected.drop(offset).take(limit)
        return HistorySearchOutcome(
            results = page,
            semanticState = state,
            hasMore = selected.size > offset + page.size,
            semanticDetail = detail,
            totalCandidates = selected.size,
        )
    }

    /** 查询向量 + 向量召回 + 回主库核对 */
    private suspend fun recallByEmbedding(
        keyword: String,
        target: EmbeddingTarget,
        assistantId: Uuid?,
        currentConversationId: Uuid?,
        focusConversationId: Uuid?,
        role: MessageRole?,
        fetchLimit: Int,
        excludeConversationIds: Set<String>,
        minSimilarity: Double,
    ): RecallResult {
        val queryText = keyword
        // 缓存 key 必须带上端点指纹（端点 + 自定义 headers/body）而不只是模型 id：
        // 同一模型配不同参数就是不同空间，维度碰巧一样时不会报错，只会静默给出错结果
        val cacheKey = "${target.fingerprint}|$queryText"
        val cached = queryEmbeddingCache[cacheKey]
        val embedding = cached ?: try {
            withTimeoutOrNull(QUERY_EMBEDDING_TIMEOUT_MS) {
                embeddingClient.embed(target, listOf(queryText)).firstOrNull()
            } ?: return RecallResult(
                emptyList(),
                SemanticState.TIMEOUT,
                "查询向量超过 ${QUERY_EMBEDDING_TIMEOUT_MS}ms 未返回"
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Log.w(TAG, "查询向量生成失败: ${error.message}")
            return RecallResult(
                emptyList(),
                SemanticState.FAILED,
                "查询向量生成失败：${error.message ?: error::class.simpleName}"
            )
        }
        if (cached == null && embedding.isNotEmpty()) {
            if (queryEmbeddingCache.size >= QUERY_EMBEDDING_CACHE_SIZE) queryEmbeddingCache.clear()
            queryEmbeddingCache[cacheKey] = embedding
        }
        if (embedding.isEmpty()) {
            return RecallResult(emptyList(), SemanticState.FAILED, "服务商返回了空的向量")
        }

        val store = runCatching { vectorIndexProvider.get() }.getOrNull()
            ?: return RecallResult(emptyList(), SemanticState.NOT_CONFIGURED, "向量索引打不开")
        // 库里的 profile 必须就是当前设置该用的那个，否则拿新的查询向量去搜旧库只会得到垃圾
        val expectedProfile = VectorProfile(
            provider = target.fingerprint,
            model = target.model.modelId,
            dimension = embedding.size
        )
        if (store.activeProfileId() != expectedProfile.id) {
            Log.i(TAG, "向量库 profile 与当前设置不一致，本次不做语义召回")
            return RecallResult(
                emptyList(),
                SemanticState.INDEXING,
                "索引还没按当前模型重建，需要等待后台重建完成"
            )
        }
        val hits = runCatching {
            store.search(
                embedding = embedding,
                limit = fetchLimit,
                minSimilarity = minSimilarity,
                filter = VectorFilter(
                    conversationId = focusConversationId?.toString(),
                    excludeConversationId = currentConversationId?.toString(),
                    excludeConversationIds = excludeConversationIds,
                    assistantIds = assistantId?.let { setOf(it.toString()) },
                    role = role?.name?.lowercase(),
                )
            )
        }.getOrElse {
            Log.w(TAG, "向量召回失败: ${it.message}")
            return RecallResult(
                emptyList(),
                SemanticState.FAILED,
                "向量召回失败：${it.message ?: it::class.simpleName}"
            )
        }
        val conversations = mutableMapOf<Uuid, Conversation?>()
        val matched = ArrayList<MessageSearchResult>(hits.size)
        for (hit in hits) {
            val conversationId = runCatching { Uuid.parse(hit.conversationId) }.getOrNull() ?: continue
            val conversation = if (conversations.containsKey(conversationId)) {
                conversations[conversationId]
            } else {
                getConversationById(conversationId).also { conversations[conversationId] = it }
            } ?: continue
            val node = conversation.messageNodes.firstOrNull { it.id.toString() == hit.nodeId } ?: continue
            val message = node.selectedMessageOrNull() ?: continue
            // 回主库核对：消息还在、角色对得上、确实是选中的分支、内容哈希没变
            if (message.id.toString() != hit.messageId) continue
            if (role != null && message.role != role) continue
            val text = IndexableText.extract(message) ?: continue
            if (hit.endOffset > text.length || hit.startOffset >= hit.endOffset) continue
            if (TextChunker.hashOf(text, hit.startOffset, hit.endOffset) != hit.contentHash) continue
            matched += MessageSearchResult(
                nodeId = hit.nodeId,
                messageId = hit.messageId,
                conversationId = hit.conversationId,
                title = conversation.title,
                updateAt = conversation.updateAt,
                snippet = buildVectorSnippet(text, hit.startOffset, hit.endOffset),
                role = message.role,
                createdAt = message.createdAt.toInstant(TimeZone.currentSystemDefault()).toJavaInstant(),
                isSelected = true,
                matchedBy = setOf(RecallSource.VECTOR),
            )
        }
        // 只有积压真的多到会改变结论时才提一句。当前会话本来就排除在召回之外（它就在上下文里），
        // 把它算进“还没建好”的名单里会让这句话变成每次都出现的噪声
        val backlog = excludeConversationIds.count { it != currentConversationId?.toString() }
        return if (backlog >= INDEXING_BACKLOG_THRESHOLD) {
            RecallResult(
                matched,
                SemanticState.INDEXING,
                "还有 $backlog 个会话在等待建索引，本次结果不含它们"
            )
        } else {
            RecallResult(matched, SemanticState.OK, null)
        }
    }

    /** 向量召回的结果 + 状态 + 失败原因（给模型看，不展示在界面上） */
    private data class RecallResult(
        val results: List<MessageSearchResult>,
        val state: SemanticState,
        val detail: String?,
    )

    private fun buildVectorSnippet(text: String, start: Int, end: Int): String {
        val from = (start - 40).coerceAtLeast(0)
        val to = (end + 40).coerceAtMost(text.length)
        val prefix = if (from > 0) "..." else ""
        val suffix = if (to < text.length) "..." else ""
        return prefix + text.substring(from, to).replace(Regex("\\s+"), " ").trim() + suffix
    }

    private fun historyFetchLimit(limit: Int): Int =
        (limit * 8).coerceAtLeast(80).coerceAtMost(MAX_HISTORY_SEARCH_FETCH_LIMIT * 4)

    private fun HistorySearchRoute.toRecallSource(): RecallSource = when (this) {
        HistorySearchRoute.RAW -> RecallSource.RAW
        HistorySearchRoute.SEGMENT -> RecallSource.SEGMENT
        HistorySearchRoute.TOKEN -> RecallSource.TOKEN
    }

    suspend fun readAssistantHistory(
        refs: List<String>,
        assistantId: Uuid,
        currentConversationId: Uuid,
        before: Int = DEFAULT_HISTORY_READ_BEFORE,
        after: Int = DEFAULT_HISTORY_READ_AFTER,
    ): List<ConversationHistoryReadResult> {
        val parsedRefs = normalizeHistoryReadRefs(refs).mapNotNull(::parseConversationHistoryRef)
        if (parsedRefs.isEmpty()) return emptyList()
        val normalizedBefore = normalizeHistoryReadBefore(before)
        val normalizedAfter = normalizeHistoryReadAfter(after)
        val conversations = mutableMapOf<Uuid, Conversation>()
        parsedRefs.map { it.conversationId }.distinct().forEach { id ->
            if (id != currentConversationId) {
                getConversationById(id)?.takeIf { it.assistantId == assistantId }?.let { conversations[id] = it }
            }
        }
        return parsedRefs.mapNotNull { ref ->
            val conversation = conversations[ref.conversationId] ?: return@mapNotNull null
            val nodeIndex = conversation.messageNodes.indexOfFirst { it.id == ref.nodeId }
            if (nodeIndex < 0 || conversation.messageNodes[nodeIndex].currentMessage.id != ref.messageId) {
                return@mapNotNull null
            }
            val start = (nodeIndex - normalizedBefore).coerceAtLeast(0)
            val end = (nodeIndex + normalizedAfter).coerceAtMost(conversation.messageNodes.lastIndex)
            val messages = conversation.messageNodes.subList(start, end + 1).mapIndexedNotNull { offset, node ->
                val message = node.currentMessage
                val text = message.toText().trim()
                if (text.isBlank()) {
                    null
                } else {
                    ConversationHistoryReadMessage(
                        role = message.role,
                        createdAt = message.createdAt.toString(),
                        text = text,
                        isMatch = start + offset == nodeIndex,
                    )
                }
            }
            if (messages.none { it.isMatch }) return@mapNotNull null
            ConversationHistoryReadResult(ref.toString(), conversation.id, conversation.title, messages)
        }
    }

    /**
     * 写索引任务。必须和会话写入/删除在同一个事务里调用，否则会漏任务。
     *
     * 版本号按会话单调递增，worker 写向量前会复核，期间被改过就放弃这一轮。
     */
    private suspend fun enqueueIndexTask(conversationId: String, op: IndexTaskOp) {
        val currentVersion = conversationIndexTaskDao.versionOf(conversationId) ?: 0L
        conversationIndexTaskDao.upsert(
            ConversationIndexTaskEntity(
                conversationId = conversationId,
                op = op.name,
                version = currentVersion + 1,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /** 后台重建向量索引时枚举全部会话 id */
    suspend fun getAllConversationIds(): List<String> = conversationDAO.getAllIds()

    /** 索引重建进度，界面和工具结果据此报告"仍在建" */
    data class IndexBuildState(
        val running: Boolean = false,
        val current: Int = 0,
        val total: Int = 0,
    )

    private val _ftsRebuildState = MutableStateFlow(IndexBuildState())
    val ftsRebuildState: StateFlow<IndexBuildState> = _ftsRebuildState.asStateFlow()

    /**
     * 文本提取规则升级后，旧 FTS 里可能还留着工具输出。
     *
     * 版本不一致就清空并后台重建；重建期间搜索仍能返回已完成的部分，并通过 [ftsRebuildState] 报状态。
     */
    suspend fun ensureFtsIndexUpToDate() = withContext(Dispatchers.IO) {
        val stored = messageFtsManager.storedExtractVersion()
        if (stored == IndexableText.EXTRACT_VERSION) return@withContext
        _ftsRebuildState.value = IndexBuildState(running = true)
        try {
            rebuildAllIndexes { current, total ->
                _ftsRebuildState.value = IndexBuildState(running = true, current = current, total = total)
            }
            messageFtsManager.setExtractVersion(IndexableText.EXTRACT_VERSION)
        } finally {
            _ftsRebuildState.value = IndexBuildState()
        }
    }

    suspend fun rebuildAllIndexes(onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }) {
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
    }

    fun conversationToConversationEntity(conversation: Conversation): ConversationEntity {
        require(conversation.messageNodes.none { it.messages.any { message -> message.hasBase64Part() } })
        return ConversationEntity(
            id = conversation.id.toString(),
            title = conversation.title,
            nodes = "[]", // nodes 现在存储在单独的表中
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
            config = conversation.config?.let { JsonInstant.encodeToString(it) } ?: "",
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
            config = conversationEntity.config.ifEmpty { null }?.let { JsonInstant.decodeFromString(it) },
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

    suspend fun updatePinStatus(conversationId: Uuid, isPinned: Boolean) {
        conversationDAO.updatePinStatus(
            id = conversationId.toString(),
            isPinned = isPinned,
        )
    }

    suspend fun updateConversationAssistant(conversationId: Uuid, assistantId: Uuid) {
        // 助手归属变了，向量行里的 assistant_id 也要跟着变，所以和写入放在同一个事务里重新排队
        database.withTransaction {
            conversationDAO.updateAssistantId(conversationId.toString(), assistantId.toString())
            enqueueIndexTask(conversationId.toString(), IndexTaskOp.UPSERT)
        }
        indexTaskScheduler.schedule()
    }

    /**
     * 只更新会话持有的配置（含注入绑定和工作目录），不重写消息节点。
     */
    suspend fun updateConversationConfig(conversation: Conversation) {
        conversationDAO.updateConfig(
            id = conversation.id.toString(),
            config = conversation.config?.let { JsonInstant.encodeToString(it) } ?: "",
            modeInjectionIds = JsonInstant.encodeToString(conversation.modeInjectionIds),
            lorebookIds = JsonInstant.encodeToString(conversation.lorebookIds),
            workspaceCwd = conversation.workspaceCwd ?: "",
        )
    }

    suspend fun updateTitle(conversationId: Uuid, title: String) {
        conversationDAO.updateTitle(conversationId.toString(), title)
    }

    suspend fun updateCustomSystemPrompt(conversationId: Uuid, customSystemPrompt: String?) {
        conversationDAO.updateCustomSystemPrompt(conversationId.toString(), customSystemPrompt ?: "")
    }

    suspend fun updateChatSuggestions(conversationId: Uuid, chatSuggestions: List<String>) {
        conversationDAO.updateChatSuggestions(
            id = conversationId.toString(),
            chatSuggestions = JsonInstant.encodeToString(chatSuggestions),
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
                } catch (e: CancellationException) {
                    // CancellationException 是 IllegalStateException 的子类，必须先重新抛出，否则取消后会在事务内无限循环
                    throw e
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
