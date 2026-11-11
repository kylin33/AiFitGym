package io.github.kylin33.aifitgym.ui.workout

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kylin33.aifitgym.data.anatomy.MuscleScoring
import io.github.kylin33.aifitgym.data.repo.PrMetric
import io.github.kylin33.aifitgym.ui.components.CountdownRing
import io.github.kylin33.aifitgym.ui.components.ExerciseImage
import io.github.kylin33.aifitgym.ui.components.MuscleHeatmap
import io.github.kylin33.aifitgym.ui.components.rememberMuscleTaxonomy

/**
 * 全屏跟练页。
 *
 * 布局固定三段：顶部进度、中部演示（图 + 微缩热力图 + 平替），
 * 底部按阶段切换操作区 —— 计次是次数卡片，计时和休息是倒计时圆环。
 */
@Composable
fun ActiveWorkoutScreen(
    state: WorkoutSessionState,
    onAdjustReps: (Int) -> Unit,
    onCompleteSet: () -> Unit,
    onFinishTimedSet: () -> Unit,
    onSkipRest: () -> Unit,
    onExtendRest: () -> Unit,
    onToggleBackup: () -> Unit,
    onFinishEarly: () -> Unit,
    onDismissPromotions: () -> Unit,
    onSwapPromoted: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showExitDialog by remember { mutableStateOf(false) }

    // 跟练时屏幕不能自己熄灭：倒计时归零那一刻用户往往没碰手机
    val view = LocalView.current
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }

    BackHandler {
        if (state.phase == SessionPhase.COMPLETED || state.phase == SessionPhase.IDLE) {
            onExit()
        } else {
            showExitDialog = true
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        when (state.phase) {
            SessionPhase.IDLE -> SessionMessage(
                title = state.error ?: "还没有开始训练",
                detail = "回到计划列表再点一次「开始训练」",
                actionLabel = "返回",
                onAction = onExit,
            )

            SessionPhase.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }

            SessionPhase.COMPLETED -> WorkoutSummary(
                state = state,
                onExit = onExit,
            )

            else -> SessionBody(
                state = state,
                onAdjustReps = onAdjustReps,
                onCompleteSet = onCompleteSet,
                onFinishTimedSet = onFinishTimedSet,
                onSkipRest = onSkipRest,
                onExtendRest = onExtendRest,
                onToggleBackup = onToggleBackup,
                onRequestExit = { showExitDialog = true },
            )
        }
    }

    // 双轨升级：结算页之上再压一层「晋升 / 突破」，不和退出确认抢焦点
    if (state.promotions.isNotEmpty() || state.breakthroughs.isNotEmpty()) {
        PromotionDialog(
            promotions = state.promotions,
            breakthroughs = state.breakthroughs,
            xpGains = state.xpGains,
            canSwap = state.routineId > 0L,
            onSwap = onSwapPromoted,
            onDismiss = onDismissPromotions,
        )
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("结束训练？") },
            text = {
                Text(
                    if (state.completedSets > 0) {
                        "已经完成的 ${state.completedSets} 组会保留在训练记录里。"
                    } else {
                        "这次训练还没有完成任何一组。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitDialog = false
                        onFinishEarly()
                    },
                ) { Text("结束训练", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) { Text("继续练") }
            },
        )
    }
}

