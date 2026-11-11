package io.github.kylin33.aifitgym.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kylin33.aifitgym.data.local.SetLogRow
import io.github.kylin33.aifitgym.data.repo.HistoryGroup
import io.github.kylin33.aifitgym.data.repo.isTimedIn
import io.github.kylin33.aifitgym.ui.components.ExerciseImage

/**
 * 一次训练的历史详情：能看每一组做了多少，也能直接改。
 *
 * 编辑入口做成「点整行」而不是小铅笔图标 —— 手机上 24dp 的图标很难点准，
 * 而这一页的每一行本来就只有一个可做的动作。
 */
@Composable
fun SessionDetailScreen(
    state: SessionDetailViewModel.UiState,
    onBack: () -> Unit,
    onEditSet: (setLogId: Long, value: Int) -> Unit,
    onAddSet: (HistoryGroup) -> Unit,
    onDeleteSet: (setLogId: Long) -> Unit,
    onFinishSession: () -> Unit,
    onDeleteSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<SetLogRow?>(null) }
    var deletingSet by remember { mutableStateOf<SetLogRow?>(null) }
    var confirmingSessionDelete by remember { mutableStateOf(false) }

    val detail = state.detail

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = detail?.session?.routineNameSnapshot?.ifBlank { "训练记录" } ?: "训练记录",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (detail != null) {
                IconButton(onClick = { confirmingSessionDelete = true }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除这次训练",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        when {
            state.loading -> Unit

            detail == null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "这条训练记录已经不在了",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp),
            ) {
                item(key = "summary") {
                    Column {
                        Text(
                            text = sessionTimeLabel(detail.session.startTime),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DetailStat("组数", "${detail.setCount}", Modifier.weight(1f))
                            DetailStat("时长", durationLabel(detail.session.totalDurationSec), Modifier.weight(1f))
                            DetailStat("次数", "${detail.totalReps}", Modifier.weight(1f))
                        }
                        if (!detail.session.isFinished) {
                            Spacer(Modifier.height(12.dp))
                            UnfinishedBanner(onFinish = onFinishSession)
                        }
                        Spacer(Modifier.height(18.dp))
                        Text(
                            text = "点任意一组可以改成绩",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }

                detail.groups.forEach { group ->
                    item(key = "group-${group.exerciseId}") {
                        GroupCard(
                            group = group,
                            onEdit = { editing = it },
                            onDelete = { deletingSet = it },
                            onAdd = { onAddSet(group) },
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }

    editing?.let { row ->
        val group = detail?.groups?.firstOrNull { g -> g.sets.any { it.setLogId == row.setLogId } }
        EditSetDialog(
            row = row,
            timed = group != null && row.isTimedIn(group),
            onDismiss = { editing = null },
            onConfirm = { value ->
                onEditSet(row.setLogId, value)
                editing = null
            },
        )
    }

    deletingSet?.let { row ->
        AlertDialog(
            onDismissRequest = { deletingSet = null },
            title = { Text("删除这一组？") },
            text = { Text("删掉后这一组就不算在训练量和恢复里了。") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSet(row.setLogId)
                    deletingSet = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingSet = null }) { Text("取消") }
            },
        )
    }

    if (confirmingSessionDelete) {
        AlertDialog(
            onDismissRequest = { confirmingSessionDelete = false },
            title = { Text("删除整次训练？") },
            text = { Text("这次训练的所有组都会一并删掉，训练量、恢复和 PR 都会跟着变，且无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingSessionDelete = false
                    onDeleteSession()
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingSessionDelete = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun GroupCard(
    group: HistoryGroup,
    onEdit: (SetLogRow) -> Unit,
    onDelete: (SetLogRow) -> Unit,
    onAdd: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ExerciseImage(
                path = group.image,
                contentDescription = group.name,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (group.isTimed) {
                        "${group.sets.size} 组 · 共 ${group.totalSeconds} 秒"
                    } else {
                        "${group.sets.size} 组 · 共 ${group.totalReps} 次"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        group.sets.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onEdit(row) }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${row.setIndex}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (row.actualDurationSec > 0) "坚持 ${row.actualDurationSec} 秒"
                    else "${row.actualReps} 次",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "改",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = { onDelete(row) },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除这一组",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }

        TextButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("补一组", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun UnfinishedBanner(onFinish: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = "这次训练没有正常结束",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "补完组数后可以把它标记为已完成，时长按开始到现在计算（最多记 6 小时）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
        )
        TextButton(
            onClick = onFinish,
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
        ) {
            Text("标记为已完成", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun EditSetDialog(
    row: SetLogRow,
    timed: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val initial = if (timed) row.actualDurationSec else row.actualReps
    var text by remember(row.setLogId) { mutableStateOf(initial.toString()) }
    val value = text.toIntOrNull()
    val label = if (timed) "坚持秒数" else "完成次数"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("改第 ${row.setIndex} 组") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        // 只吃数字，最长 4 位：足够到 9999 秒
                        if (input.length <= 4 && input.all { it.isDigit() }) text = input
                    },
                    label = { Text(label) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "填 0 等于「这一组没做」，仍然会留在记录里。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = value != null,
                onClick = { value?.let(onConfirm) },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
