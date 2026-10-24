package me.rerere.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.index.ConversationIndexer
import me.rerere.rikkahub.data.index.IndexTaskScheduler
import me.rerere.rikkahub.data.index.VectorIndexProvider
import kotlin.uuid.Uuid

class SettingVM(
    private val settingsStore: SettingsStore,
    private val mcpManager: McpManager,
    private val conversationIndexer: ConversationIndexer,
    private val vectorIndexProvider: VectorIndexProvider,
    private val indexTaskScheduler: IndexTaskScheduler,
) :
    ViewModel() {
    // 设置在应用启动时就读进了内存，初始值直接取当前值；
    // 用占位值的话，每次进设置页第一帧都是默认设置，随后才跳成真实数据
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = settingsStore.settingsFlow.value.takeUnless { it.init }
                ?: Settings(init = true, providers = emptyList()),
        )

    val launchCount: StateFlow<Int> = settingsStore.launchCountFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    /** 换向量模型前等待用户确认；null 表示没有待确认的改动 */
    private val _pendingEmbeddingModel = MutableStateFlow<Uuid?>(null)
    val pendingEmbeddingModel: StateFlow<Uuid?> = _pendingEmbeddingModel.asStateFlow()

    fun updateSettings(fn: (Settings) -> Settings) {
        viewModelScope.launch {
            settingsStore.update(fn)
        }
    }

    /**
     * 选中一个向量模型。
     *
     * 同一个模型：什么都不做，连写都不写，避免无意间触发重建。
     * 换了模型：只有库里确实有向量时才算「换空间」，需要确认后重建；
     * 没有索引过任何东西的话直接换，没什么可重建的。
     */
    fun onEmbeddingModelSelected(modelId: Uuid) {
        viewModelScope.launch {
            if (settingsStore.settingsFlow.value.embeddingIndex.modelId == modelId) return@launch
            val hasIndexedVectors = runCatching { vectorIndexProvider.statsOrNull()?.chunkCount ?: 0 }
                .getOrDefault(0) > 0
            if (hasIndexedVectors) {
                _pendingEmbeddingModel.value = modelId
            } else {
                applyEmbeddingModel(modelId)
            }
        }
    }

    fun confirmPendingEmbeddingModel() {
        val modelId = _pendingEmbeddingModel.value ?: return
        _pendingEmbeddingModel.value = null
        viewModelScope.launch { applyEmbeddingModel(modelId) }
    }

    fun dismissPendingEmbeddingModel() {
        _pendingEmbeddingModel.value = null
    }

    private suspend fun applyEmbeddingModel(modelId: Uuid) {
        settingsStore.update { it.copy(embeddingIndex = it.embeddingIndex.copy(modelId = modelId)) }
        // 换模型等于换向量空间：旧向量一律作废，后台按新模型重算
        conversationIndexer.rebuildAll()
        indexTaskScheduler.schedule(debounceSeconds = 0L)
    }
}
