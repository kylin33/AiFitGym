package io.github.kylin33.aifitgym.ui.ai

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.kylin33.aifitgym.data.ai.AiSettingsState
import io.github.kylin33.aifitgym.data.ai.PlanProposal
import io.github.kylin33.aifitgym.data.ai.ProposalChange
import io.github.kylin33.aifitgym.data.ai.ProposalChangeKind

/**
 * 练完复盘。
 *
 * 三段式：这次练了什么（本地数据）→ 教练怎么说（模型）→ 要不要照做（用户决定）。
 * 最后一段是关键：AI 只出建议，「应用」才写库，所以这里不需要撤销功能。
 */
@Composable
fun SessionReviewScreen(
    state: ReviewUiState,
    settings: AiSettingsState,
    onFeedbackChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = settings.ready && settings.preferences.autoCoach

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp),
    ) {
        item(key = "bar") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text(
                    text = "让教练复盘",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        item(key = "brief") {
            Spacer(Modifier.height(6.dp))
            val brief = state.brief
            if (brief == null) {
                if (!state.loading) {
                    Text(
                        text = state.error ?: "找不到这次训练的记录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                return@item
            }
            CardBox {
                Text(
                    text = brief.routineName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = buildString {
                        append(brief.whenText)
                        append(" · ").append(brief.minutes).append(" 分钟")
                        append(" · ").append(brief.setCount).append(" 组")
                        if (brief.plannedSets > 0) append("（计划 ").append(brief.plannedSets).append(" 组）")
                        append(" · ").append(brief.exerciseCount).append(" 个动作")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!ready) {
            item(key = "not_ready") {
                Spacer(Modifier.height(14.dp))
                CardBox {
                    Text(
                        text = "全程 AI 教练没开，或者 Key 还没填。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "开启后每次练完都能让教练看一次成绩，并调整下次的计划。" +
                            "不点复盘就不会产生 token 费用。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onOpenSettings) { Text("去设置") }
                }
            }
            return@LazyColumn
        }

        item(key = "feedback") {
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = state.feedback,
                onValueChange = onFeedbackChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("补一句（可不填）") },
                placeholder = { Text("今天肩有点紧，后半段没力气；下周只有三天能练") },
                minLines = 2,
                maxLines = 4,
                enabled = !state.streaming,
            )
        }

        item(key = "send") {
            Spacer(Modifier.height(12.dp))
            if (state.streaming) {
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Text("停止")
                }
            } else {
                Button(
                    onClick = onSend,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        text = if (state.asked) "再问一次" else "让教练看看",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "会把本次每组的实际成绩和这句话发给你配置的服务商，约 2~3k token。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        if (state.reply.isNotBlank()) {
            item(key = "reply") {
                Spacer(Modifier.height(16.dp))
                CardBox {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CoachDot()
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "教练",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.streaming) {
                            Spacer(Modifier.width(8.dp))
                            CircularProgressIndicator(
                                modifier = Modifier.width(12.dp).height(12.dp),
                                strokeWidth = 1.5.dp,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = state.reply,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        state.proposal?.let { proposal ->
            item(key = "proposal") {
                Spacer(Modifier.height(14.dp))
                ProposalCard(
                    proposal = proposal,
                    applying = state.applying,
                    applied = state.appliedRoutineId != null,
                    onApply = onApply,
                    onDiscard = onDiscard,
                )
            }
        }

        if (state.appliedRoutineId != null) {
            item(key = "applied") {
                Spacer(Modifier.height(12.dp))
                CardBox {
                    Text(
                        text = "已写进计划。下次开始训练就是新的安排。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        state.error?.let { error ->
            item(key = "error") {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ProposalCard(
    proposal: PlanProposal,
    applying: Boolean,
    applied: Boolean,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
) {
    CardBox {
        Text(
            text = "建议改动",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = when {
                proposal.isNew && proposal.targetName.isNotBlank() ->
                    "没找到叫「${proposal.targetName}」的计划，应用会新建一套「${proposal.draft.name}」"
                proposal.isNew -> "应用会新建一套「${proposal.draft.name}」"
                else -> "将改动「${proposal.target?.name}」"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (proposal.draft.reason.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = proposal.draft.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 程序体检：伤病部位、训练量、恢复 —— 模型漏了也会在这里露出来
        proposal.draft.warnings.forEach { warning ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = "⚠ $warning",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

        proposal.changes.forEach { change ->
            ChangeRow(change)
        }

        if (proposal.effectiveCount == 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "这套计划不用改，教练只是确认了一下。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            return@CardBox
        }

        Spacer(Modifier.height(12.dp))
        if (applied) {
            Text(
                text = "已应用",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDiscard, enabled = !applying) { Text("不用了") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onApply,
                    enabled = !applying,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    if (applying) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(14.dp).height(14.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("应用到计划")
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangeRow(change: ProposalChange) {
    val (symbol, color) = when (change.kind) {
        ProposalChangeKind.ADD -> "+" to MaterialTheme.colorScheme.primary
        ProposalChangeKind.REMOVE -> "−" to MaterialTheme.colorScheme.error
        ProposalChangeKind.CHANGE -> "~" to MaterialTheme.colorScheme.onSurface
        ProposalChangeKind.KEEP -> "=" to MaterialTheme.colorScheme.outline
    }
    Row(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = symbol,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.width(16.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = change.title,
                style = MaterialTheme.typography.bodySmall,
                color = if (change.kind == ProposalChangeKind.KEEP) {
                    MaterialTheme.colorScheme.outline
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = change.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun CardBox(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
    ) {
        Column { content() }
    }
}
