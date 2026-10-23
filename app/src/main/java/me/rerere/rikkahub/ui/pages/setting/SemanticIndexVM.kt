package me.rerere.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.index.ConversationIndexer
import me.rerere.rikkahub.data.index.IndexTaskScheduler
import me.rerere.rikkahub.data.index.VectorIndexProvider
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlin.uuid.Uuid

/** 语义索引页的状态 */
data class SemanticIndexUiState(
    val enabled: Boolean = false,
    val modelId: Uuid? = null,
    val instruction: String = "",
    val chunkCount: Int = 0,
    val conversationCount: Int = 0,
    val cacheSizeBytes: Long = 0L,
    val pendingConversations: Int = 0,
    val ftsRebuilding: Boolean = false,
    val ftsProgress: Int = 0,
    val ftsTotal: Int = 0,
    val busy: Boolean = false,
)

/**
 * 语义索引设置页。
 *
 * 可选的模型只有一个条件：OpenAI 兼容 provider 里标了 EMBEDDING 的模型，
 * 别的 provider 的模型类型标记不代表它支持 /embeddings。
 */
class SemanticIndexVM(
    private val settingsStore: SettingsStore,
    private val vectorIndexProvider: VectorIndexProvider,
    private val conversationIndexer: ConversationIndexer,
    private val conversationRepository: ConversationRepository,
    private val indexTaskScheduler: IndexTaskScheduler,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow

    private val _state = MutableStateFlow(SemanticIndexUiState())
    val state: StateFlow<SemanticIndexUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsStore.settingsFlow.collect { settings ->
                _state.value = _state.value.copy(
                    enabled = settings.embeddingIndex.enabled,
                    modelId = settings.embeddingIndex.modelId,
                    instruction = settings.embeddingIndex.instruction,
                    ftsRebuilding = conversationRepository.ftsRebuildState.value.running,
                )
            }
        }
        refresh()
    }

    fun embeddingModels(settings: Settings): List<Pair<ProviderSetting.OpenAI, Model>> =
        settings.providers.filterIsInstance<ProviderSetting.OpenAI>()
            .flatMap { provider -> provider.models.filter { it.type == ModelType.EMBEDDING }.map { provider to it } }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(embeddingIndex = it.embeddingIndex.copy(enabled = enabled)) }
            if (enabled) {
                // 启用后把已有会话都排进队列，后台慢慢建
                conversationIndexer.rebuildAll()
                indexTaskScheduler.schedule(debounceSeconds = 0L)
            }
            refresh()
        }
    }

    fun setModel(modelId: Uuid) {
        viewModelScope.launch {
            // 换模型等于换向量空间：清空重建，不做迁移
            settingsStore.update { it.copy(embeddingIndex = it.embeddingIndex.copy(modelId = modelId)) }
            conversationIndexer.rebuildAll()
            indexTaskScheduler.schedule(debounceSeconds = 0L)
            refresh()
        }
    }

    fun setInstruction(instruction: String) {
        viewModelScope.launch {
            settingsStore.update { it.copy(embeddingIndex = it.embeddingIndex.copy(instruction = instruction)) }
        }
    }

    fun rebuild() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            conversationIndexer.rebuildAll()
            indexTaskScheduler.schedule(debounceSeconds = 0L)
            refresh()
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun clear() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            conversationIndexer.clearAll()
            refresh()
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun retryNow() {
        indexTaskScheduler.schedule(debounceSeconds = 0L)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val stats = vectorIndexProvider.statsOrNull()
            val pending = runCatching { conversationIndexer.pendingCount() }.getOrDefault(0)
            val ftsState = conversationRepository.ftsRebuildState.value
            _state.value = _state.value.copy(
                chunkCount = stats?.chunkCount ?: 0,
                conversationCount = stats?.conversationCount ?: 0,
                cacheSizeBytes = stats?.fileSizeBytes ?: 0L,
                pendingConversations = pending,
                ftsRebuilding = ftsState.running,
                ftsProgress = ftsState.current,
                ftsTotal = ftsState.total,
            )
        }
    }
}
