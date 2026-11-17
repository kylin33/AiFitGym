package io.github.kylin33.aifitgym.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.kylin33.aifitgym.data.ai.AI_PRESETS
import io.github.kylin33.aifitgym.data.ai.AiProfile
import io.github.kylin33.aifitgym.data.ai.AiProviderPreset
import io.github.kylin33.aifitgym.data.ai.EQUIPMENT_OPTIONS
import io.github.kylin33.aifitgym.data.ai.EXPERIENCE_OPTIONS
import io.github.kylin33.aifitgym.data.ai.FOCUS_OPTIONS
import io.github.kylin33.aifitgym.data.ai.ProfileField
import io.github.kylin33.aifitgym.data.ai.AiSettingsState
import io.github.kylin33.aifitgym.data.ai.EndpointSecurity
import io.github.kylin33.aifitgym.data.ai.classifyEndpoint

/**
 * AI 教练设置。
 *
 * 只有三个必填项（Base URL / Key / 模型），因为所有服务商都被统一到
 * OpenAI 的 Chat Completions 协议上；剩下的「训练目标」是给提示词补上下文用的，
 * 不填也能聊，填了排出来的计划才贴近本人。
 */
@Composable
fun AiSettingsScreen(
    state: AiSettingsState,
    test: AiTestState,
    onBack: () -> Unit,
    onPreset: (AiProviderPreset) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
    onTest: () -> Unit,
    onGoalChange: (String) -> Unit,
    onLevelChange: (String) -> Unit,
    onDaysChange: (Int) -> Unit,
    onMinutesChange: (Int) -> Unit,
    onNotesChange: (String) -> Unit,
    onLimitationsChange: (String) -> Unit,
    onExperienceChange: (String) -> Unit,
    onToggleEquipment: (String) -> Unit,
    onToggleFocus: (String) -> Unit,
    onSpaceChange: (String) -> Unit,
    onToggleTrainingDay: (Int) -> Unit,
    onAutoCoachChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val endpoint = state.preferences.endpoint
    val profile = state.preferences.profile

    var keyDraft by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    // 开启全程教练前先弹一次说明：计划交给 AI、会花 token，这两件事必须点之前就知道
    var showAutoCoachConfirm by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item(key = "bar") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text(
                    text = "AI 教练设置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        item(key = "intro") {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "任意 OpenAI 兼容服务都能用：官方 API、中转站、本地 Ollama。" +
                    "只有你问问题、或点「让教练复盘」时才会把训练数据发给这个地址，App 本身不上传任何东西。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
        }

        item(key = "preset_label") { SectionLabel("服务商") }
        item(key = "presets") {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AI_PRESETS.take(3).forEach { preset ->
                        PresetChip(
                            preset = preset,
                            selected = endpoint.baseUrl == preset.baseUrl && preset.baseUrl.isNotBlank(),
                            onClick = {
                                onPreset(preset)
                                keyDraft = ""
                            },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AI_PRESETS.drop(3).forEach { preset ->
                        PresetChip(
                            preset = preset,
                            selected = endpoint.baseUrl == preset.baseUrl && preset.baseUrl.isNotBlank(),
                            onClick = { onPreset(preset) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                val hint = AI_PRESETS.firstOrNull { it.baseUrl == endpoint.baseUrl }?.hint
                if (hint != null) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }

        item(key = "base_url") {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = endpoint.baseUrl,
                onValueChange = onBaseUrlChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Base URL") },
                placeholder = { Text("https://api.deepseek.com/v1") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
                supportingText = { Text("要带版本段，一般以 /v1 结尾") },
            )
        }

        // 明文 HTTP 提示跟着输入实时变化：http:// 一敲下去就能看到，不用等到保存。
        // 放行明文是 network_security_config 的决定（用户可能填任何地址），
        // 但放行不等于默认用户知情，所以这里把代价写在地址输入框正下方。
        item(key = "cleartext") {
            when (classifyEndpoint(endpoint.baseUrl)) {
                EndpointSecurity.LocalCleartext -> CleartextNotice(publicNetwork = false)
                EndpointSecurity.PublicCleartext -> CleartextNotice(publicNetwork = true)
                EndpointSecurity.Secure -> Unit
            }
        }

        item(key = "api_key") {
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = keyDraft,
                onValueChange = { keyDraft = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API Key") },
                placeholder = {
                    Text(if (state.hasKey) "已保存一把，留空表示不修改" else "sk-…")
                },
                singleLine = true,
                visualTransformation = if (keyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                trailingIcon = {
                    TextButton(onClick = { keyVisible = !keyVisible }) {
                        Text(if (keyVisible) "隐藏" else "显示")
                    }
                },
                supportingText = {
                    Text(
                        when {
                            keyDraft.isNotBlank() -> "点下方「保存密钥」写入本机（Keystore 加密）"
                            state.hasKey -> "密钥已用系统 Keystore 加密，只存在这台手机上"
                            else -> "密钥只存在本机，不会出现在备份文件里"
                        },
                    )
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        onSaveKey(keyDraft)
                        keyDraft = ""
                        keyVisible = false
                    },
                    enabled = keyDraft.isNotBlank(),
                ) { Text("保存密钥") }
                if (state.hasKey) {
                    TextButton(onClick = { onClearKey() }) {
                        Text("清除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        item(key = "model") {
            OutlinedTextField(
                value = endpoint.model,
                onValueChange = onModelChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("模型名") },
                placeholder = { Text("deepseek-chat") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                supportingText = { Text("要和服务商文档里写的完全一致") },
            )
        }

        item(key = "test") {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onTest, enabled = !test.testing && state.hasKey) {
                    if (test.testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (test.testing) "测试中…" else "测试连接")
                }
                Spacer(Modifier.width(8.dp))
                test.message?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (test.ok) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        item(key = "profile_label") { SectionLabel("我的档案") }
        item(key = "profile_hint") {
            Text(
                text = "这些会写进每次对话的背景里，AI 排计划时才知道你要练成什么样。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
            ProfileCompleteness(profile)
            Spacer(Modifier.height(12.dp))
        }

        item(key = "goal") {
            OutlinedTextField(
                value = profile.goal,
                onValueChange = onGoalChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("目标") },
                placeholder = { Text("增肌 / 减脂 / 先练会 10 个引体向上") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
        }

        item(key = "level") {
            Spacer(Modifier.height(12.dp))
            FieldLabel("训练水平")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("新手", "有基础", "进阶").forEach { level ->
                    FilterChip(
                        selected = profile.level == level,
                        onClick = { onLevelChange(level) },
                        label = { Text(level) },
                    )
                }
            }
        }

        item(key = "experience") {
            Spacer(Modifier.height(12.dp))
            FieldLabel("训练经验")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EXPERIENCE_OPTIONS.forEach { option ->
                    FilterChip(
                        selected = profile.experience == option,
                        onClick = { onExperienceChange(option) },
                        label = { Text(option) },
                    )
                }
            }
            Text(
                text = "练了多久、有没有断过。和上面的自评水平不是一回事：" +
                    "练过两年但停了半年的，AI 会按重新开始排。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
        }

        item(key = "days") {
            Spacer(Modifier.height(12.dp))
            FieldLabel("每周练几次")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2, 3, 4, 5, 6).forEach { days ->
                    FilterChip(
                        selected = profile.daysPerWeek == days,
                        onClick = { onDaysChange(days) },
                        label = { Text("$days 次") },
                    )
                }
            }
        }

        item(key = "minutes") {
            Spacer(Modifier.height(12.dp))
            FieldLabel("单次时长")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(20, 30, 45, 60, 90).forEach { minutes ->
                    FilterChip(
                        selected = profile.sessionMinutes == minutes,
                        onClick = { onMinutesChange(minutes) },
                        label = { Text("$minutes 分钟") },
                    )
                }
            }
        }

        item(key = "training_days") {
            Spacer(Modifier.height(12.dp))
            FieldLabel("哪几天能练（可不选）")
            TrainingDayChips(
                selected = profile.trainingDays,
                onToggle = onToggleTrainingDay,
            )
            Text(
                text = "选了具体日子，AI 才能安排隔天练还是连着练；不选就只按每周次数算。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
        }

        item(key = "limitations") {
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = profile.limitations,
                onValueChange = onLimitationsChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("伤病 / 疼痛 / 禁忌动作") },
                placeholder = { Text("右肩有旧伤，过顶推举会疼；深蹲到底膝盖响") },
                minLines = 2,
                maxLines = 4,
            )
            Text(
                text = "最重要的一项：涉及这里写的部位时，教练会先让你停练或换动作，" +
                    "不会说「忍一下」。没有就写「无」。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
        }

        item(key = "equipment") {
            Spacer(Modifier.height(16.dp))
            FieldLabel("可用器械")
            ChipFlow(
                options = EQUIPMENT_OPTIONS,
                selected = profile.equipment,
                onToggle = onToggleEquipment,
            )
            Text(
                text = "勾了它，AI 才不会给你排「需要引体向上杠」但你根本没杠的计划。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
        }

        item(key = "focus") {
            Spacer(Modifier.height(16.dp))
            FieldLabel("想加强的部位 / 方向（可不选）")
            ChipFlow(
                options = FOCUS_OPTIONS,
                selected = profile.preferredFocus,
                onToggle = onToggleFocus,
            )
            Text(
                text = "只影响「优先排哪些」：不会因为选了胸就把腿完全去掉。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
        }

        item(key = "space") {
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = profile.availableSpace,
                onValueChange = onSpaceChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("训练空间（可不填）") },
                placeholder = { Text("客厅 2×2 米，楼上有人，不能跳") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
        }

        item(key = "notes") {
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = profile.notes,
                onValueChange = onNotesChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("其他备注（可不填）") },
                placeholder = { Text("晚上十点后不方便练，膝盖不好时不做跳跃") },
                minLines = 2,
                maxLines = 4,
            )
        }

        item(key = "auto_coach_label") { SectionLabel("全程 AI 教练") }
        item(key = "auto_coach") {
            SurfaceCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "让 AI 管理我的计划",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = if (state.preferences.autoCoach) {
                                "已开启 · 练完结算页会出现「让教练复盘」"
                            } else {
                                "练完让教练看一次成绩，并调整下次的计划"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = state.preferences.autoCoach,
                        onCheckedChange = { checked ->
                            if (checked) showAutoCoachConfirm = true else onAutoCoachChange(false)
                        },
                        enabled = state.ready,
                    )
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = "开启后：计划由 AI 根据你的实际完成度调整，练完可以给它一句反馈" +
                        "（累不累、哪里不舒服），它会改动作、改组次，或者加一次别的训练。" +
                        "改动会先列出清单，你点「应用」才生效。\n" +
                        "代价：每次复盘约 2~3k token。DeepSeek 不到一分钱，GPT-4o 这类要贵二十倍；" +
                        "不点复盘就不会产生费用。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!state.ready) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "先把上面的 Key 填好，这个开关才能打开。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        item(key = "footer") {
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(14.dp),
            ) {
                Text(
                    text = "提示：AI 只能从内置动作库里挑动作，所以排出来的计划和技能树、" +
                        "动图、肌肉热力图都是对得上的；它编不出来的动作会被自动丢掉。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showAutoCoachConfirm) {
        AlertDialog(
            onDismissRequest = { showAutoCoachConfirm = false },
            title = { Text("把计划交给 AI 管理？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "练完之后，结算页会出现「让教练复盘」。你写一句感受" +
                            "（累不累、哪里不舒服、时间够不够），教练会给出调整建议 —— " +
                            "改动作、改组次，或者加一次别的训练。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "它不会偷偷改：建议先以清单形式给你看，点「应用」才写进计划；" +
                            "不想用就点「不用了」，计划一个字都不动。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "要花钱：每次复盘会把本次每组的成绩和你的反馈发给你配置的服务商，" +
                            "约 2~3k token。不点复盘就不消耗。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAutoCoachChange(true)
                        showAutoCoachConfirm = false
                    },
                ) { Text("开启") }
            },
            dismissButton = {
                TextButton(onClick = { showAutoCoachConfirm = false }) { Text("先不用") }
            },
        )
    }
}

/**
 * 档案完整度。
 *
 * 只报「关键项还缺什么」，不追求填满 11 项 —— 全都填完才敢排计划的话，
 * 没人会用这个功能。缺关键项时用警示色说明「AI 会先问你」，别让它看起来像报错。
 */
@Composable
private fun ProfileCompleteness(profile: AiProfile) {
    val missing = profile.missingCritical()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "档案已填 ${profile.filledCount}/${ProfileField.ALL.size} 项",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (missing.isEmpty()) {
                "关键项齐了：AI 可以直接排计划，不用再问你。"
            } else {
                "关键项还缺「${missing.joinToString("、") { ProfileField.label(it) }}」——" +
                    "不填的话 AI 会先问一句，或者按默认值猜。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (missing.isEmpty()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

/** 一排可多选的取值，用于器械、偏好这类固定选项。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(options: List<String>, selected: List<String>, onToggle: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option in selected,
                onClick = { onToggle(option) },
                label = { Text(option) },
            )
        }
    }
}

/** 周一到周日七个可多选的圆角小按钮。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrainingDayChips(selected: List<Int>, onToggle: (Int) -> Unit) {
    val labels = listOf("一", "二", "三", "四", "五", "六", "日")
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val day = index + 1
            FilterChip(
                selected = day in selected,
                onClick = { onToggle(day) },
                label = { Text("周$label") },
            )
        }
    }
}

/** 设置页里那种「一块浅色卡片」：多处复用，别每个 item 各写一遍圆角背景。 */
@Composable
private fun SurfaceCard(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
    ) {
        Column { content() }
    }
}

@Composable
private fun CleartextNotice(publicNetwork: Boolean) {
    val container = if (publicNetwork) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val body = if (publicNetwork) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(container)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (publicNetwork) Icons.Filled.Warning else Icons.Filled.Lock,
            contentDescription = null,
            tint = if (publicNetwork) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(18.dp),
        )
        Column {
            Text(
                text = if (publicNetwork) "明文 HTTP，内容会被看见" else "局域网明文地址",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = body,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (publicNetwork) {
                    "http:// 不加密。你的 API Key 和每次对话的正文都会原样经过网络，" +
                        "同一个 Wi-Fi 下的人、以及路上任何一台中间设备都能读到。" +
                        "除非这是你自己搭的服务器，否则请把开头改成 https://。"
                } else {
                    "这是局域网地址，数据只在同一个 Wi-Fi 内明文传输，自己电脑上跑模型没问题。" +
                        "但别把公网服务的 Key 填在这里 —— 同一网络里的设备一样能看到它。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = body.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun PresetChip(preset: AiProviderPreset, selected: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(preset.label) },
        colors = if (selected) {
            AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        } else {
            AssistChipDefaults.assistChipColors()
        },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 8.dp),
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

/** 圆形小头像，聊天气泡用，避免两侧各画一个。 */
@Composable
internal fun CoachDot(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "AI",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Bold,
        )
    }
}
