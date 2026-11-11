package io.github.kylin33.aifitgym.ui.workout

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.kylin33.aifitgym.data.skill.BranchBreakthrough
import io.github.kylin33.aifitgym.data.skill.BranchPromotion
import io.github.kylin33.aifitgym.data.skill.BranchXp
import io.github.kylin33.aifitgym.ui.components.ExerciseImage

/**
 * 结算时的「晋升」弹窗。
 *
 * 这是整个 App 的情绪高点，所以不用 AlertDialog 那种灰白对话框：
 * 自带一块暖色头部、放大弹入、把下一阶动作的演示图直接摆出来。
 * 两种事件共用这一个弹窗 —— 考级通关（晋升）和 XP 换来的熟练度突破。
 *
 * [canSwap] 为 false（考级模式没有计划）时不给替换按钮，只提示知道。
 */
@Composable
fun PromotionDialog(
    promotions: List<BranchPromotion>,
    breakthroughs: List<BranchBreakthrough>,
    xpGains: List<BranchXp>,
    canSwap: Boolean,
    onSwap: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (promotions.isEmpty() && breakthroughs.isEmpty()) return

    val pop = remember { Animatable(0.9f) }
    LaunchedEffect(promotions, breakthroughs) {
        pop.snapTo(0.9f)
        pop.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
            ),
        )
    }

    val first = promotions.firstOrNull()
    val swapTargets = promotions.filter { it.nextId != null && it.nextName != null }
    val allClear = promotions.isNotEmpty() && promotions.all { it.allClear }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                },
        ) {
            Column {
                // 头部：暖色块 + 大字，先把「成了」这件事喊出来
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 22.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = if (allClear) "🏅" else "🎉", fontSize = 44.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = when {
                            allClear -> "全链通关！"
                            first != null -> "晋升 Lv.${first.toTier}！"
                            else -> "熟练度突破！"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = when {
                            allClear -> "这条进阶线你已经走到最后了"
                            first != null -> "${first.branch.label}线 · ${first.clearedName} 已通关"
                            else -> "攒够的经验换成了更低的门槛"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                    )
                }

                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    promotions.forEach { PromotionRow(it) }

                    breakthroughs.forEach { breakthrough ->
                        BreakthroughRow(breakthrough)
                        Spacer(Modifier.height(10.dp))
                    }

                    if (xpGains.isNotEmpty()) {
                        Text(
                            text = "本次获得 " + xpGains.joinToString(" · ") {
                                "${it.branch.label} +${it.xp} XP"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.height(10.dp))
                    }

                    if (swapTargets.isNotEmpty()) {
                        Text(
                            text = if (canSwap) {
                                "要把计划里的「${swapTargets.first().clearedName}」直接换成新动作吗？"
                            } else {
                                "这次是单独考级，没有要改的计划；想练下一阶可以直接在技能树里点「加入计划」。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))

                        if (canSwap) {
                            Button(
                                onClick = onSwap,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Text("一键替换成 ${swapTargets.first().nextName}")
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }

                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("先留着，我自己安排")
                    }
                }
            }
        }
    }
}

/** 一条晋升：左边通关的动作，右边新解锁的动作。 */
@Composable
private fun PromotionRow(promotion: BranchPromotion) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "${promotion.branch.label} · Lv.${promotion.fromTier} 通关",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = promotion.clearedName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (promotion.nextName != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "↓ 下一阶：Lv.${promotion.toTier} ${promotion.nextName}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            } else {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "已经是最后一阶了",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (promotion.nextImage != null) {
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(width = 64.dp, height = 64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                ExerciseImage(
                    path = promotion.nextImage,
                    contentDescription = promotion.nextName,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** 突破：不涉及新动作，只是一句「门槛降了」。 */
@Composable
private fun BreakthroughRow(breakthrough: BranchBreakthrough) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = "⚡", fontSize = 18.sp)
        Column(Modifier.weight(1f)) {
            Text(
                text = "${breakthrough.branch.label} 熟练度突破 · 要求降 ${breakthrough.discountPercent}%",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (breakthrough.newLabel.isNotBlank()) {
                Text(
                    text = "现在完成 ${breakthrough.newLabel} 即可通关",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
