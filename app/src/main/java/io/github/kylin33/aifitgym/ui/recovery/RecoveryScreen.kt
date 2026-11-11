package io.github.kylin33.aifitgym.ui.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.kylin33.aifitgym.data.recovery.MuscleLoad
import io.github.kylin33.aifitgym.data.recovery.RecoveryPolicy
import io.github.kylin33.aifitgym.data.recovery.RecoverySnapshot
import io.github.kylin33.aifitgym.data.recovery.VolumeSnapshot
import io.github.kylin33.aifitgym.ui.components.FatigueOrange
import io.github.kylin33.aifitgym.ui.components.FatigueRed
import io.github.kylin33.aifitgym.ui.components.FatigueYellow
import io.github.kylin33.aifitgym.ui.components.MuscleBodyMap
import io.github.kylin33.aifitgym.ui.components.MuscleHeatmap
import kotlin.math.roundToInt

/**
 * 恢复 / 训练量看板（Tab 3）。
 *
 * 两种模式共用同一张人体图，但表达两件完全不同的事：
 *  - **恢复**：用 `MuscleBodyMap(fatigue = true)` 的黄橙红，越红说明离上次练它越近、组数越多；
 *  - **训练量**：用 `MuscleHeatmap` 的品牌色连续色阶，越深说明这 7/30 天练得越多。
 *
 * 恢复模式是「现在」，训练量模式是「这段时间」，所以切模式时统计口径没变、只是衰减系数从 1.0 变成 0。
 */
