package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.EmbeddingIndexSetting
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.ui.components.RikkaConfirmDialog
import me.rerere.ui.toast.ToastType
import org.koin.androidx.compose.koinViewModel

/**
 * 语义检索设置页。
 *
 * 隐私层面要说清楚：开启后聊天正文会发到所选模型的服务商去算向量。
 * 另外「重建」和「换模型」都要重算全部历史，是真花钱的操作，所以都加确认。
 */
@Composable
fun SettingSemanticIndexPage(vm: SemanticIndexVM = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val toaster = LocalToaster.current
    var showRebuildConfirm by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    fun notify(text: String, type: ToastType = ToastType.Normal) = toaster.show(text, type)

    // 提示文案在 composable 作用域取好，确认框的回调里不能再调 stringResource
    val msgCleared = stringResource(R.string.setting_semantic_cleared)
    val msgRebuildStarted = stringResource(R.string.setting_semantic_rebuild_started, state.conversationCount)
    val msgRetryStarted = stringResource(R.string.setting_semantic_retry_started)

    RikkaConfirmDialog(
        show = showRebuildConfirm,
        title = stringResource(R.string.setting_semantic_rebuild),
        text = {
            Text(
                stringResource(
                    R.string.setting_semantic_rebuild_confirm,
                    state.conversationCount,
                )
            )
        },
        confirmText = stringResource(R.string.setting_semantic_rebuild_confirm_action),
        dismissText = stringResource(R.string.setting_semantic_cancel),
        onConfirm = {
            showRebuildConfirm = false
            vm.rebuild()
            notify(msgRebuildStarted, ToastType.Info)
        },
        onDismiss = { showRebuildConfirm = false },
    )

    RikkaConfirmDialog(
        show = showClearConfirm,
        title = stringResource(R.string.setting_semantic_clear),
        text = { Text(stringResource(R.string.setting_semantic_clear_confirm)) },
        confirmText = stringResource(R.string.setting_semantic_clear),
        dismissText = stringResource(R.string.setting_semantic_cancel),
        onConfirm = {
            showClearConfirm = false
            vm.clear()
            notify(msgCleared, ToastType.Success)
        },
        onDismiss = { showClearConfirm = false },
    )

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_semantic_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                CardGroup(title = { Text(stringResource(R.string.setting_semantic_switch_section)) }) {
                    switchItem(
                        checked = state.enabled,
                        onCheckedChange = { vm.setEnabled(it) },
                        headlineContent = { Text(stringResource(R.string.setting_semantic_enable)) },
                        supportingContent = {
                            Text(stringResource(R.string.setting_semantic_enable_desc))
                        },
                    )
                    // 阈值是模型相关的：不同模型、不同语言的分数分布差很多，只能让用户在自己的模型上调
                    if (state.enabled) {
                        formItem(
                            headlineContent = { Text(stringResource(R.string.setting_semantic_similarity)) },
                            supportingContent = {
                                Text(stringResource(R.string.setting_semantic_similarity_desc))
                            },
                            trailingContent = {
                                CardGroupValue("%.2f".format(state.minSimilarity))
                            },
                        ) {
                            Slider(
                                value = state.minSimilarity.toFloat(),
                                onValueChange = { vm.setMinSimilarity(it.toDouble()) },
                                valueRange = EmbeddingIndexSetting.MIN_SIMILARITY_LOWER_BOUND.toFloat()..
                                    EmbeddingIndexSetting.MIN_SIMILARITY_UPPER_BOUND.toFloat(),
                                // 按常数算出格数，别再手写一个和范围脱钩的数字
                                steps = (
                                    (EmbeddingIndexSetting.MIN_SIMILARITY_UPPER_BOUND -
                                        EmbeddingIndexSetting.MIN_SIMILARITY_LOWER_BOUND) /
                                        EmbeddingIndexSetting.MIN_SIMILARITY_STEP
                                    ).toInt() - 1,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            item {
                CardGroup(title = { Text(stringResource(R.string.setting_semantic_status_section)) }) {
                    item(
                        headlineContent = {
                            Text(
                                stringResource(
                                    R.string.setting_semantic_indexed,
                                    state.chunkCount,
                                    state.conversationCount,
                                )
                            )
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    stringResource(
                                        R.string.setting_semantic_cache_size,
                                        formatBytes(state.cacheSizeBytes),
                                    )
                                )
                                Text(
                                    stringResource(
                                        R.string.setting_semantic_pending,
                                        state.pendingConversations,
                                    )
                                )
                                if (state.workerRunning) {
                                    Text(
                                        stringResource(
                                            R.string.setting_semantic_progress,
                                            state.workerProcessed,
                                            state.workerTotal,
                                        ),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                if (state.ftsRebuilding) {
                                    Text(
                                        stringResource(
                                            R.string.setting_semantic_fts_progress,
                                            state.ftsProgress,
                                            state.ftsTotal,
                                        )
                                    )
                                }
                                state.lastError?.let { error ->
                                    Text(
                                        text = stringResource(R.string.setting_semantic_last_error, error),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    )
                    item(
                        onClick = {
                            vm.retryNow()
                            notify(msgRetryStarted, ToastType.Info)
                        },
                        headlineContent = { Text(stringResource(R.string.setting_semantic_retry)) },
                        supportingContent = { Text(stringResource(R.string.setting_semantic_retry_desc)) },
                    )
                    item(
                        onClick = { showRebuildConfirm = true },
                        headlineContent = { Text(stringResource(R.string.setting_semantic_rebuild)) },
                        supportingContent = { Text(stringResource(R.string.setting_semantic_rebuild_desc)) },
                    )
                    item(
                        onClick = { showClearConfirm = true },
                        headlineContent = { Text(stringResource(R.string.setting_semantic_clear)) },
                        supportingContent = { Text(stringResource(R.string.setting_semantic_clear_desc)) },
                    )
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
