package com.kingzcheung.xime.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.speech.service.AsrServiceConfigValidator
import com.kingzcheung.xime.speech.service.AsrServiceCredentialStore
import com.kingzcheung.xime.speech.service.HttpAsrServiceClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface AsrServiceConnectionState {
    data object Idle : AsrServiceConnectionState
    data object Testing : AsrServiceConnectionState
    data class Success(val message: String) : AsrServiceConnectionState
    data class Error(val message: String) : AsrServiceConnectionState
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AsrServiceSettingsContent(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val credentialStore = remember(context) { AsrServiceCredentialStore(context) }
    val scope = rememberCoroutineScope()
    var serviceUrl by remember { mutableStateOf(SettingsPreferences.getAsrServiceUrl(context)) }
    var token by remember { mutableStateOf(credentialStore.getToken()) }
    var showToken by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var connectionState by remember { mutableStateOf<AsrServiceConnectionState>(AsrServiceConnectionState.Idle) }

    fun normalizedUrl(): String? = try {
        AsrServiceConfigValidator.normalizeHttpsUrl(serviceUrl).also {
            validationError = null
        }
    } catch (error: IllegalArgumentException) {
        validationError = error.message ?: "ASR 服务地址无效"
        null
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("自建 ASR 服务") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(padding)
                .imePadding()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SettingsSection(title = "服务配置") {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.CloudQueue, contentDescription = null)
                            Text("HTTPS 实时转写服务", style = MaterialTheme.typography.titleMedium)
                        }

                        OutlinedTextField(
                            value = serviceUrl,
                            onValueChange = {
                                serviceUrl = it
                                validationError = null
                                connectionState = AsrServiceConnectionState.Idle
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("服务地址") },
                            placeholder = { Text("https://asr.example.com") },
                            supportingText = {
                                Text(validationError ?: "仅允许 HTTPS；不要包含查询参数、账号或密码")
                            },
                            isError = validationError != null,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            shape = RoundedCornerShape(12.dp)
                        )

                        OutlinedTextField(
                            value = token,
                            onValueChange = {
                                token = it
                                connectionState = AsrServiceConnectionState.Idle
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Bearer token（可选）") },
                            supportingText = { Text("token 使用 Android Keystore 加密保存") },
                            singleLine = true,
                            visualTransformation = if (showToken) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = { showToken = !showToken }) {
                                    Icon(
                                        if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (showToken) "隐藏 token" else "显示 token"
                                    )
                                }
                            },
                            shape = RoundedCornerShape(12.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    val url = normalizedUrl() ?: return@Button
                                    SettingsPreferences.setAsrServiceUrl(context, url)
                                    credentialStore.setToken(token)
                                    SettingsPreferences.setSttUseLocal(context, false)
                                    SettingsPreferences.setSttProvider(context, "asr_service")
                                    serviceUrl = url
                                    token = token.trim()
                                    Toast.makeText(context, "ASR 服务配置已保存并启用", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("保存并启用")
                            }
                            OutlinedButton(
                                onClick = {
                                    token = ""
                                    credentialStore.clearToken()
                                    connectionState = AsrServiceConnectionState.Idle
                                    Toast.makeText(context, "已清除 token", Toast.LENGTH_SHORT).show()
                                },
                                enabled = token.isNotEmpty() || credentialStore.hasToken()
                            ) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("清除")
                            }
                        }
                    }
                }
            }

            item {
                SettingsSection(title = "连接测试") {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "测试会请求 GET /health，并确认服务返回 features.live=true。测试使用当前输入内容，不会自动保存。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = {
                                val url = normalizedUrl() ?: return@OutlinedButton
                                connectionState = AsrServiceConnectionState.Testing
                                scope.launch {
                                    connectionState = try {
                                        val health = withContext(Dispatchers.IO) {
                                            HttpAsrServiceClient(url, token.trim()).checkHealth()
                                        }
                                        when {
                                            !health.ok -> AsrServiceConnectionState.Error("服务健康检查未通过")
                                            !health.live -> AsrServiceConnectionState.Error("服务未启用实时转写能力")
                                            else -> AsrServiceConnectionState.Success(
                                                health.version.takeIf { it.isNotBlank() }
                                                    ?.let { "连接成功，服务版本 $it" }
                                                    ?: "连接成功，实时转写可用"
                                            )
                                        }
                                    } catch (error: Exception) {
                                        AsrServiceConnectionState.Error(error.message ?: "连接测试失败")
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = connectionState !is AsrServiceConnectionState.Testing
                        ) {
                            if (connectionState is AsrServiceConnectionState.Testing) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.HealthAndSafety, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(if (connectionState is AsrServiceConnectionState.Testing) "正在测试…" else "测试连接")
                        }

                        when (val state = connectionState) {
                            AsrServiceConnectionState.Idle,
                            AsrServiceConnectionState.Testing -> Unit
                            is AsrServiceConnectionState.Success -> ConnectionResultCard(
                                message = state.message,
                                success = true
                            )
                            is AsrServiceConnectionState.Error -> ConnectionResultCard(
                                message = state.message,
                                success = false
                            )
                        }
                    }
                }
            }

            item {
                SettingsSection(title = "接口要求") {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "GET /health：健康检查与实时能力声明",
                            "POST /v1/live/sessions：创建会话",
                            "POST /v1/live/sessions/{id}/audio：推送 16 kHz mono s16le PCM",
                            "GET /v1/live/sessions/{id}：获取中间结果",
                            "DELETE /v1/live/sessions/{id}：结束并获取最终文本"
                        ).forEach { requirement ->
                            Text(
                                text = requirement,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionResultCard(
    message: String,
    success: Boolean,
    modifier: Modifier = Modifier
) {
    val containerColor = if (success) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = if (success) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                if (success) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = contentColor
            )
            Text(message, color = contentColor, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