@Composable
private fun SessionBody(
    state: WorkoutSessionState,
    onAdjustReps: (Int) -> Unit,
    onCompleteSet: () -> Unit,
    onFinishTimedSet: () -> Unit,
    onSkipRest: () -> Unit,
    onExtendRest: () -> Unit,
    onToggleBackup: () -> Unit,
    onRequestExit: () -> Unit,
) {
    val entry = state.current
    val exercise = state.currentExercise
    val taxonomy = rememberMuscleTaxonomy()

    val muscleScores = remember(taxonomy, exercise) {
        if (taxonomy == null || exercise == null) emptyMap()
        else MuscleScoring.forExercise(taxonomy, exercise)
    }

    Column(Modifier.fillMaxSize()) {
        WorkoutTopBar(
            routineName = state.routineName,
            elapsedSec = state.elapsedSec,
            progress = state.progress,
            meta = buildString {
                append("第 ${state.entryIndex + 1}/${state.entries.size} 个动作")
                entry?.let { append(" · 第 ${state.setIndex}/${it.targetSets} 组") }
            },
            counter = "已完成 ${state.completedSets}/${state.totalSets} 组",
            onRequestExit = onRequestExit,
        )

        // 中部：演示图为主视觉，热力图缩在右下角
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            ExerciseImage(
                path = exercise?.image,
                contentDescription = exercise?.name,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(24.dp)),
                contentScale = ContentScale.Fit,
            )

            exercise?.let { current ->
                Text(
                    text = current.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

            if (entry?.backup != null) {
                val label = if (state.useBackup) {
                    "切回 ${entry.exercise.name}"
                } else {
                    "做不动？改成 ${entry.backup.name}"
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                        .clickable(onClick = onToggleBackup)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (state.useBackup) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (muscleScores.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                        .size(width = 132.dp, height = 170.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                        .padding(6.dp),
                ) {
                    MuscleHeatmap(
                        muscleScores = muscleScores,
                        modifier = Modifier.fillMaxSize(),
                        gap = 4,
                    )
                }
            }
        }

        // 底部操作区：随阶段切换
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 250.dp)
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (state.phase) {
                SessionPhase.PREPARE -> PreparePanel(
                    state = state,
                    caption = entry?.let { "第 ${state.setIndex}/${it.targetSets} 组" },
                )

                SessionPhase.ACTIVE_SET -> if (state.isTimed) {
                    TimedSetPanel(
                        state = state,
                        caption = entry?.let { "第 ${state.setIndex}/${it.targetSets} 组" },
                        onFinishEarly = onFinishTimedSet,
                    )
                } else {
                    RepsPanel(
                        state = state,
                        targetReps = entry?.plannedReps ?: 0,
                        onAdjustReps = onAdjustReps,
                        onCompleteSet = onCompleteSet,
                    )
                }

                SessionPhase.RESTING -> RestPanel(
                    state = state,
                    onExtendRest = onExtendRest,
                    onSkipRest = onSkipRest,
                )

                else -> Unit
            }
        }
    }
}

