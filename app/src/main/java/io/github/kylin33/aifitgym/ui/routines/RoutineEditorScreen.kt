package io.github.kylin33.aifitgym.ui.routines

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kylin33.aifitgym.data.local.RoutineCategory
import io.github.kylin33.aifitgym.ui.components.ExerciseImage

/**
 * 计划编排页。
 *
 * 一个条目的可调项就是四件事：几组、每组多少次（或多少秒）、组间歇多久、做不动了换哪个动作。
 * 计次/计时用同一个按钮切换，切换时保留另一侧的值，来回点不会丢数据。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineEditorScreen(
    state: RoutineEditorState,
    onBack: () -> Unit,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onCategoryChange: (RoutineCategory) -> Unit,
    onAddExercise: () -> Unit,
    onPickBackup: (Long) -> Unit,
    onClearBackup: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onMove: (Long, Int) -> Unit,
    onSetsChange: (Long, Int) -> Unit,
    onRepsChange: (Long, Int) -> Unit,
    onDurationChange: (Long, Int) -> Unit,
    onRestChange: (Long, Int) -> Unit,
    onToggleTimed: (Long) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (state.isNew) "新建计划" else "编辑计划",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (state.items.isNotEmpty()) {
                    Text(
                        text = "${state.items.size} 个动作 · 共 ${state.totalSets} 组",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onSave, enabled = state.canSave) {
                Text(
                    text = if (state.saving) "保存中…" else "保存",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        if (state.loading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "meta") {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column(Modifier.padding(14.dp)) {
                        OutlinedTextField(
                            value = state.name,
                            onValueChange = onNameChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("计划名称") },
                            placeholder = { Text("如：周一推力日 / 15 分钟 HIIT") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = state.description,
                            onValueChange = onDescriptionChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("说明（可留空）") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RoutineCategory.entries.forEach { category ->
                                FilterChip(
                                    selected = state.category == category,
                                    onClick = { onCategoryChange(category) },
                                    label = { Text(category.label) },
                                )
                            }
                        }
                        if (state.presetKey != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "内置模板 · 改完保存会保留模板标记",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }

            item(key = "items-header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "动作",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onAddExercise) { Text("+ 添加动作") }
                }
            }

            if (state.items.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = "还没有动作。点右上角「+ 添加动作」，从 2300 多个动作里挑。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }

            itemsIndexed(items = state.items, key = { _, item -> item.draftId }) { index, item ->
                EditorItemCard(
                    index = index,
                    item = item,
                    canMoveUp = index > 0,
                    canMoveDown = index < state.items.lastIndex,
                    onPickBackup = { onPickBackup(item.draftId) },
                    onClearBackup = { onClearBackup(item.draftId) },
                    onRemove = { onRemove(item.draftId) },
                    onMove = { delta -> onMove(item.draftId, delta) },
                    onSetsChange = { onSetsChange(item.draftId, it) },
                    onRepsChange = { onRepsChange(item.draftId, it) },
                    onDurationChange = { onDurationChange(item.draftId, it) },
                    onRestChange = { onRestChange(item.draftId, it) },
                    onToggleTimed = { onToggleTimed(item.draftId) },
                )
            }

            item(key = "footer") {
                Column(Modifier.navigationBarsPadding()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "保存后可以随时回来改组数；训练日志与计时器在下一步接。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
        }

        if (state.error != null) {
            Text(
                text = state.error,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun EditorItemCard(
    index: Int,
    item: EditorItem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onPickBackup: () -> Unit,
    onClearBackup: () -> Unit,
    onRemove: () -> Unit,
    onMove: (Int) -> Unit,
    onSetsChange: (Int) -> Unit,
    onRepsChange: (Int) -> Unit,
    onDurationChange: (Int) -> Unit,
    onRestChange: (Int) -> Unit,
    onToggleTimed: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(18.dp),
                )
                ExerciseImage(
                    path = item.exercise.image,
                    contentDescription = item.exercise.name,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.exercise.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.mainText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上移")
                }
                IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下移")
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Close, contentDescription = "移除")
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stepper(
                    label = "组数",
                    value = item.sets,
                    suffix = "",
                    onChange = onSetsChange,
                )
                Stepper(
                    label = if (item.timed) "每组秒数" else "每组次数",
                    value = if (item.timed) item.durationSec else item.reps,
                    suffix = if (item.timed) "s" else "",
                    onChange = { if (item.timed) onDurationChange(it) else onRepsChange(it) },
                    step = if (item.timed) 5 else 1,
                )
                Stepper(
                    label = "组间休息",
                    value = item.restSec,
                    suffix = "s",
                    onChange = onRestChange,
                    step = 5,
                )
            }

            Spacer(Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onToggleTimed) {
                    Text(if (item.timed) "改为计次" else "改为计时")
                }
                Spacer(Modifier.weight(1f))
                if (item.backupName != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "平替：${item.backupName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = onClearBackup, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "清除平替",
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                } else {
                    TextButton(onClick = onPickBackup) { Text("设平替") }
                }
            }
        }
    }
}

/** 紧凑步进器：左右各一个圆钮，中间显示数值。 */
@Composable
private fun RowScope.Stepper(
    label: String,
    value: Int,
    suffix: String,
    onChange: (Int) -> Unit,
    step: Int = 1,
) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton(text = "−", enabled = true) { onChange(value - step) }
            Text(
                text = "$value$suffix",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .width(44.dp)
                    .padding(horizontal = 2.dp),
                maxLines = 1,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            StepButton(text = "+", enabled = true) { onChange(value + step) }
        }
    }
}

@Composable
private fun StepButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
