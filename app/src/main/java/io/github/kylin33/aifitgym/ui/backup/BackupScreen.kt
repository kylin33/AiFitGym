package io.github.kylin33.aifitgym.ui.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.kylin33.aifitgym.data.backup.WorkoutBackupPayload
import io.github.kylin33.aifitgym.ui.history.durationLabel
import io.github.kylin33.aifitgym.ui.history.sessionTimeLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份与恢复。
 *
 * 走 SAF（系统文件选择器），不申请任何存储权限：Android 10 之后
 * `WRITE_EXTERNAL_STORAGE` 已经对下载目录无效，而用户亲手选的 Uri 自带读写授权，
 * 还能存到任意云盘客户端提供的目录里。
 */
@Composable
fun BackupScreen(
    state: BackupUiState,
    overview: BackupOverview,
    onBack: () -> Unit,
    onExport: (Uri) -> Unit,
    onPickImport: (Uri) -> Unit,
    onConfirmImport: () -> Unit,
    onCancelImport: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onMessageShown()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(onExport) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onPickImport) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
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
                        text = "备份与恢复",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }

            item(key = "intro") {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "把训练数据打包成一个 JSON 文件。换手机、重装 App、" +
                        "或者只是想留个存档，都可以用它把进度原样搬回来。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
            }

            item(key = "overview") { OverviewCard(overview) }

            item(key = "what") {
                Spacer(Modifier.height(14.dp))
                SectionCard(
                    title = "备份里有什么",
                    lines = listOf(
                        "✔ 训练计划（含内置模板与你自己改过的版本）",
                        "✔ 历史打卡记录、每一组的成绩",
                        "✔ 技能树进度：通关了哪些动作、6 条流派的经验",
                        "✘ 不含动作库 —— 2303 个动作随 App 版本更新，不占你备份的体积",
                    ),
                )
            }

            item(key = "actions") {
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { exportLauncher.launch(exportFileName()) },
                        enabled = !state.running,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(vertical = 13.dp),
                    ) {
                        Text(if (state.running) "处理中…" else "导出全量备份")
                    }
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                        enabled = !state.running,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(vertical = 13.dp),
                    ) {
                        Text("导入恢复数据")
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "导入是覆盖式的：本机现有的计划与历史会被替换成文件里的内容，无法撤销。" +
                        "所以动之前，先导一份现在的备份。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
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

    state.pending?.let { payload ->
        ConfirmImportDialog(
            payload = payload,
            onConfirm = onConfirmImport,
            onDismiss = onCancelImport,
        )
    }
}

@Composable
private fun OverviewCard(overview: BackupOverview) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = "本机现有数据",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            OverviewStat("计划", "${overview.routines} 套")
            OverviewStat("训练", "${overview.workouts} 次")
            OverviewStat("时长", durationLabel(overview.totalSeconds))
            OverviewStat("通关", "${overview.mastered} 个")
        }
    }
}

@Composable
private fun OverviewStat(label: String, value: String) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
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
private fun SectionCard(title: String, lines: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        lines.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun ConfirmImportDialog(
    payload: WorkoutBackupPayload,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val summary = remember(payload) { payload.summary() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("用这份备份覆盖本机数据？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "备份时间：${sessionTimeLabel(summary.exportedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "内容：${summary.describe()}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "确认后，本机现有的计划、历史与技能进度会被清空替换，无法撤销。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("覆盖恢复", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 文件名带日期，多次导出不会互相覆盖（系统会自动加 (1)(2)）。 */
private fun exportFileName(): String {
    val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    return "fitgym_backup_$stamp.json"
}
