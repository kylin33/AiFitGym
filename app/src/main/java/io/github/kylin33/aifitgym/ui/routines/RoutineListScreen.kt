package io.github.kylin33.aifitgym.ui.routines

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kylin33.aifitgym.data.checkpoint.ActiveSessionCheckpoint
import io.github.kylin33.aifitgym.data.local.RoutineCategory
import io.github.kylin33.aifitgym.data.local.RoutineSummary
import io.github.kylin33.aifitgym.ui.components.ExerciseImage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * 计划列表（首页）：内置模板 + 用户自建。
 *
 * 卡片信息够判断「这套练要不要今天做」：练什么（封面）、几个动作、共几组、属于力量还是有氧。
 * 长按卡片弹底部操作面板，可以删除整套计划（带二次确认 + 底部撤销）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineListScreen(
    routines: List<RoutineSummary>,
    events: Flow<RoutineListEvent>,
    onOpenRoutine: (Long) -> Unit,
    onStartWorkout: (Long) -> Unit,
    onCreateRoutine: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenCoach: () -> Unit,
    onDeleteRoutine: (Long) -> Unit,
    onUndoDelete: () -> Unit,
    modifier: Modifier = Modifier,
    checkpoint: ActiveSessionCheckpoint? = null,
    onResumeSession: () -> Unit = {},
    onDiscardCheckpoint: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 长按选中的卡片 → 底部面板；确认删除 → 对话框
    var sheetTarget by remember { mutableStateOf<RoutineSummary?>(null) }
    var confirmTarget by remember { mutableStateOf<RoutineSummary?>(null) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    /** 先播完收起动画再把状态置空，否则面板会「啪」地消失。 */
    fun closeSheet(after: () -> Unit = {}) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            sheetTarget = null
            after()
        }
    }

    LaunchedEffect(events) {
        events.collect { event ->
            when (event) {
                is RoutineListEvent.Deleted -> launch {
                    val result = snackbarHostState.showSnackbar(
                        message = "已删除「${event.name}」",
                        actionLabel = "撤销",
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) onUndoDelete()
                }

                is RoutineListEvent.Restored -> launch {
                    snackbarHostState.showSnackbar("已恢复「${event.name}」")
                }

                is RoutineListEvent.Failed -> launch {
                    snackbarHostState.showSnackbar(event.message)
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "训练计划",
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (routines.isEmpty()) {
                                "还没有计划，新建一套开始练"
                            } else {
                                "${routines.size} 套计划 · 点击编排 · 长按删除"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onOpenLibrary) { Text("动作库") }
                }
                Spacer(Modifier.height(10.dp))
            }

            item(key = "resume_banner") {
                if (checkpoint != null) {
                    ResumeBanner(
                        checkpoint = checkpoint,
                        onResume = onResumeSession,
                        onDiscard = onDiscardCheckpoint,
                    )
                }
            }

            item(key = "coach") {
                CoachEntryCard(onClick = onOpenCoach)
                Spacer(Modifier.height(2.dp))
            }

            items(items = routines, key = { it.routine.routineId }) { summary ->
                RoutineCard(
                    summary = summary,
                    onClick = { onOpenRoutine(summary.routine.routineId) },
                    onLongClick = { sheetTarget = summary },
                    onStart = { onStartWorkout(summary.routine.routineId) },
                )
            }
        }

        ExtendedFloatingActionButton(
            onClick = onCreateRoutine,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("新建计划", fontWeight = FontWeight.SemiBold) },
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // 抬到 FAB 上方，避免被「新建计划」按钮压住
                .padding(start = 16.dp, end = 16.dp, bottom = 92.dp),
        )
    }

    sheetTarget?.let { target ->
        ModalBottomSheet(
            onDismissRequest = { sheetTarget = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            RoutineActionsSheet(
                summary = target,
                onDelete = {
                    closeSheet { confirmTarget = target }
                },
                onCancel = { closeSheet() },
            )
        }
    }

    confirmTarget?.let { target ->
        DeleteRoutineDialog(
            summary = target,
            onConfirm = {
                onDeleteRoutine(target.routine.routineId)
                confirmTarget = null
            },
            onDismiss = { confirmTarget = null },
        )
    }
}

