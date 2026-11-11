package io.github.kylin33.aifitgym.ui.history

import java.util.Calendar

/** 历史页的时间/时长文案。minSdk 24 没有 java.time，统一用 Calendar。 */

private fun dayStart(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** 「今天 21:34」「昨天 07:10」「11月8日 21:34」「2025年12月1日」。 */
fun sessionTimeLabel(millis: Long): String {
    if (millis <= 0L) return "时间未知"
    val now = System.currentTimeMillis()
    val calendar = Calendar.getInstance().apply { timeInMillis = millis }
    val hour = calendar.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
    val minute = calendar.get(Calendar.MINUTE).toString().padStart(2, '0')
    val clock = "$hour:$minute"

    val daysAgo = ((dayStart(now) - dayStart(millis)) / 86_400_000L).toInt()
    val sameYear = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR) ==
        calendar.get(Calendar.YEAR)
    val month = calendar.get(Calendar.MONTH) + 1
    val day = calendar.get(Calendar.DAY_OF_MONTH)

    return when {
        daysAgo == 0 -> "今天 $clock"
        daysAgo == 1 -> "昨天 $clock"
        sameYear -> "${month}月${day}日 $clock"
        else -> "${calendar.get(Calendar.YEAR)}年${month}月${day}日"
    }
}

/** 秒 → 「48 分」「1 小时 12 分」；不到一分钟显示秒。 */
fun durationLabel(seconds: Int): String {
    if (seconds <= 0) return "—"
    if (seconds < 60) return "$seconds 秒"
    val minutes = seconds / 60
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours <= 0 -> "$minutes 分"
        rest == 0 -> "$hours 小时"
        else -> "$hours 小时 $rest 分"
    }
}
