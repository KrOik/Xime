package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.speech.doutype.DouTypeCredentialManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DouTypeSettingsContent(onBack: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(SettingsPreferences.isDouTypeEnabled(context)) }
    var status by remember { mutableStateOf(DouTypeCredentialManager(context).status()) }
    Scaffold(topBar = { TopAppBar(title = { Text("DouType 在线 API") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) { Text("启用 DouType", style = MaterialTheme.typography.titleMedium); Text("状态：$status", style = MaterialTheme.typography.bodySmall) }
                Switch(checked = enabled, onCheckedChange = { value -> enabled = value; SettingsPreferences.setDouTypeEnabled(context, value); if (value) { SettingsPreferences.setSttProvider(context, "doutype"); SettingsPreferences.setSttUseLocal(context, false) } })
            }
            Text("凭据会在首次启用时自动注册设备并获取，应用会在过期前自动刷新。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DouTypeOption("自动标点", SettingsPreferences.isDouTypePunctuationEnabled(context)) { SettingsPreferences.setDouTypePunctuationEnabled(context, it) }
            DouTypeOption("两遍识别纠错", SettingsPreferences.isDouTypeTwoPassEnabled(context)) { SettingsPreferences.setDouTypeTwoPassEnabled(context, it) }
            DouTypeOption("三遍离线纠错", SettingsPreferences.isDouTypeThreePassEnabled(context)) { SettingsPreferences.setDouTypeThreePassEnabled(context, it) }
            DouTypeOption("语音拒识", SettingsPreferences.isDouTypeSpeechRejectionEnabled(context)) { SettingsPreferences.setDouTypeSpeechRejectionEnabled(context, it) }
            DouTypeOption("句子分段", SettingsPreferences.isDouTypeSentenceSegEnabled(context)) { SettingsPreferences.setDouTypeSentenceSegEnabled(context, it) }
            DouTypeOption("时间戳", SettingsPreferences.isDouTypeTimestampEnabled(context)) { SettingsPreferences.setDouTypeTimestampEnabled(context, it) }
        }
    }
}

@Composable
private fun DouTypeOption(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(title); Switch(checked = checked, onCheckedChange = onChange) }
}
