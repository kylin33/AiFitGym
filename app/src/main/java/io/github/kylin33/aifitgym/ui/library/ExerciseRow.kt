package io.github.kylin33.aifitgym.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kylin33.aifitgym.data.local.ExerciseEntity
import io.github.kylin33.aifitgym.ui.components.ExerciseImage

/**
 * 动作列表行。动作库和「添加动作选择器」共用一份，避免两处样式各改各的。
 *
 * @param selected 选择器里勾中的状态（只影响底色，勾选框由 [trailing] 传进来）
 * @param trailing 右侧附加内容：选择器放复选框，动作库不放
 */
@Composable
internal fun ExerciseRow(
    exercise: ExerciseEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface
            )
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExerciseImage(
            path = exercise.image,
            contentDescription = exercise.name,
            modifier = Modifier
                .size(78.dp)
                .clip(RoundedCornerShape(14.dp)),
        )

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = exercise.name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (exercise.nameEn.isNotBlank() && exercise.nameEn != exercise.name) {
                Text(
                    text = exercise.nameEn,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(6.dp))
            MuscleTags(exercise)

            Spacer(Modifier.height(6.dp))
            Text(
                text = listOf(exercise.bodyPartCn, exercise.categoryCn, exercise.equipmentCn)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
            )
        }

        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            trailing()
        }
    }
}

/** 主动肌（实心）+ 辅助肌（浅色），最多显示 3 个辅助肌，其余折叠成 +N。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MuscleTags(exercise: ExerciseEntity) {
    val secondary = exercise.secondaryMuscles
    val visible = secondary.take(3)
    val rest = secondary.size - visible.size

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (exercise.targetCn.isNotBlank()) {
            TagChip(text = exercise.targetCn, primary = true)
        }
        visible.forEach { TagChip(text = it, primary = false) }
        if (rest > 0) TagChip(text = "+$rest", primary = false)
    }
}

@Composable
private fun TagChip(text: String, primary: Boolean) {
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
