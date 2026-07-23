package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.speech.doutype.DouTypeCredentialManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DouTypeSettingsContent(onBack: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(SettingsPreferences.isDouTypeEnabled(context)) }
    var status by remember { mutableStateOf(DouTypeCredentialManager(context).status()) }

    var punctuation by remember { mutableStateOf(SettingsPreferences.isDouTypePunctuationEnabled(context)) }
    var twoPass by remember { mutableStateOf(SettingsPreferences.isDouTypeTwoPassEnabled(context)) }
    var threePass by remember { mutableStateOf(SettingsPreferences.isDouTypeThreePassEnabled(context)) }
    var twoPassRetry by remember { mutableStateOf(SettingsPreferences.isDouTypeTwoPassRetryEnabled(context)) }
    var strongDdc by remember { mutableStateOf(SettingsPreferences.isDouTypeStrongDdcEnabled(context)) }
    var speechRejection by remember { mutableStateOf(SettingsPreferences.isDouTypeSpeechRejectionEnabled(context)) }
    var sentenceSeg by remember { mutableStateOf(SettingsPreferences.isDouTypeSentenceSegEnabled(context)) }
    var textSeg by remember { mutableStateOf(SettingsPreferences.isDouTypeTextSegEnabled(context)) }
    var timestamp by remember { mutableStateOf(SettingsPreferences.isDouTypeTimestampEnabled(context)) }
    var showUtterances by remember { mutableStateOf(SettingsPreferences.isDouTypeShowUtterancesEnabled(context)) }
    var printChinese by remember { mutableStateOf(SettingsPreferences.isDouTypePrintChineseEnabled(context)) }
    var removeSpacesNum by remember { mutableStateOf(SettingsPreferences.isDouTypeRemoveSpacesEnabled(context)) }
    var removeSpacesEng by remember { mutableStateOf(SettingsPreferences.isDouTypeRemoveSpacesEngEnabled(context)) }
    var disableUserWords by remember { mutableStateOf(SettingsPreferences.isDouTypeDisableUserWordsEnabled(context)) }
    var language by remember { mutableStateOf(SettingsPreferences.getDouTypeLanguage(context)) }
    var resultType by remember { mutableStateOf(SettingsPreferences.getDouTypeResultType(context)) }
    var inputMode by remember { mutableStateOf(SettingsPreferences.getDouTypeInputMode(context)) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("DouType 在线 API") },
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = padding.calculateBottomPadding() + 8.dp
            )
        ) {
            item {
                SettingsSection(title = "服务") {
                    DouTypeSwitchRow(
                        title = "启用 DouType",
                        subtitle = "状态：$status",
                        checked = enabled,
                        onCheckedChange = { value ->
                            enabled = value
                            SettingsPreferences.setDouTypeEnabled(context, value)
                            if (value) {
                                SettingsPreferences.setSttProvider(context, "doutype")
                                SettingsPreferences.setSttUseLocal(context, false)
                                SettingsPreferences.setSttEnabled(context, true)
                                runCatching {
                                    DouTypeCredentialManager(context).ensure()
                                }
                                status = DouTypeCredentialManager(context).status()
                            }
                        }
                    )
                    Text(
                        text = "凭据在首次启用时自动注册并获取，过期前自动刷新。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            item {
                SettingsSection(title = "识别质量") {
                    DouTypeSwitchRow(
                        title = "自动标点",
                        subtitle = "识别结果自动补全标点",
                        checked = punctuation,
                        onCheckedChange = {
                            punctuation = it
                            SettingsPreferences.setDouTypePunctuationEnabled(context, it)
                        }
                    )
                    DouTypeSwitchRow(
                        title = "两遍识别纠错",
                        subtitle = "enable_asr_twopass",
                        checked = twoPass,
                        onCheckedChange = {
                            twoPass = it
                            SettingsPreferences.setDouTypeTwoPassEnabled(context, it)
                        }
                    )
                    DouTypeSwitchRow(
                        title = "三遍离线纠错",
                        subtitle = "enable_asr_threepass",
                        checked = threePass,
                        onCheckedChange = {
                            threePass = it
                            SettingsPreferences.setDouTypeThreePassEnabled(context, it)
                        }
                    )
                    DouTypeSwitchRow(
                        title = "二路失败重试",
                        subtitle = "use_twopass_retry",
                        checked = twoPassRetry,
                        onCheckedChange = {
                            twoPassRetry = it
                            SettingsPreferences.setDouTypeTwoPassRetryEnabled(context, it)
                        }
                    )
                    DouTypeSwitchRow(
                        title = "强 DDC 纠错",
                        subtitle = "strong_ddc",
                        checked = strongDdc,
                        onCheckedChange = {
                            strongDdc = it
                            SettingsPreferences.setDouTypeStrongDdcEnabled(context, it)
                        }
                    )
                    DouTypeSwitchRow(
                        title = "语音拒识",
                        subtitle = "过滤无效/噪声片段",
                        checked = speechRejection,
                        onCheckedChange = {
                            speechRejection = it
                            SettingsPreferences.setDouTypeSpeechRejectionEnabled(context, it)
                        }
                    )
                }
            }

            item {
                SettingsSection(title = "文本处理") {
                    DouTypeSwitchRow(
                        title = "句子分段",
                        subtitle = "enable_sentence_seg",
                        checked = sentenceSeg,
                        onCheckedChange = {
                            sentenceSeg = it
                            SettingsPreferences.setDouTypeSentenceSegEnabled(context, it)
                        }
                    )
                    DouTypeSwitchRow(
                        title = "文本分段",
                        subtitle = "enable_text_seg",
                        checked = textSeg,
                        onCheckedChange = {
                            textSeg = it
                            SettingsPreferences.setDouTypeTextSegEnabled(context, it)
                        }
                    )
                    DouTypeSwitchRow(
                        title = "时间戳",
                        subtitle = "enable_timestamp",
                        checked = timestamp,
                        onCheckedChange = {
                            timestamp = it
                            SettingsPreferences.setDouTypeTimestampEnabled(context, it)
                        }
                    )
                    DouTypeSwitchRow(
                        title = "返回 utterance 明细",
                        subtitle = "show_utterances",
                        checked = showUtterances,
                        onCheckedChange = {
                            showUtterances = it
                            SettingsPreferences.setDouTypeShowUtterancesEnabled(context, it)
                        }
                    )
                    DouTypeSwitchRow(
                        title = "数字倾向中文",
                        subtitle = "enable_print_chinese",
                        checked = printChinese,
                        onCheckedChange = {
                            printChinese = it
                            SettingsPreferences.setDouTypePrintChineseEnabled(context, it)
                        }
                    )
                    DouTypeSwitchRow(
                        title = "去汉字-数字空格",
                        subtitle = "remove_space_between_han_num",
                        checked = removeSpacesNum,
                        onCheckedChange = {
                            removeSpacesNum = it
                            SettingsPreferences.setDouTypeRemoveSpacesEnabled(context, it)
                        }
                    )
                    DouTypeSwitchRow(
                        title = "去汉字-英文空格",
                        subtitle = "remove_space_between_han_eng",
                        checked = removeSpacesEng,
                        onCheckedChange = {
                            removeSpacesEng = it
                            SettingsPreferences.setDouTypeRemoveSpacesEngEnabled(context, it)
                        }
                    )
                    DouTypeSwitchRow(
                        title = "禁用用户热词",
                        subtitle = "disable_user_words",
                        checked = disableUserWords,
                        onCheckedChange = {
                            disableUserWords = it
                            SettingsPreferences.setDouTypeDisableUserWordsEnabled(context, it)
                        }
                    )
                }
            }

            item {
                SettingsSection(title = "会话参数") {
                    DouTypeChoiceRow(
                        title = "语言",
                        options = listOf(
                            "zh-CN" to "中文",
                            "en-US" to "英语",
                            "ja-JP" to "日语",
                            "ko-KR" to "韩语"
                        ),
                        selected = language,
                        onSelect = {
                            language = it
                            SettingsPreferences.setDouTypeLanguage(context, it)
                        }
                    )
                    DouTypeChoiceRow(
                        title = "结果类型",
                        options = listOf(
                            "full" to "完整",
                            "single" to "单句"
                        ),
                        selected = resultType,
                        onSelect = {
                            resultType = it
                            SettingsPreferences.setDouTypeResultType(context, it)
                        }
                    )
                    DouTypeChoiceRow(
                        title = "输入模式",
                        options = listOf(
                            "tool" to "工具",
                            "dictation" to "听写"
                        ),
                        selected = inputMode,
                        onSelect = {
                            inputMode = it
                            SettingsPreferences.setDouTypeInputMode(context, it)
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun DouTypeSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DouTypeChoiceRow(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(label) }
                )
            }
        }
    }
}
