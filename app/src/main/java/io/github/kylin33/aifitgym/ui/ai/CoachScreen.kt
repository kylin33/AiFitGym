package io.github.kylin33.aifitgym.ui.ai

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.kylin33.aifitgym.data.ai.AiSettingsState
import io.github.kylin33.aifitgym.data.ai.CoachPrompts
import io.github.kylin33.aifitgym.data.ai.PlanDraft
import io.github.kylin33.aifitgym.data.anatomy.MuscleScoring
import io.github.kylin33.aifitgym.ui.components.ExerciseImage
import io.github.kylin33.aifitgym.ui.components.MuscleHeatmap
import io.github.kylin33.aifitgym.ui.components.rememberMuscleTaxonomy
import io.github.kylin33.aifitgym.ui.legal.LegalContent

/**
 * AI 教练对话页。
 *
 * 三条设计原则：
 *  1. **教练说话像人**：正文就是普通气泡，没有 JSON、没有表格，代码块在解析前就被剥掉了；
 *  2. **计划是卡片不是文本**：一旦模型给出计划，气泡下面就长出一张可视化卡片
 *     （肌群热力图 + 每条动作的动图缩略图），点按钮直接写进「我的计划」；
 *  3. **没有配置也要能看懂**：顶部横幅说明还差什么，一键跳到设置，而不是让发送按钮失灵。
 */
@Composable
fun CoachScreen(
    state: CoachUiState,
    settings: AiSettingsState,
    message: String?,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onImport: (Long) -> Unit,
    onOpenRoutine: (Long) -> Unit,
    onNewChat: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    // 输入到一半转屏/切后台，草稿不该丢，所以用 rememberSaveable
    var draft by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        onMessageShown()
    }

    // 流式输出时跟着最后一条滚动：用文本长度当 key，比用消息数更灵敏
    val lastLength = state.messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(state.messages.size, lastLength) {
        if (state.messages.isNotEmpty()) {
            listState.scrollToItem(state.messages.lastIndex)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .imePadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            CoachTopBar(
                subtitle = if (settings.ready) {
                    "${settings.preferences.endpoint.model} · 能读到你的训练记录"
                } else {
                    "还没配置服务"
                },
                hasHistory = state.messages.isNotEmpty(),
                onBack = onBack,
                onOpenSettings = onOpenSettings,
                onNewChat = onNewChat,
            )

            if (!settings.ready) {
                ConfigBanner(onOpenSettings)
            }

            if (state.messages.isEmpty()) {
                EmptyState(
                    modifier = Modifier.weight(1f),
                    onPick = { prompt ->
                        draft = ""
                        // 没配置就先去配置：发出去只会换来一条「还没填 Key」的错误气泡
                        if (settings.ready) onSend(prompt) else onOpenSettings()
                    },
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(state.messages, key = { it.id }) { item ->
                        when (item.role) {
                            CoachRole.USER -> UserBubble(item.text)
                            CoachRole.COACH -> CoachBubble(
                                message = item,
                                streaming = state.streaming && item.id == state.messages.last().id,
                                importing = state.importing,
                                onImport = { onImport(item.id) },
                                onOpenRoutine = onOpenRoutine,
                            )
                        }
                    }
                }
            }

            InputBar(
                draft = draft,
                onDraftChange = { draft = it },
                streaming = state.streaming,
                enabled = settings.ready,
                onSend = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        draft = ""
                        onSend(text)
                    }
                },
                onStop = onStop,
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }
}

@Composable
private fun CoachTopBar(
    subtitle: String,
    hasHistory: Boolean,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onNewChat: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = "AI 教练",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (hasHistory) {
            TextButton(onClick = onNewChat) { Text("新对话") }
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "设置")
        }
    }
}

