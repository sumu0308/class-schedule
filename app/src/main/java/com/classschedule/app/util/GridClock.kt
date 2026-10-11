package com.classschedule.app.util

/**
 * 时间字符串与"当天第几分钟"的互转。
 *
 * 单独抽出来是因为课表网格排版、节次设置、默认值生成都要用同一套换算，
 * 散落多处容易出现"某个地方按 60 进制、另一个地方按 100 进制"的隐蔽 bug。
 */
object GridClock {

    /** "08:00" → 480；解析失败返回 0 */
    fun minutesOf(time: String): Int {
        val parts = time.trim().split(':', '：')
        val h = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return 0
        val m = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        return (h.coerceIn(0, 23)) * 60 + m.coerceIn(0, 59)
    }

    /** 480 → "08:00" */
    fun formatMinutes(total: Int): String {
        val t = ((total % 1440) + 1440) % 1440
        return "%02d:%02d".format(t / 60, t % 60)
    }

    /** 解析失败或时间倒置时返回 false，用于输入校验 */
    fun isValidRange(start: String, end: String): Boolean {
        val s = minutesOf(start)
        val e = minutesOf(end)
        return e > s && start.contains(':') && end.contains(':')
    }

    /** 时长文本，如 100 分钟 → "1 小时 40 分" */
    fun durationText(start: String, end: String): String {
        val d = minutesOf(end) - minutesOf(start)
        if (d <= 0) return "—"
        val h = d / 60
        val m = d % 60
        return when {
            h == 0 -> "$m 分"
            m == 0 -> "$h 小时"
            else -> "$h 小时 $m 分"
        }
    }
}
