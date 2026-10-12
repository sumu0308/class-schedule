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

    /**
     * 480 → "8:00"（去掉小时的补零）。
     *
     * 课表左边的时间刻度栏很窄，`08:00` 里那个前导零纯属浪费宽度；
     * 弹窗里的时间输入仍然用 [formatMinutes] 的 `08:00`，便于对齐阅读。
     */
    fun formatMinutesCompact(total: Int): String {
        val t = ((total % 1440) + 1440) % 1440
        return "%d:%02d".format(t / 60, t % 60)
    }

    /** 严格匹配 "HH:MM"：小时 00-23、分钟 00-59，两位补零 */
    private val STRICT_TIME = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")

    /**
     * 严格解析 "HH:MM" → 当天第几分钟；格式不合法返回 null。
     *
     * 与 [minutesOf] 的宽容解析分工明确：minutesOf 负责把"已经存进去的数据"
     * 算成分钟数，容错是美德；parseStrict 负责判断"要不要把用户输入存进去"，
     * 严格是美德。没有这一层，"08:70" 会被 minutesOf 容错成 08:59 之后
     * 骗过旧版 isRange 检查，脏时间直接入库。
     */
    fun parseStrict(time: String): Int? {
        val m = STRICT_TIME.matchEntire(time.trim()) ?: return null
        return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
    }

    /** 解析失败、格式非法或时间倒置时返回 false，用于输入校验 */
    fun isValidRange(start: String, end: String): Boolean {
        val s = parseStrict(start) ?: return false
        val e = parseStrict(end) ?: return false
        return e > s
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
