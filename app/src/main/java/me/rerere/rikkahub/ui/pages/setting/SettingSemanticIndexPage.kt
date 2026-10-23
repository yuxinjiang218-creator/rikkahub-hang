package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

/**
 * 语义检索设置页。
 *
 * 说明写清楚：开启后聊天正文会发到所选模型的服务商去算向量，这是隐私面上的实质变化。
 */
@Composable
fun SettingSemanticIndexPage(vm: SemanticIndexVM = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val models = remember(settings.providers) { vm.embeddingModels(settings) }
    var instructionDraft by remember(state.instruction) { mutableStateOf(state.instruction) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("语义检索") },
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
                CardGroup(title = { Text("开关") }) {
                    item(
                        onClick = { vm.setEnabled(!state.enabled) },
                        headlineContent = { Text(if (state.enabled) "已开启语义检索" else "开启语义检索") },
                        supportingContent = {
                            Text(
                                "开启后，聊天正文会被发送到所选模型的服务商来生成向量；" +
                                    "关闭时只用本地的关键词搜索，不发出任何内容。"
                            )
                        },
                        trailingContent = {
                            TextButton(onClick = { vm.setEnabled(!state.enabled) }) {
                                Text(if (state.enabled) "关闭" else "开启")
                            }
                        }
                    )
                }
            }

            item {
                CardGroup(title = { Text("Embedding 模型") }) {
                    if (models.isEmpty()) {
                        item(
                            headlineContent = { Text("没有可用的向量模型") },
                            supportingContent = {
                                Text("请先在服务商里添加一个 OpenAI 兼容的 Embedding 模型（类型选 EMBEDDING）。")
                            }
                        )
                    } else {
                        models.forEach { (provider, model) ->
                            val selected = state.modelId == model.id
                            item(
                                onClick = { vm.setModel(model.id) },
                                headlineContent = { Text(model.displayName.ifBlank { model.modelId }) },
                                supportingContent = {
                                    Text(
                                        "${provider.name} · ${model.modelId}" +
                                            if (selected) " · 当前使用" else ""
                                    )
                                }
                            )
                        }
                    }
                }
            }

            item {
                CardGroup(title = { Text("查询指令前缀") }) {
                    formItem(
                        headlineContent = { Text("指令（可留空）") },
                        supportingContent = {
                            Text("只作用于查询，不改文档侧；换了它等于换了向量空间，会触发重建。")
                        },
                        content = {
                            OutlinedTextField(
                                value = instructionDraft,
                                onValueChange = { instructionDraft = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                label = { Text("Instruct: ...") }
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { vm.setInstruction(instructionDraft) }) {
                                    Text("保存")
                                }
                            }
                        }
                    )
                }
            }

            item {
                CardGroup(title = { Text("索引状态") }) {
                    item(
                        headlineContent = {
                            Text("已索引 ${state.chunkCount} 块 / ${state.conversationCount} 个会话")
                        },
                        supportingContent = {
                            Column {
                                Text("缓存占用 ${formatBytes(state.cacheSizeBytes)}")
                                Text("待处理任务 ${state.pendingConversations} 个会话")
                                if (state.ftsRebuilding) {
                                    Text("关键词索引正在重建：${state.ftsProgress}/${state.ftsTotal}")
                                }
                            }
                        }
                    )
                    item(
                        onClick = { vm.retryNow() },
                        headlineContent = { Text("立即重试") },
                        supportingContent = { Text("不等防抖，马上开始处理待办任务") }
                    )
                    item(
                        onClick = { vm.rebuild() },
                        headlineContent = { Text("重建索引") },
                        supportingContent = { Text("清空后重新生成全部向量，重建期间仍可用关键词搜索") }
                    )
                    item(
                        onClick = { vm.clear() },
                        headlineContent = { Text("清理索引") },
                        supportingContent = { Text("只删除本地向量缓存，不删除任何聊天记录") }
                    )
                }
            }

            if (state.busy) {
                item {
                    Text("处理中…", style = MaterialTheme.typography.bodySmall)
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
