package io.github.kylin33.aifitgym.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.kylin33.aifitgym.data.anatomy.MuscleScoring
import io.github.kylin33.aifitgym.data.local.ExerciseEntity
import io.github.kylin33.aifitgym.ui.components.ExerciseClip
import io.github.kylin33.aifitgym.ui.components.MuscleHeatmap
import io.github.kylin33.aifitgym.ui.components.muscleBaseColor
import io.github.kylin33.aifitgym.ui.components.muscleColorForScore
import io.github.kylin33.aifitgym.ui.components.rememberMuscleTaxonomy

/**
 * 动作详情抽屉：上面演示图，下面肌肉热力图。
 *
 * 热力图直接吃 [MuscleScoring.forExercise] 的结果（主肌群 1.0 / 副肌群 0.4），
 * 所以「这个动作练哪里」不用再靠读标签，一眼就能看出来。
 *
 * [footer] 是给技能树用的插槽：同一个抽屉，从技能树打开时底部多一行「历史最高 / 还差多少」，
 * 从动作库打开时不传就没有 —— 免得为了一个角标复制一份抽屉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExercisePreviewSheet(
    exercise: ExerciseEntity,
    onDismiss: () -> Unit,
    onOpenDetail: () -> Unit,
    footer: (@Composable () -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val taxonomy = rememberMuscleTaxonomy()
    val scores = remember(taxonomy, exercise) {
        taxonomy?.let { MuscleScoring.forExercise(it, exercise) }.orEmpty()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                ExerciseClip(
                    exerciseId = exercise.id,
                    imagePath = exercise.image,
                    contentDescription = exercise.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(16.dp)),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = exercise.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    if (exercise.nameEn.isNotBlank() && exercise.nameEn != exercise.name) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = exercise.nameEn,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    SheetTags(exercise)
                }
            }

            Spacer(Modifier.height(18.dp))

            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "肌肉热力图",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "主肌群深色 · 辅助肌群浅色",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    if (taxonomy == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "肌群数据加载中…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    } else {
                        MuscleHeatmap(
                            muscleScores = scores,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(250.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        MuscleLegend(scores = scores, taxonomy = taxonomy)
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            if (footer != null) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        footer()
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            Button(
                onClick = onOpenDetail,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text("查看完整步骤", fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 图例：按强度排序，色点和人体图上那块肌肉的颜色一致。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MuscleLegend(
    scores: Map<String, Float>,
    taxonomy: io.github.kylin33.aifitgym.data.anatomy.MuscleTaxonomy,
) {
    val accent = MaterialTheme.colorScheme.primary
    val base = muscleBaseColor()
    val ranked = remember(scores) { MuscleScoring.ranked(scores) }

    if (ranked.isEmpty()) {
        Text(
            text = "这个动作没有可绘制的目标肌群（如纯有氧）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        return
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ranked.forEach { (slug, score) ->
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(muscleColorForScore(score, base, accent)),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = taxonomy.nameCn(slug),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SheetTags(exercise: ExerciseEntity) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MiniChip(text = exercise.bodyPartCn, primary = false)
        if (exercise.categoryCn.isNotBlank()) MiniChip(text = exercise.categoryCn, primary = false)
        if (exercise.targetCn.isNotBlank()) MiniChip(text = exercise.targetCn, primary = true)
    }
}

@Composable
private fun MiniChip(text: String, primary: Boolean) {
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
