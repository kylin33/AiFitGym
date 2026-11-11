package io.github.kylin33.aifitgym.ui.skill

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.kylin33.aifitgym.data.local.ExerciseEntity
import io.github.kylin33.aifitgym.data.skill.BossState
import io.github.kylin33.aifitgym.data.skill.BranchBoard
import io.github.kylin33.aifitgym.data.skill.BranchBoss
import io.github.kylin33.aifitgym.data.skill.SkillBranch
import io.github.kylin33.aifitgym.data.skill.SkillRules
import io.github.kylin33.aifitgym.ui.components.ExerciseImage

/**
 * 技能树（Tab 2）。
 *
 * 结构刻意做得很平：顶部 6 个流派 Tab，中间当前流派的等级 / XP，下面就是 8 张主干卡片。
 * 点卡片弹详情，里面除了通关条件，还挂着 15 个「同流派强化动作」——
 * 2255 个自由动作就是从这里回到训练里的。
 */
@Composable
fun SkillTreeScreen(
    state: SkillTreeState,
    onSelectBranch: (SkillBranch) -> Unit,
    onOpenBoss: (BranchBoss) -> Unit,
    onCloseBoss: () -> Unit,
    onAddToRoutine: (Long, ExerciseEntity) -> Unit,
    onStartBoss: (String) -> Unit,
    onOpenExerciseDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val board = state.board

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
        ) {
            item(key = "header") {
                Column {
                    Text(
                        text = "技能树",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "6 条流派 × 8 阶考级。练哪条线的动作都给那条线涨经验，攒够 300 XP 能降低门槛。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    BranchTabs(selected = state.selected, onSelect = onSelectBranch)
                    Spacer(Modifier.height(4.dp))
                }
            }

            item(key = "board") {
                if (board == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "正在读取进度…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                } else {
                    BoardHeader(board)
                }
            }

            if (board != null) {
                itemsIndexed(
                    items = board.bosses,
                    key = { _, boss -> "${boss.branch.key}-${boss.tier}" },
                ) { index, boss ->
                    BossRow(
                        boss = boss,
                        isFirst = index == 0,
                        isLast = index == board.bosses.lastIndex,
                        onClick = { onOpenBoss(boss) },
                    )
                }
            }
        }
    }

    state.sheet?.let { sheet ->
        BossDetailDialog(
            sheet = sheet,
            routines = state.routines,
            onDismiss = onCloseBoss,
            onAddToRoutine = onAddToRoutine,
            onStartBoss = {
                onCloseBoss()
                sheet.boss.exercise?.let { onStartBoss(it.id) }
            },
            onOpenDetail = {
                sheet.boss.exercise?.let {
                    onCloseBoss()
                    onOpenExerciseDetail(it.id)
                }
            },
        )
    }
}

