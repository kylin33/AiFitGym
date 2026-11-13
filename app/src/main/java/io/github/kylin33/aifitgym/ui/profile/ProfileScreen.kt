package io.github.kylin33.aifitgym.ui.profile

import android.content.Context
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kylin33.aifitgym.ui.components.rememberClipStore
import io.github.kylin33.aifitgym.ui.history.durationLabel

/**
 * 「我的」：把所有非训练类的东西收到一个 Tab 里。
 *
 * 原来是 4 个平级 Tab（计划 / 技能树 / 恢复 / 历史），底栏被塞满，
 * 而历史和「设置类」内容本来就不该和训练入口平起平坐 —— 收回一层更清爽。
 *
 * 命名用「我的」而不是「我」或「个人中心」：两个汉字在四个 Tab 里排版均衡，
 * 且微信/支付宝/淘宝都是这个词，用户不用学。
 */
@Composable
fun ProfileScreen(
    stats: ProfileStats,
    exerciseCount: Int,
    clearing: Boolean,
    message: String?,
    onOpenHistory: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenCoach: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenDisclaimer: () -> Unit,
    onClearAllData: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAbout by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showClipClear by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    // 按需下载的演示短片存在私有目录，用户既看不到也删不掉，给一个能看占用、能清空的入口
    val clipStore = rememberClipStore()
    val localClips by clipStore.local.collectAsStateWithLifecycle()

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        onMessageShown()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
        ) {
            item(key = "header") {
                Column {
                    Text(
                        text = "我的",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "训练数据都存在这台手机上",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }

            item(key = "stats") {
                StatsCard(stats)
                Spacer(Modifier.height(6.dp))
            }

            item(key = "section_ai") { SectionLabel("AI") }
            item(key = "coach") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    EntryRow(
                        icon = Icons.Filled.Star,
                        title = "AI 教练",
                        subtitle = "聊训练、排计划；模型与密钥也在这里配",
                        onClick = onOpenCoach,
                    )
                }
                Spacer(Modifier.height(6.dp))
            }

            item(key = "section_training") { SectionLabel("训练") }
            item(key = "history") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    EntryRow(
                        icon = Icons.Filled.DateRange,
                        title = "训练历史",
                        subtitle = if (stats.workouts == 0) {
                            "练完一场，这里会留下完整记录"
                        } else {
                            "共 ${stats.workouts} 次 · 累计 ${durationLabel(stats.totalSeconds)} · 点进去可以改成绩"
                        },
                        onClick = onOpenHistory,
                    )
                }
                Spacer(Modifier.height(6.dp))
            }

            item(key = "section_data") { SectionLabel("数据") }
            item(key = "backup") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    EntryRow(
                        icon = Icons.Filled.Settings,
                        title = "数据备份与恢复",
                        subtitle = "导出一个 JSON 文件，换手机、重装都能搬回来",
                        onClick = onOpenBackup,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 60.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    )
                    EntryRow(
                        icon = Icons.Filled.Delete,
                        title = "清空所有数据",
                        subtitle = if (clearing) "正在清空…" else "回到刚装好的状态，动作库保留",
                        onClick = { showClearConfirm = true },
                        danger = true,
                        enabled = !clearing,
                    )
                }
                Spacer(Modifier.height(6.dp))
            }

            item(key = "section_general") { SectionLabel("通用") }
            item(key = "general") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    NotificationRow()
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 60.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    )
                    EntryRow(
                        icon = Icons.Filled.Info,
                        title = "关于 FitGym",
                        subtitle = "版本、动作库与数据说明",
                        onClick = { showAbout = true },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 60.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    )
                    EntryRow(
                        icon = Icons.Filled.Lock,
                        title = "隐私政策",
                        subtitle = "数据存在哪、什么时候会离开这台手机",
                        onClick = onOpenPrivacy,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 60.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    )
                    EntryRow(
                        icon = Icons.Filled.Warning,
                        title = "免责声明",
                        subtitle = "训练风险、AI 内容的局限与责任范围",
                        onClick = onOpenDisclaimer,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 60.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    )
                    EntryRow(
                        icon = Icons.Filled.PlayArrow,
                        title = "演示短片",
                        subtitle = if (localClips.isEmpty()) {
                            "缺演示的动作可以单独下载 · 当前没有额外占用"
                        } else {
                            "已下载 ${localClips.size} 个 · ${clipSizeLabel(localClips.values.sum())}，点一下可清理"
                        },
                        onClick = { showClipClear = true },
                        enabled = localClips.isNotEmpty(),
                    )
                }
            }

            item(key = "footer") {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "内置动作库 $exerciseCount 个 · 徒手优先，器械可选",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }

    if (showAbout) {
        AboutDialog(
            exerciseCount = exerciseCount,
            onOpenPrivacy = {
                showAbout = false
                onOpenPrivacy()
            },
            onDismiss = { showAbout = false },
        )
    }

    if (showClearConfirm) {
        ClearDataDialog(
            onConfirm = {
                showClearConfirm = false
                onClearAllData()
            },
            onDismiss = { showClearConfirm = false },
        )
    }

    if (showClipClear) {
        AlertDialog(
            onDismissRequest = { showClipClear = false },
            title = { Text("清理已下载的短片？") },
            text = {
                Text(
                    text = "会删除 ${localClips.size} 个按需下载的演示短片，" +
                        "释放 ${clipSizeLabel(localClips.values.sum())}。" +
                        "内置短片不受影响，删掉的动作下次点开再点一下「下载」就能补回来。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        clipStore.clearDownloaded()
                        showClipClear = false
                    },
                ) { Text("清理") }
            },
            dismissButton = {
                TextButton(onClick = { showClipClear = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun StatsCard(stats: ProfileStats) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatCell("训练次数", "${stats.workouts}", Modifier.weight(1f))
        StatDivider()
        StatCell("累计时长", durationLabel(stats.totalSeconds), Modifier.weight(1f))
        StatDivider()
        StatCell(
            label = "通关动作",
            value = if (stats.totalBosses > 0) "${stats.mastered}/${stats.totalBosses}" else "—",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun StatDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(26.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 8.dp),
    )
}

@Composable
private fun EntryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: String? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val titleColor = if (danger) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val bubbleColor = if (danger) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val iconTint = if (danger) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp)
            .alpha(if (enabled) 1f else 0.55f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(bubbleColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = titleColor,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = trailing ?: "›",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * 通知权限这一行。
 *
 * 不是「设置项」而是一个跳转：权限只有系统能改，App 里能做的是把状态说清楚、
 * 一键把用户送到系统设置页。回到前台时重新读一次，开了就立刻变成「已开启」。
 */
@Composable
private fun NotificationRow() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember { mutableStateOf(notificationsEnabled(context)) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) enabled = notificationsEnabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    EntryRow(
        icon = Icons.Filled.Notifications,
        title = "通知权限",
        subtitle = if (enabled) {
            "已开启 · 锁屏也能看到组间倒计时"
        } else {
            "未开启 · 计时照常，但锁屏看不到倒计时"
        },
        trailing = if (enabled) "已开启" else "去开启",
        onClick = { openNotificationSettings(context) },
    )
}

@Composable
private fun AboutDialog(
    exerciseCount: Int,
    onOpenPrivacy: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关于 FitGym") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "内置动作库 $exerciseCount 个，按部位和器械筛选，徒手动作优先。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "训练计划、历史成绩、技能进度全部存在本机数据库里。" +
                        "只有你在「AI 教练」里提问时，才会把训练摘要发给你自己配置的那个服务商；" +
                        "另外，你点「下载」补动作演示短片时会去 GitHub 拉取。" +
                        "除此之外 App 不联网。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "换手机或重装前，去「数据备份与恢复」导出一份 JSON 就能带走" +
                        "（不含 AI 对话与密钥）。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "训练有风险，量力而行。详细的隐私说明与免责条款见「通用」里的两页。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
        dismissButton = {
            TextButton(onClick = onOpenPrivacy) { Text("隐私政策") }
        },
    )
}

/**
 * 清空确认。
 *
 * 只弹一次确认，但把「删什么 / 留什么 / 不可撤销」三件事全写清楚 ——
 * 破坏性操作要么不做，要么让对方在点下去之前就知道代价，靠连点两次确认并不能让人更谨慎。
 */
@Composable
private fun ClearDataDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("清空所有训练数据？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "会删除：全部训练计划（含你自建的）、所有历史记录与每组成绩、" +
                        "技能树进度与 6 条流派的经验。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "会保留：内置动作库，以及通知权限这类系统设置。" +
                        "清空后自动恢复 3 套内置模板，等于刚装好 App 的样子。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "此操作无法撤销。只是想换手机的话，先去「数据备份与恢复」导出一份。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("清空", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun notificationsEnabled(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    runCatching { context.startActivity(intent) }
        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } }
}

/** 下载占用的展示：不到 1 MB 用 KB，避免「0.0 MB」这种没信息量的写法。 */
private fun clipSizeLabel(bytes: Long): String {
    if (bytes < 1024 * 1024) return "${(bytes / 1024).coerceAtLeast(1)} KB"
    return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
}