/**
 * 「上次没做完」横幅。
 *
 * 只在真的存在检查点时出现，而且必须**显眼**：用户开 App 的目的十有八九是接着练，
 * 让他去翻历史找那条没头没尾的记录，等于把恢复功能藏起来。
 *
 * 文案刻意写清「已完成 3/12 组」：这样「继续」是可预期的（从第 4 组接上），
 * 而不是让人担心点进去会从头再来一遍。
 */
@Composable
private fun ResumeBanner(
    checkpoint: ActiveSessionCheckpoint,
    onResume: () -> Unit,
    onDiscard: () -> Unit,
) {
    var confirmDiscard by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "有一场训练没做完",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = checkpoint.routineName.ifBlank { "考级训练" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = "${checkpoint.positionLabel} · 已完成 ${checkpoint.completedSets}/${checkpoint.totalSets} 组",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { checkpoint.progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f),
        )

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onResume,
                modifier = Modifier.weight(1f),
            ) { Text("继续训练") }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = { confirmDiscard = true }) {
                Text("放弃", color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃这次训练？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "只会去掉这条「继续训练」的提示。" +
                            "已经做完的 ${checkpoint.completedSets} 组还在训练历史里，" +
                            "想补完或删掉都可以在那边操作。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        onDiscard()
                    },
                ) { Text("放弃", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("取消") }
            },
        )
    }
}

/**
 * AI 教练入口。
 *
 * 放在计划列表顶部而不是塞进「我的」：排计划这件事天然发生在这里 ——
 * 用户翻着计划想「今天该练什么」的时候，才是他最需要教练的时候。
 * 卡片做得很薄（一行标题 + 一行说明），不抢内置模板的位置。
 */
@Composable
private fun CoachEntryCard(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "AI 教练",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "看过你的训练记录，帮你排下一套",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
        )
    }
}

/** 长按弹出的操作面板。目前只有删除，后续加「复制一份」「归档」都放这里。 */
@Composable
private fun RoutineActionsSheet(
    summary: RoutineSummary,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val routine = summary.routine
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        Text(
            text = routine.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${summary.exerciseCount} 个动作 · 共 ${summary.totalSets} 组 · ${routine.category.label}" +
                if (routine.presetKey != null) " · 内置模板" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(18.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.errorContainer)
                .clickable(onClick = onDelete)
                .padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "删除这个计划",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "只删除计划本身，已完成的打卡记录会保留。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )

        Spacer(Modifier.height(6.dp))
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("取消")
        }
    }
}

@Composable
private fun DeleteRoutineDialog(
    summary: RoutineSummary,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val routine = summary.routine
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "删除「${routine.name}」？",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column {
                Text(
                    text = "计划里的 ${summary.exerciseCount} 个动作编排会一起删除。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (routine.presetKey != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "这是内置模板，删掉后不会自动恢复。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "删完可以在底部提示条里撤销。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoutineCard(
    summary: RoutineSummary,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onStart: () -> Unit,
) {
    val routine = summary.routine
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExerciseImage(
            path = summary.coverImage,
            contentDescription = routine.name,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(14.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = routine.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CategoryChip(routine.category, preset = routine.presetKey != null)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${summary.exerciseCount} 个动作 · 共 ${summary.totalSets} 组",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.width(8.dp))

        // 独立的开始按钮：点卡片是编排，点这里直接开练
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onStart),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "开始训练",
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun CategoryChip(category: RoutineCategory, preset: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip(text = category.label, primary = true)
        if (preset) Chip(text = "内置", primary = false)
    }
}

@Composable
private fun Chip(text: String, primary: Boolean) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(
                if (primary) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (primary) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
