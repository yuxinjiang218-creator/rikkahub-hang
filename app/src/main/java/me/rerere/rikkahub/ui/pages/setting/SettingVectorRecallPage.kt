package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.VectorRecallConfig
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.sync.vector.VectorRecallState
import me.rerere.rikkahub.data.sync.vector.VectorRecallSyncManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.uuid.Uuid
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
fun SettingVectorRecallPage(
    vm: SettingVM = koinViewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val vectorState: VectorRecallState = koinInject()
    val vectorSyncManager: VectorRecallSyncManager = koinInject()
    val conversationRepository: ConversationRepository = koinInject()
    val status by vectorState.status.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val config = settings.vectorRecallConfig
    val serverUrlError = config.serverUrl.isNotBlank() && config.serverUrl.toHttpUrlOrNull() == null
    val connectedToast = stringResource(R.string.setting_vector_recall_connected_toast)
    val failedToast = stringResource(R.string.setting_vector_recall_failed_toast)

    suspend fun syncAll() {
        val summaries = conversationRepository.getVectorRecallConversationSummaries()
        vectorSyncManager.syncAll(
            summaries = summaries,
            loadConversation = { id: Uuid -> conversationRepository.getConversationById(id) },
        )
    }

    LaunchedEffect(config.enabled) {
        if (config.enabled) {
            withContext(Dispatchers.IO) {
                syncAll()
            }
        }
    }

    fun save(newConfig: VectorRecallConfig) {
        vm.updateSettings(settings.copy(vectorRecallConfig = newConfig))
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_vector_recall_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = padding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("config") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = CustomColors.listItemColors.containerColor,
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .animateContentSize()
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    stringResource(R.string.setting_vector_recall_enable),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    stringResource(R.string.setting_vector_recall_enable_desc),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = config.enabled,
                                onCheckedChange = { save(config.copy(enabled = it)) },
                            )
                        }

                        OutlinedTextField(
                            value = config.serverUrl,
                            onValueChange = { save(config.copy(serverUrl = it.trim())) },
                            label = { Text(stringResource(R.string.setting_vector_recall_server_url)) },
                            singleLine = true,
                            isError = serverUrlError,
                            supportingText = {
                                if (serverUrlError) {
                                    Text(stringResource(R.string.setting_vector_recall_invalid_url))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OutlinedTextField(
                            value = config.username,
                            onValueChange = { save(config.copy(username = it.trim())) },
                            label = { Text(stringResource(R.string.setting_vector_recall_username)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        PasswordField(
                            value = config.password,
                            onValueChange = { save(config.copy(password = it)) },
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ConnectionStatusIndicator(
                                checking = status.checking,
                                ok = status.handshakeOk,
                                error = status.lastError,
                            )
                            Button(
                                enabled = !status.checking && config.serverUrl.toHttpUrlOrNull() != null,
                                onClick = {
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) {
                                            vectorSyncManager.handshake()
                                        }
                                        toaster.show(
                                            message = if (ok) connectedToast else failedToast,
                                            type = if (ok) ToastType.Success else ToastType.Error,
                                        )
                                    }
                                },
                            ) {
                                if (status.checking) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Text(stringResource(R.string.setting_vector_recall_test_connection))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.setting_vector_recall_password)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) HugeIcons.ViewOff else HugeIcons.View,
                    contentDescription = null,
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ConnectionStatusIndicator(
    checking: Boolean,
    ok: Boolean,
    error: String?,
) {
    val color = when {
        checking -> MaterialTheme.colorScheme.tertiary
        ok -> Color(0xFF2E7D32)
        else -> MaterialTheme.colorScheme.error
    }
    val text = when {
        checking -> stringResource(R.string.setting_vector_recall_status_checking)
        ok -> stringResource(R.string.setting_vector_recall_status_connected)
        error.isNullOrBlank() -> stringResource(R.string.setting_vector_recall_status_not_connected)
        else -> stringResource(R.string.setting_vector_recall_status_failed)
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
