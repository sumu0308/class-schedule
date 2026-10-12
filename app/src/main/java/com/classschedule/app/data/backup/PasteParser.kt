package com.classschedule.app.data.backup

import com.classschedule.app.data.WeekParity
import com.classschedule.app.util.WeekRanges

/**
 * 粘贴课表文本解析器（F-31 的"粘贴文本"入口，语法见计划文档 10.2.4）。
 *
 * 每行一门课，空格/制表符分隔，从星期 token 向前推：前一 token 是教室、
 * 再前一是教师、其余是课名——所以课名可以含空格，教师教室可用 `-` 占位：
 *
 * ```
 * 高等数学 张老师 A101 周一 1-2 1-16周
 * 大学英语 - B203 周三 3-4
 * # 这是注释
 * ```
 *
 * 无法识别的行进 issues 并给出原因，不猜、不写库。
 */
object PasteParser {

    private val WEEKDAYS: Map<String, Int> = buildMap {
        val names = listOf("一", "二", "三", "四", "五", "六", "日")
        names.forEachIndexed { i, n ->
            put(n, i + 1)
            put("周$n", i + 1)
            put("星期$n", i + 1)
            put("礼拜$n", i + 1)
        }
    }

    fun parse(text: String, totalWeeks: Int, maxPeriod: Int): ParseReport {
        val courses = mutableListOf<ParsedCourse>()
        val issues = mutableListOf<ParseIssue>()
        text.lines().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            val lineNo = index + 1
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val at = "第 $lineNo 行"
            val tokens = line.split(Regex("[ \\t]+")).filter { it.isNotEmpty() }

            val weekdayIdx = tokens.indexOfFirst { WEEKDAYS.containsKey(it) }
            if (weekdayIdx < 0) {
                issues += ParseIssue(at, "找不到星期（应含 周一/周二…周日）")
                return@forEachIndexed
            }
            if (weekdayIdx == 0) {
                issues += ParseIssue(at, "星期之前没有课程名")
                return@forEachIndexed
            }
            val dayOfWeek = WEEKDAYS.getValue(tokens[weekdayIdx])

            // 星期之前：最后一个 token 是教室，倒数第二个是教师，其余拼接为课名
            val room = tokens.getOrNull(weekdayIdx - 1)?.takeIf { it != "-" && weekdayIdx - 1 > 0 }
            val teacher = tokens.getOrNull(weekdayIdx - 2)?.takeIf { it != "-" && weekdayIdx - 2 > 0 }
            val name = tokens.subList(0, (weekdayIdx - 2).coerceAtLeast(1)).joinToString(" ")
            if (name.isBlank()) {
                issues += ParseIssue(at, "课名为空（教师教室可用 - 占位）")
                return@forEachIndexed
            }

            // 星期之后：节次 [周次] [单双周]
            val periodToken = tokens.getOrNull(weekdayIdx + 1)
            if (periodToken == null) {
                issues += ParseIssue(at, "缺少节次（如 3-4 或 3）")
                return@forEachIndexed
            }
            val range = parsePeriods(periodToken)
            if (range == null) {
                issues += ParseIssue(at, "节次「$periodToken」无法识别（如 3-4 或单节 3）")
                return@forEachIndexed
            }
            val (start, count) = range
            if (start < 1 || start + count - 1 > maxPeriod) {
                issues += ParseIssue(at, "节次超出当前学期的 1–$maxPeriod 节")
                return@forEachIndexed
            }

            var weeks = "1-$totalWeeks"
            var parity = WeekParity.EVERY
            tokens.getOrNull(weekdayIdx + 2)?.let { token ->
                // 宽容处理：用户可能把"单/双/每周"写在周次位上（想写"每周上课"却省略了周次）。
                // 这些词作为周次本来就无法解析，顺势当成单双周，缺省周次补全学期。
                when (token) {
                    "单", "单周" -> parity = WeekParity.ODD
                    "双", "双周" -> parity = WeekParity.EVEN
                    "每周" -> parity = WeekParity.EVERY
                    else -> {
                        val w = token.removeSuffix("周")
                        if (WeekRanges.isBlankOrInvalid(w)) {
                            issues += ParseIssue(at, "周次「$token」无法解析（如 1-16 或 1-8,10-16）")
                            return@forEachIndexed
                        }
                        weeks = w
                    }
                }
            }
            tokens.getOrNull(weekdayIdx + 3)?.let { token ->
                parity = when (token) {
                    "单", "单周" -> WeekParity.ODD
                    "双", "双周" -> WeekParity.EVEN
                    "每周" -> WeekParity.EVERY
                    else -> {
                        issues += ParseIssue(at, "单双周「$token」应为 单 / 双 / 每周")
                        return@forEachIndexed
                    }
                }
            }

            courses += ParsedCourse(
                name = name,
                teacher = teacher,
                room = room,
                dayOfWeek = dayOfWeek,
                startPeriodIndex = start,
                periodCount = count,
                weekRanges = weeks,
                weekParity = parity,
            )
        }
        return ParseReport("粘贴文本", courses, issues)
    }

    /** "3-4" / "3-4节" / "第3-4节" / "3" → (起始节次, 占用节数) */
    private fun parsePeriods(token: String): Pair<Int, Int>? {
        val t = token.removePrefix("第").removeSuffix("节").replace('~', '-').replace('—', '-').replace('－', '-')
        val parts = t.split('-')
        val start = parts[0].toIntOrNull() ?: return null
        if (parts.size == 1) return start to 1
        val end = parts[1].toIntOrNull() ?: return null
        if (end < start) return null
        return start to (end - start + 1)
    }
}