/** 六个流派横向切换。用滚动的 Row 而不是 TabRow：6 个中文标签在窄屏上放不下。 */
@Composable
private fun BranchTabs(selected: SkillBranch, onSelect: (SkillBranch) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SkillBranch.entries.forEach { branch ->
            val active = branch == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface
                    )
                    .clickable { onSelect(branch) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = branch.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 当前流派的等级与 XP 进度条。 */
@Composable
private fun BoardHeader(board: BranchBoard) {
    val accent = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "Lv.${board.currentTier}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
            Text(
                text = " / ${SkillRules.MAX_TIER}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "已通关 ${board.masteredCount}/${board.bosses.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(8.dp))

        // XP 进度条：自己画，能顺手把「还差多少」标在右边
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(board.xpFraction)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(accent),
            )
        }

        Spacer(Modifier.height(6.dp))

        Row {
            Text(
                text = "经验 ${board.availableXp} / ${SkillRules.XP_PER_BREAKTHROUGH} XP",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = when {
                    board.progress.discountPercent >= SkillRules.MAX_DISCOUNT -> "门槛已降到底"
                    board.xpToBreakthrough > 0 -> "再攒 ${board.xpToBreakthrough} XP 可降 20% 门槛"
                    else -> "可触发熟练度突破"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        board.current?.let { boss ->
            if (boss.discountPercent > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "⚡ 熟练度突破生效：${boss.requirement.label()} → ${boss.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

/**
 * 一张主干卡片 + 上下的连接线。
 *
 * 行高固定 104dp，Canvas 才能用确定的中点算线段端点；
 * 线画在行内、首尾各半段，LazyColumn 回收 item 时不会错位。
 */
@Composable
private fun BossRow(
    boss: BranchBoss,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
) {
    val locked = boss.state == BossState.LOCKED
    val mastered = boss.state == BossState.MASTERED
    val accent = MaterialTheme.colorScheme.primary
    val lineColor = if (locked) MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    else accent.copy(alpha = 0.55f)
    val dash = if (locked) PathEffect.dashPathEffect(floatArrayOf(9f, 9f)) else null

    Row(modifier = Modifier
        .fillMaxWidth()
        .height(104.dp)) {
        Box(
            modifier = Modifier
                .width(58.dp)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val radius = 20.dp.toPx()
                val stroke = 3.dp.toPx()

                if (!isFirst) {
                    drawLine(lineColor, Offset(cx, 0f), Offset(cx, cy - radius), stroke, pathEffect = dash)
                }
                if (!isLast) {
                    drawLine(lineColor, Offset(cx, cy + radius), Offset(cx, size.height), stroke, pathEffect = dash)
                }
            }

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            mastered -> accent
                            locked -> MaterialTheme.colorScheme.surfaceVariant
                            else -> MaterialTheme.colorScheme.surface
                        }
                    )
                    .border(
                        width = if (mastered) 0.dp else 2.dp,
                        color = when {
                            mastered -> accent
                            locked -> MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                            else -> accent
                        },
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = when {
                        mastered -> "✓"
                        locked -> "🔒"
                        else -> "${boss.tier}"
                    },
                    fontSize = if (locked) 14.sp else 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        mastered -> MaterialTheme.colorScheme.onPrimary
                        locked -> MaterialTheme.colorScheme.outline
                        else -> accent
                    },
                )
            }
        }

        Spacer(Modifier.width(4.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(vertical = 7.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    when {
                        mastered -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        boss.state == BossState.CURRENT -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
                        else -> MaterialTheme.colorScheme.surface
                    }
                )
                .clickable(enabled = boss.exercise != null, onClick = onClick)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Lv.${boss.tier}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (locked) MaterialTheme.colorScheme.outline else accent,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = boss.exercise?.name ?: "动作库里已找不到",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (locked) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                StateChip(boss)
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = progressLine(boss),
                style = MaterialTheme.typography.labelSmall,
                color = if (locked) MaterialTheme.colorScheme.outline
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (boss.state == BossState.CURRENT) {
                Spacer(Modifier.height(6.dp))
                val target = boss.effective.value.coerceAtLeast(1)
                val fraction = (boss.best.toFloat() / target).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(accent),
                    )
                }
            }
        }
    }
}

/** 卡面第二行：通关看条件，当前看最好成绩/目标，锁着看还差几阶。 */
private fun progressLine(boss: BranchBoss): String {
    if (boss.exercise == null) return "动作数据缺失，跳过"
    return when (boss.state) {
        BossState.MASTERED -> "已通关 · ${boss.label}"
        BossState.CURRENT -> {
            val bestText = if (boss.best <= 0) "还没练过" else "最好 ${boss.best} ${boss.unit}"
            "$bestText / 目标 ${boss.label}"
        }

        BossState.LOCKED -> "先通关 Lv.${boss.tier - 1}"
    }
}

@Composable
private fun StateChip(boss: BranchBoss) {
    val (label, bg, fg) = when (boss.state) {
        BossState.MASTERED -> Triple(
            "已通关",
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.onPrimary,
        )

        BossState.CURRENT -> Triple(
            "正在考级",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )

        BossState.LOCKED -> Triple(
            "未解锁",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.outline,
        )
    }

    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
    }
}