@Composable
private fun WorkoutTopBar(
    routineName: String,
    elapsedSec: Int,
    progress: Float,
    meta: String,
    counter: String,
    onRequestExit: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = routineName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                )
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = formatDuration(elapsedSec),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            IconButton(onClick = onRequestExit, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "结束训练", tint = MaterialTheme.colorScheme.outline)
            }
        }

        Spacer(Modifier.height(8.dp))

        // 自己画进度条：LinearProgressIndicator 的 progress 参数在新旧 Material3 上签名不一样
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
        ) {
            if (progress > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = counter,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun PreparePanel(state: WorkoutSessionState, caption: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CountdownRing(
            fraction = if (state.phaseTotalMs > 0) {
                state.remainingMs.toFloat() / state.phaseTotalMs
            } else 1f,
            seconds = state.remainingSec,
            label = "准备",
            caption = caption,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "调整姿势，马上开始",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TimedSetPanel(
    state: WorkoutSessionState,
    caption: String?,
    onFinishEarly: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CountdownRing(
            fraction = if (state.phaseTotalMs > 0) {
                state.remainingMs.toFloat() / state.phaseTotalMs
            } else 1f,
            seconds = state.remainingSec,
            label = "保持",
            caption = caption,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onFinishEarly) { Text("提前完成本组") }
    }
}

@Composable
private fun RepsPanel(
    state: WorkoutSessionState,
    targetReps: Int,
    onAdjustReps: (Int) -> Unit,
    onCompleteSet: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "完成次数",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            RepsStepButton(
                symbol = "−",
                filled = false,
                onClick = { onAdjustReps(-1) },
            )

            Row(
                modifier = Modifier.width(160.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = "${state.reps}",
                    fontSize = 62.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "次",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp, start = 4.dp),
                )
            }

            RepsStepButton(
                symbol = "+",
                filled = true,
                onClick = { onAdjustReps(1) },
            )
        }

        Text(
            text = "目标 $targetReps 次 · 第 ${state.setIndex}/${state.current?.targetSets ?: 1} 组",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = onCompleteSet,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            Text("完成本组", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun RepsStepButton(symbol: String, filled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(
                if (filled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = if (filled) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RestPanel(
    state: WorkoutSessionState,
    onExtendRest: () -> Unit,
    onSkipRest: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CountdownRing(
            fraction = if (state.phaseTotalMs > 0) {
                state.remainingMs.toFloat() / state.phaseTotalMs
            } else 1f,
            seconds = state.remainingSec,
            label = "休息",
            color = MaterialTheme.colorScheme.secondary,
        )

        Spacer(Modifier.height(6.dp))
        Text(
            text = state.nextUpLabel?.let { "接下来 $it" } ?: "马上结束",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onExtendRest, modifier = Modifier.height(48.dp)) {
                Text("+15 秒")
            }
            Button(
                onClick = onSkipRest,
                modifier = Modifier.height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
            ) {
                Text("跳过休息")
            }
        }
    }
}

@Composable
private fun WorkoutSummary(state: WorkoutSessionState, onExit: () -> Unit) {
    val taxonomy = rememberMuscleTaxonomy()

    // 结算热力图按「实际完成的动作和组数」算，切过平替的话算的是平替动作
    val scores = remember(taxonomy, state.volume, state.entries) {
        if (taxonomy == null || state.volume.isEmpty()) {
            emptyMap()
        } else {
            val catalog = state.entries
                .flatMap { listOfNotNull(it.exercise, it.backup) }
                .associateBy { it.id }
            val planned = state.volume.mapNotNull { (id, sets) ->
                catalog[id]?.let { MuscleScoring.PlannedExercise(it, sets) }
            }
            MuscleScoring.forPlan(taxonomy, planned)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(24.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "训练完成",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = state.routineName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(
                label = "总时长",
                value = formatDuration(state.elapsedSec),
                modifier = Modifier.weight(1f),
            )
            StatCard(
                label = "完成组数",
                value = "${state.completedSets} / ${state.totalSets}",
                modifier = Modifier.weight(1f),
            )
        }

        // 破纪录横幅：结算页最该被看见的东西，放在时长卡片正下方
        if (state.prs.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            PrBanner(state.prs)
        }

        if (scores.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text(
                text = "今天练了哪",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(8.dp),
            ) {
                MuscleHeatmap(scores, Modifier.fillMaxSize(), gap = 8)
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = "动作明细",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))

        val grouped = state.logs.groupBy { it.exerciseId }
        grouped.forEach { (exerciseId, logs) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (exerciseId in state.prIds) {
                    Text(text = "🏆", fontSize = 15.sp)
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = logs.first().name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val detail = logs.joinToString(" · ") {
                        if (it.seconds > 0) "${it.seconds}s" else "${it.reps} 次"
                    }
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Text(
                    text = "${logs.size} 组",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(16.dp))

        // 落库状态：写完了才敢说「已保存」，失败也不假装成功
        val saveText = when {
            state.saving -> "正在保存训练记录…"
            state.saveError != null -> state.saveError
            state.completedSets > 0 -> "已存进训练记录 · ${state.completedSets} 组"
            else -> "这次没有完成任何一组，不会写进记录"
        }
        Text(
            text = saveText,
            style = MaterialTheme.typography.labelSmall,
            color = when {
                state.saveError != null -> MaterialTheme.colorScheme.error
                state.saving -> MaterialTheme.colorScheme.outline
                else -> MaterialTheme.colorScheme.primary
            },
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        // 流派经验：支线动作的价值就在这一行里，让用户看到「练了就算数」
        if (state.xpGains.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "流派经验 " + state.xpGains.joinToString(" · ") { "${it.branch.label} +${it.xp}" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        state.swapNote?.let { note ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onExit,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            Text(
                text = if (state.routineId > 0L) "回到计划" else "完成",
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}

/**
 * 破纪录横幅。
 *
 * 结算页是唯一值得「爽一下」的地方，所以给了个轻微的弹性放大：
 * 数据是异步补上来的，横幅出现时弹一次，比直接静态贴上去更像「刚打出来的成绩」。
 */
@Composable
private fun PrBanner(records: List<PrRecord>) {
    val pop = remember { Animatable(0.92f) }
    LaunchedEffect(records) {
        pop.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
            ),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
            }
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "🏆", fontSize = 24.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "新纪录 (New PR)！",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = "${records.size} 项成绩超过你自己的历史最好水平",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                )
            }
        }

        records.forEach { record ->
            val unit = record.metric.unit
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = record.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                    )
                    Text(
                        text = "原纪录 ${record.previousBest} $unit",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.65f),
                    )
                }
                Text(
                    text = "${record.value} $unit",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "+${record.delta}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SessionMessage(
    title: String,
    detail: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAction) { Text(actionLabel) }
    }
}

/** PR 的量词。计次动作用「次」，计时动作用「秒」。 */
private val PrMetric.unit: String
    get() = if (this == PrMetric.REPS) "次" else "秒"

/** mm:ss，超过一小时也按分钟继续累加（健身场景基本用不到小时）。 */
private fun formatDuration(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    return "%02d:%02d".format(safe / 60, safe % 60)
}