@Composable
fun RecoveryScreen(
    state: RecoveryViewModel.UiState,
    onModeChange: (RecoveryMode) -> Unit,
    onRangeChange: (VolumeRange) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 疲劳度随时间衰减，每次切回这个 Tab 都重算一次，避免看到几小时前的颜色
    LaunchedEffect(Unit) { onRefresh() }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
    ) {
        item(key = "header") {
            Column {
                Text(
                    text = "恢复",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        state.error != null -> "读取训练记录失败"
                        state.mode == RecoveryMode.RECOVERY -> "按过去 72 小时的组数与时间衰减估算，越红越该休息"
                        else -> "看这段时间哪个肌群练得多、哪个被落下了"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModePill("恢复状态", state.mode == RecoveryMode.RECOVERY) {
                        onModeChange(RecoveryMode.RECOVERY)
                    }
                    ModePill("训练量", state.mode == RecoveryMode.VOLUME) {
                        onModeChange(RecoveryMode.VOLUME)
                    }
                }
                if (state.mode == RecoveryMode.VOLUME) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VolumeRange.entries.forEach { range ->
                            ModePill(range.label, state.range == range, small = true) {
                                onRangeChange(range)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
        }

        if (state.error != null) {
            item(key = "error") {
                NoticeCard(
                    title = "读不到数据",
                    body = "${state.error}。下拉页面或重开 App 再试。",
                    tone = MaterialTheme.colorScheme.errorContainer,
                    textColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        when (state.mode) {
            RecoveryMode.RECOVERY -> recoveryItems(state)
            RecoveryMode.VOLUME -> volumeItems(state.volume)
        }
    }
}

// ------------------------------------------------------------ 恢复模式

private fun androidx.compose.foundation.lazy.LazyListScope.recoveryItems(
    state: RecoveryViewModel.UiState,
) {
    val snapshot = state.recovery

    item(key = "recovery-map") {
        Column {
            SurfaceCard {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                ) {
                    if (snapshot.isEmpty) {
                        EmptyMapHint(
                            title = if (state.loading) "正在计算…" else "过去 72 小时没有训练记录",
                            body = if (state.loading) "" else "练完一场，这里会按组数点亮对应肌群，并随时间慢慢褪色",
                        )
                    } else {
                        MuscleBodyMap(
                            levels = snapshot.levels,
                            modifier = Modifier.fillMaxSize(),
                            fatigue = true,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                FatigueLegend()
            }
        }
    }

    if (!snapshot.isEmpty) {
        item(key = "recovery-summary") {
            Spacer(Modifier.height(14.dp))
            RecoverySummary(snapshot)
        }

        item(key = "recovery-title") {
            Spacer(Modifier.height(22.dp))
            SectionTitle("还在恢复的肌群", "${snapshot.recoveringCount} 处")
        }

        val loads = snapshot.loads.filter { it.fatigue >= RecoveryPolicy.READY_BELOW }
        if (loads.isEmpty()) {
            item(key = "recovery-all-ready") {
                NoticeCard(
                    title = "全部满血",
                    body = "72 小时内练到的肌群都已经恢复，今天可以放心练任何部位。",
                    tone = MaterialTheme.colorScheme.primaryContainer,
                    textColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        } else {
            loads.take(8).forEach { load ->
                item(key = "load-${load.slug}") {
                    MuscleLoadRow(load)
                }
            }
        }
    }
}

@Composable
private fun RecoverySummary(snapshot: RecoverySnapshot) {
    val deepest = snapshot.deepest
    val headline = when {
        deepest == null -> "全部恢复"
        deepest.fatigue >= RecoveryPolicy.SORE_BELOW -> "今天建议避开${deepest.nameCn}"
        else -> "只有轻微疲劳"
    }
    val detail = deepest?.let {
        "过去 72 小时 ${snapshot.totalSets} 组 · ${it.nameCn} ${it.sets} 组、约 ${formatHours(it.remainingHours)}后彻底恢复"
    } ?: "过去 72 小时没有有效训练"

    SurfaceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${snapshot.readyCount}/${snapshot.muscleCount}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "已恢复",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** 每条：肌群名 + 状态 + 疲劳条 + 剩余恢复时间。 */
@Composable
private fun MuscleLoadRow(load: MuscleLoad) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = load.nameCn,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(loadColor(load.fatigue).copy(alpha = 0.16f))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = load.status,
                        style = MaterialTheme.typography.labelSmall,
                        color = loadColor(load.fatigue),
                    )
                }
            }
            Spacer(Modifier.height(7.dp))
            LoadBar(fraction = load.fatigue, color = loadColor(load.fatigue))
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${load.sets} 组",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${formatHours(load.remainingHours)}后恢复",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

// ------------------------------------------------------------ 训练量模式

private fun androidx.compose.foundation.lazy.LazyListScope.volumeItems(snapshot: VolumeSnapshot) {
    item(key = "volume-stats") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCell("训练次数", "${snapshot.sessions}", "次", Modifier.weight(1f))
            StatCell("总组数", "${snapshot.totalSets}", "组", Modifier.weight(1f))
            StatCell("总时长", "${snapshot.durationSec / 60}", "分", Modifier.weight(1f))
        }
    }

    item(key = "volume-map") {
        Spacer(Modifier.height(12.dp))
        SurfaceCard {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
            ) {
                if (snapshot.isEmpty) {
                    EmptyMapHint(
                        title = "这 ${snapshot.days} 天还没有训练",
                        body = "换个时间窗，或者先去计划页练一场",
                    )
                } else {
                    MuscleHeatmap(
                        muscleScores = snapshot.scores(),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "颜色深浅按这 ${snapshot.days} 天内该肌群的加权组数（主发力 1.0 / 辅助 0.4）相对最大值归一化",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }

    if (!snapshot.isEmpty) {
        item(key = "volume-title") {
            Spacer(Modifier.height(22.dp))
            SectionTitle("训练量排行", snapshot.top?.let { "${it.nameCn}最多" } ?: "")
        }

        snapshot.muscles.take(10).forEach { muscle ->
            item(key = "volume-${muscle.slug}") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = muscle.nameCn,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "${muscle.sets} 组",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        Spacer(Modifier.height(7.dp))
                        LoadBar(
                            fraction = muscle.normalized,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "${(muscle.share * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (snapshot.gaps.isNotEmpty()) {
            item(key = "volume-gaps") {
                Spacer(Modifier.height(14.dp))
                NoticeCard(
                    title = "这 ${snapshot.days} 天几乎没练到",
                    body = snapshot.gaps.joinToString("、") + "\n长期偏科会让关节和体态往一边倒，排计划时补两三个动作就够。",
                    tone = MaterialTheme.colorScheme.secondaryContainer,
                    textColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        if (snapshot.focusShare >= 0.45f) {
            item(key = "volume-focus") {
                Spacer(Modifier.height(14.dp))
                NoticeCard(
                    title = "练得有点集中",
                    body = "${snapshot.top?.nameCn}占了全部训练量的 ${(snapshot.focusShare * 100).roundToInt()}%，" +
                        "其余肌群分到的组数偏少。",
                    tone = MaterialTheme.colorScheme.surfaceVariant,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ------------------------------------------------------------ 公共小件

@Composable
private fun ModePill(
    label: String,
    selected: Boolean,
    small: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface
            )
            .clickable(onClick = onClick)
            .padding(
                horizontal = if (small) 12.dp else 16.dp,
                vertical = if (small) 5.dp else 8.dp,
            ),
    ) {
        Text(
            text = label,
            style = if (small) MaterialTheme.typography.labelSmall
            else MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SurfaceCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
    ) {
        content()
    }
}

@Composable
private fun SectionTitle(title: String, trailing: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = trailing,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun LoadBar(fraction: Float, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(CircleShape)
                .background(color),
        )
    }
}

@Composable
private fun StatCell(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(3.dp))
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun NoticeCard(
    title: String,
    body: String,
    tone: Color,
    textColor: Color,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(tone)
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = textColor,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = textColor.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun EmptyMapHint(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (body.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** 图例：灰（已恢复）→ 黄 → 橙 → 红。 */
@Composable
private fun FatigueLegend() {
    val base = lerp(
        MaterialTheme.colorScheme.surface,
        MaterialTheme.colorScheme.onSurface,
        0.11f,
    )
    val entries = listOf(
        "已恢复" to base,
        "轻微" to FatigueYellow.copy(alpha = 0.55f),
        "恢复中" to FatigueOrange,
        "酸痛" to FatigueRed,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        entries.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(color),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 疲劳度 → 颜色，和人体图恢复期的那套配色一致。 */
private fun loadColor(fatigue: Float): Color = when (RecoveryPolicy.levelOf(fatigue)) {
    0 -> Color(0xFF8A9A90)
    1 -> FatigueYellow
    2 -> FatigueOrange
    else -> FatigueRed
}

/** 剩余恢复时间：不到 1 小时、X 小时、X 天。 */
private fun formatHours(hours: Float): String = when {
    hours < 1f -> "不到 1 小时"
    hours < 48f -> "${hours.roundToInt()} 小时"
    else -> "${(hours / 24f).roundToInt()} 天"
}