/**
 * 节点详情。
 *
 * 上半部分是这个动作本身（图 + 条件 + 两个按钮），
 * 下半部分是「突破瓶颈」：同流派同肌群的 15 个支线动作，点一下就能加进计划。
 * 单靠这一块，2255 个自由动作就都回到了训练流里。
 */
@Composable
private fun BossDetailDialog(
    sheet: BossSheet,
    routines: List<io.github.kylin33.aifitgym.data.local.RoutineSummary>,
    onDismiss: () -> Unit,
    onAddToRoutine: (Long, ExerciseEntity) -> Unit,
    onStartBoss: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    val boss = sheet.boss
    val exercise = boss.exercise ?: return
    var pending by remember { mutableStateOf<ExerciseEntity?>(null) }
    var showNoRoutine by remember { mutableStateOf(false) }

    fun add(exercise: ExerciseEntity) {
        when {
            routines.isEmpty() -> showNoRoutine = true
            routines.size == 1 -> onAddToRoutine(routines.first().routine.routineId, exercise)
            else -> pending = exercise
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        ExerciseImage(
                            path = exercise.image,
                            contentDescription = exercise.name,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "${boss.branch.label} · Lv.${boss.tier}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = exercise.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = exercise.equipmentCn,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                InfoLine("通关条件", boss.label + if (boss.discountPercent > 0) "（已降 ${boss.discountPercent}%）" else "")
                InfoLine("历史最好", if (boss.best <= 0) "还没练过" else "单组 ${boss.best} ${boss.unit}")
                InfoLine(
                    "当前状态",
                    when (boss.state) {
                        BossState.MASTERED -> "已通关"
                        BossState.CURRENT -> if (boss.gap > 0) "还差 ${boss.gap} ${boss.unit}" else "已达标，再练一次就通关"
                        BossState.LOCKED -> "先通关 Lv.${boss.tier - 1}"
                    },
                )
                InfoLine("经验", "练这个动作每组 +${SkillRules.XP_PER_SET} XP（${boss.branch.label}）")

                Spacer(Modifier.height(14.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { add(exercise) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("加入计划")
                    }
                    Button(
                        onClick = onStartBoss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("直接开始考级")
                    }
                }

                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onOpenDetail, modifier = Modifier.fillMaxWidth()) {
                    Text("查看动作详情与步骤")
                }

                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                )
                Spacer(Modifier.height(14.dp))

                Text(
                    text = "突破瓶颈：同流派强化动作（+${SkillRules.XP_PER_SET} XP/组）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "主干太难就先练这些变式，经验和考级判定两条线都算数。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))

                when {
                    sheet.loading -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(96.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    sheet.accessories.isEmpty() -> Text(
                        text = "这个流派暂时没有合适的支线动作，直接练主干也行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )

                    else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(sheet.accessories, key = { it.id }) { item ->
                            AccessoryCard(item = item, onClick = { add(item) })
                        }
                    }
                }

                if (!sheet.loading && sheet.accessories.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "点卡片直接加进计划",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Start,
                    )
                }

                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("收起")
                }
            }
        }
    }

    pending?.let { exerciseToAdd ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("加到哪套计划") },
            text = {
                Column {
                    routines.forEach { summary ->
                        Text(
                            text = summary.routine.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    onAddToRoutine(summary.routine.routineId, exerciseToAdd)
                                    pending = null
                                }
                                .padding(vertical = 12.dp, horizontal = 6.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pending = null }) { Text("取消") }
            },
        )
    }

    if (showNoRoutine) {
        AlertDialog(
            onDismissRequest = { showNoRoutine = false },
            title = { Text("还没有训练计划") },
            text = { Text("先到「计划」页新建一套，再回来把动作加进去。") },
            confirmButton = {
                TextButton(onClick = { showNoRoutine = false }) { Text("知道了") }
            },
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(76.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 支线动作卡：图 + 名字 + 主肌群，点一下加入计划。 */
@Composable
private fun AccessoryCard(item: ExerciseEntity, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(120.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .clickable(onClick = onClick)
            .padding(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            ExerciseImage(
                path = item.image,
                contentDescription = item.name,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = item.targetCn,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
