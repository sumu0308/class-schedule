package com.classschedule.app.util

import java.util.Calendar

/**
 * 学期日期与周次的换算。
 *
 * 刻意不使用 java.time：minSdk 24 上 java.time 需要 API 26 或额外的脱糖配置，
 * 而这边只需要"天数差"和"星期几"两个量，用纯算术更省事也更快。
 *
 * 约定：epochDay 以 1970-01-01 为 0（与 java.time.LocalDate.toEpochDay 一致）。
 * 1970-01-01 是星期四。
 */
object TermCalendar {

    /** 取当前时区的 epochDay */
    fun todayEpochDay(): Long {
        val now = Calendar.getInstance()
        val zoneOffsetMillis = now.get(Calendar.ZONE_OFFSET) + now.get(Calendar.DST_OFFSET)
        val utcMillis = now.timeInMillis + zoneOffsetMillis
        return Math.floorDiv(utcMillis, 86_400_000L)
    }

    /** 取某个 epochDay 是星期几，1 = 周一 … 7 = 周日 */
    fun dayOfWeek(epochDay: Long): Int {
        // 1970-01-01 是周四(4)：+3 把基准挪到周一
        val mondayBased = Math.floorMod(epochDay + 3, 7L)
        return (mondayBased + 1).toInt()
    }

    /**
     * 计算某天落在学期的第几周。
     *
     * @param epochDay 目标日期
     * @param termStartEpochDay 学期第 1 周周一
     * @return 第几周（从 1 开始）。早于开学返回 0 或负数，超出学期则大于总周数。
     */
    fun weekOf(epochDay: Long, termStartEpochDay: Long): Int {
        // 把目标日期向前对齐到它所在周的周一，再算跨了几周
        val mondayOffset = dayOfWeek(epochDay) - 1
        val targetMonday = epochDay - mondayOffset
        val weekIndex = Math.floorDiv(targetMonday - termStartEpochDay, 7L)
        return (weekIndex + 1).toInt()
    }

    /** 第 N 周周一的 epochDay */
    fun mondayOfWeek(week: Int, termStartEpochDay: Long): Long =
        termStartEpochDay + (week - 1).toLong() * 7L

    /** 某周内指定星期几（1=周一）的 epochDay */
    fun epochDayOf(week: Int, dayOfWeek: Int, termStartEpochDay: Long): Long =
        mondayOfWeek(week, termStartEpochDay) + (dayOfWeek - 1)

    /** 把 epochDay 格式化为 M月D日 */
    fun formatMonthDay(epochDay: Long): String {
        val cal = calendarOf(epochDay)
        return "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日"
    }

    /** 把 epochDay 格式化为 M/D */
    fun formatShort(epochDay: Long): String {
        val cal = calendarOf(epochDay)
        return "${cal.get(Calendar.MONTH) + 1}/${cal.get(Calendar.DAY_OF_MONTH)}"
    }

    /** 把 epochDay 格式化为 2026年3月2日 */
    fun formatFullDate(epochDay: Long): String {
        val cal = calendarOf(epochDay)
        return "${cal.get(Calendar.YEAR)}年${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日"
    }

    /** 是否周末（周六、周日） */
    fun isWeekend(dayOfWeek: Int): Boolean = dayOfWeek >= 6

    /** epochDay → 当地时区零点的 Calendar（同模块的编解码层也要用，故为 internal） */
    internal fun calendarOf(epochDay: Long): Calendar {
        val cal = Calendar.getInstance()
        val localMidnightUtc = epochDay * 86_400_000L
        cal.timeInMillis = localMidnightUtc -
            (cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET))
        return cal
    }

    /** 星期几的中文简称 */
    fun dayName(dayOfWeek: Int): String = when (dayOfWeek) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        7 -> "周日"
        else -> "?"
    }

    /** 星期几的单字 */
    fun dayShort(dayOfWeek: Int): String = when (dayOfWeek) {
        1 -> "一"
        2 -> "二"
        3 -> "三"
        4 -> "四"
        5 -> "五"
        6 -> "六"
        7 -> "日"
        else -> "?"
    }
}
