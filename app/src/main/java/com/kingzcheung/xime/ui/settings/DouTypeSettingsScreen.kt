package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.SettingsPreferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DouTypeSettingsContent(onBack: () -> Unit) {
    val context = LocalContext.current
    var key by remember { mutableStateOf(SettingsPreferences.getDouTypeApiKey(context)) }
    var enabled by remember { mutableStateOf(SettingsPreferences.getSttProvider(context) == "doutype") }
    Scaffold(topBar = { TopAppBar(title = { Text("DouType 在线 API") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) { Text("启用 DouType", style = MaterialTheme.typography.titleMedium); Text("启用后会停用阿里百炼", style = MaterialTheme.typography.bodySmall) }
                Switch(checked = enabled, onCheckedChange = { value -> enabled = value; if (value) { SettingsPreferences.setSttProvider(context, "doutype"); SettingsPreferences.setSttUseLocal(context, false) } else if (SettingsPreferences.getSttProvider(context) == "doutype") SettingsPreferences.setSttProvider(context, "funasr") })
            }
            OutlinedTextField(value = key, onValueChange = { key = it }, modifier = Modifier.fillMaxWidth(), label = { Text("DouType Speech API Key") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            Button(onClick = { SettingsPreferences.setDouTypeApiKey(context, key.trim()) }, modifier = Modifier.fillMaxWidth()) { Text("保存") }
            Text("支持 16 kHz 单声道 PCM、流式识别、自动标点和多阶段纠错。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
