package com.classschedule.app.util

/**
 * 周次表达式的解析与规范化。
 *
 * 规范（见计划文档 4.3）：
 *   - 升序、去重：`5-6,1-3,1` → `1-3,5-6`
 *   - 连续段用 `-`，不连续用 `,`
 *   - 单双周不进表达式，由单独的字段表达，避免歧义
 *
 * 这个类只做纯计算，不依赖 Android，便于单元测试。
 */
object WeekRanges {

    /** 允许的最大周数，防止异常输入把内存撑爆 */
    private const val MAX_WEEK = 60

    /**
     * 区间分隔符的公共集合：`-`、`~`、`～`（全角波浪）、`—`（em dash）、
     * `–`（en dash）、`－`（全角减号）。周次、节次等各处解析都取自这里，
     * 避免"某一处认 en dash、另一处不认"的口径分裂。
     */
    val RANGE_SEPARATORS: Set<Char> = setOf('-', '~', '～', '—', '–', '－')

    /** [RANGE_SEPARATORS] 的字符类写法，供 Regex 拼接（额外含中文"至 / 到"） */
    const val RANGE_SEPARATOR_CLASS: String = "\\-~～—–－至到"

    /**
     * 解析表达式为周次集合。无法识别的片段会被忽略。
     * @return 升序去重后的周次集合
     */
    fun parse(expr: String): Set<Int> {
        val result = sortedSetOf<Int>()
        for (rawPart in expr.split(',', '，', '、', ' ')) {
            val part = rawPart.trim()
            if (part.isEmpty()) continue

            // 支持 1-8、1~8、1～8、1—8、1–8、1－8 等写法（分隔符集合见 RANGE_SEPARATORS）
            val range = part.split(*RANGE_SEPARATORS.toCharArray())
            if (range.size == 2) {
                val a = range[0].trim().toIntOrNull()
                val b = range[1].trim().toIntOrNull()
                if (a != null && b != null && a in 1..MAX_WEEK && b in 1..MAX_WEEK) {
                    for (w in minOf(a, b)..maxOf(a, b)) result.add(w)
                }
                continue
            }

            val single = part.toIntOrNull()
            if (single != null && single in 1..MAX_WEEK) result.add(single)
        }
        return result
    }

    /** 把集合还原成规范表达式 */
    fun format(weeks: Collection<Int>): String {
        if (weeks.isEmpty()) return ""
        val sorted = weeks.filter { it in 1..MAX_WEEK }.distinct().sorted()
        if (sorted.isEmpty()) return ""

        val parts = mutableListOf<String>()
        var start = sorted.first()
        var prev = start
        for (i in 1 until sorted.size) {
            val cur = sorted[i]
            if (cur == prev + 1) {
                prev = cur
                continue
            }
            parts.add(if (start == prev) "$start" else "$start-$prev")
            start = cur
            prev = cur
        }
        parts.add(if (start == prev) "$start" else "$start-$prev")
        return parts.joinToString(",")
    }

    /** 是否为空或无法解析 */
    fun isBlankOrInvalid(expr: String): Boolean = parse(expr).isEmpty()

    /** 某周是否落在表达式范围内 */
    fun contains(expr: String, week: Int): Boolean = parse(expr).contains(week)

    /**
     * 按学期总周数裁剪超出范围的部分。
     * @return 裁剪后的表达式
     */
    fun clamp(expr: String, totalWeeks: Int): String =
        format(parse(expr).filter { it <= totalWeeks })

    /** 是否存在超出学期总周数的周次 */
    fun hasOutOfRange(expr: String, totalWeeks: Int): Boolean =
        parse(expr).any { it > totalWeeks }
}