@Composable
private fun ConfigBanner(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "先配好服务才能聊",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "填 Base URL、API Key 和模型名，DeepSeek 最省事",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
        }
        TextButton(onClick = onOpenSettings) { Text("去配置") }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "AI",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = CoachPrompts.GREETING,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CoachPrompts.SUGGESTIONS.forEach { suggestion ->
                AssistChip(
                    onClick = { onPick(suggestion) },
                    label = { Text(suggestion, style = MaterialTheme.typography.labelLarge) },
                )
            }
        }
        Spacer(Modifier.height(26.dp))
        // 免责声明在这里而不是藏在设置里：AI 一开口就该让人知道它说的话有边界
        Text(
            text = LegalContent.DISCLAIMER_SHORT,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

/**
 * 教练气泡。
 *
 * 没有套背景色：教练的话通常有好几行，装进色块里会显得很重。
 * 只留一个圆形头像 + 正文，和微信里「对方发来一段话」的观感接近。
 */
@Composable
private fun CoachBubble(
    message: CoachMessage,
    streaming: Boolean,
    importing: Boolean,
    onImport: () -> Unit,
    onOpenRoutine: (Long) -> Unit,
) {
    Row(Modifier.fillMaxWidth()) {
        CoachDot()
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            when {
                message.text.isNotBlank() -> Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message.failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onBackground
                    },
                )

                streaming -> TypingIndicator()
            }

            if (streaming && message.text.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                TypingIndicator()
            }

            message.plan?.let { plan ->
                Spacer(Modifier.height(12.dp))
                PlanCard(
                    plan = plan,
                    importing = importing,
                    importedRoutineId = message.importedRoutineId,
                    onImport = onImport,
                    onOpenRoutine = onOpenRoutine,
                )
            }
        }
    }
}

@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "typing-alpha",
    )
    Text(
        text = "● ● ●",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.alpha(alpha),
    )
}

/**
 * 计划预览卡片。
 *
 * 这里复用了恢复看板那套 `MuscleScoring.forPlan`，所以「这套计划练哪儿」是
 * 和统计页同一套算法算出来的，不是再让 AI 编一段话。
 */
@Composable
private fun PlanCard(
    plan: PlanDraft,
    importing: Boolean,
    importedRoutineId: Long?,
    onImport: () -> Unit,
    onOpenRoutine: (Long) -> Unit,
) {
    val taxonomy = rememberMuscleTaxonomy()
    val scores = remember(plan, taxonomy) {
        if (taxonomy == null) {
            emptyMap()
        } else {
            MuscleScoring.forPlan(
                taxonomy,
                plan.items.map { MuscleScoring.PlannedExercise(it.exercise, it.sets) },
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "训练计划",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (importedRoutineId != null) "已加入" else "AI 生成",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = plan.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (plan.reason.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = plan.reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (scores.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            MuscleHeatmap(
                muscleScores = scores,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
            )
        }

        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

        plan.items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.width(18.dp),
                )
                ExerciseImage(
                    path = item.exercise.image,
                    contentDescription = item.exercise.name,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.exercise.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = buildString {
                            append("${item.sets} 组")
                            when {
                                item.durationSec != null -> append(" × ${item.durationSec} 秒")
                                item.reps != null -> append(" × ${item.reps} 次")
                            }
                            append(" · 休息 ${item.restSec} 秒")
                            if (!item.note.isNullOrBlank()) append(" · ${item.note}")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }

        if (plan.dropped > 0) {
            Text(
                text = "有 ${plan.dropped} 个动作在本地库里找不到，已忽略",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val id = importedRoutineId
            if (id == null) {
                Button(onClick = onImport, enabled = !importing) {
                    Text(if (importing) "正在加入…" else "加入我的计划")
                }
            } else {
                Button(onClick = { onOpenRoutine(id) }) { Text("打开计划") }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "可再编辑组数与休息",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun InputBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    streaming: Boolean,
    enabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(if (enabled) "问问教练，或者直接说「明天练什么」" else "先配置服务")
            },
            maxLines = 4,
            shape = RoundedCornerShape(22.dp),
            enabled = enabled,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
        )
        Spacer(Modifier.width(8.dp))
        FilledIconButton(
            onClick = { if (streaming) onStop() else onSend() },
            enabled = if (streaming) true else enabled && draft.isNotBlank(),
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            if (streaming) {
                Icon(Icons.Filled.Close, contentDescription = "停止")
            } else {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
            }
        }
    }
}
