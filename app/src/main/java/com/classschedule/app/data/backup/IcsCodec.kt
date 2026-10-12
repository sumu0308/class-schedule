package com.classschedule.app.data.backup

import com.classschedule.app.data.Period
import com.classschedule.app.data.Term
import com.classschedule.app.data.WeekParity
import com.classschedule.app.util.GridClock
import com.classschedule.app.util.TermCalendar
import com.classschedule.app.util.WeekRanges
import java.util.Calendar
import java.util.TimeZone

/**
 * ICS（iCalendar）导入（F-31，映射规则见计划文档 10.2.3）。
 *
 * DTSTART 的**时间**匹配当前学期的节次，**日期**推出星期与起始周，
 * RRULE 的 COUNT/INTERVAL/UNTIL 还原周次集合；同名 + 同星期 + 同节次的
 * 多个 VEVENT 自动合并。时间匹配不上的事件进 issues，不猜。
 * 导入侧完整支持 RFC 5545 行折叠（unfold）。
 *
 * 保真度声明（10.2.3）：ICS 是展示型日历格式，**备注与颜色没有标准载体，导入时丢弃**；
 * 只保证"显示集合一致"（同一批课、同一时间、同一批周次）。
 * 导出方向 0.10.0 随导出功能移除（含 RRULE 生成、行折叠输出）。
 */
object IcsCodec {

    fun parse(text: String, term: Term, periods: List<Period>): ParseReport {
        val sortedPeriods = periods.sortedBy { it.index }
        val parsed = mutableListOf<ParsedCourse>()
        val issues = mutableListOf<ParseIssue>()
        var inEvent = false
        var props = mutableMapOf<String, String>()
        var eventNo = 0
        val lines = unfold(text)
        for (raw in lines) {
            val line = raw.trim()
            if (line.equals("BEGIN:VEVENT", true)) { inEvent = true; props = mutableMapOf(); eventNo++ }
            else if (line.equals("END:VEVENT", true)) {
                inEvent = false
                parseEvent(props, eventNo, term, sortedPeriods).let { (course, issue) ->
                    if (course != null) parsed += course else issue?.let { issues += it }
                }
            } else if (inEvent) {
                val sep = line.indexOf(':')
                if (sep > 0) {
                    val key = line.substring(0, sep).substringBefore(';').uppercase()
                    props[key] = line.substring(sep + 1)
                }
            }
        }
        if (parsed.isEmpty() && issues.isEmpty()) {
            issues += ParseIssue("文件", "没有找到任何 VEVENT 事件，可能不是 ICS 日历文件")
        }
        return ParseReport("ICS", mergeByKey(parsed), issues)
    }

    // ── 单个事件的解析 ──────────────────────────────────────

    /** @return Pair(课程或 null, 问题或 null)——两者必有其一非空 */
    private fun parseEvent(
        props: Map<String, String>,
        eventNo: Int,
        term: Term,
        periods: List<Period>,
    ): Pair<ParsedCourse?, ParseIssue?> {
        val at = "事件 $eventNo"
        val name = props["SUMMARY"]?.takeIf { it.isNotBlank() } ?: return null to ParseIssue(at, "缺少 SUMMARY（课程名）")
        val dtStart = props["DTSTART"] ?: return null to ParseIssue(at, "缺少 DTSTART（开始时间）")
        val (day, minutes) = parseDateTime(dtStart) ?: return null to ParseIssue(at, "DTSTART「$dtStart」格式无法识别")

        val startIdx = periods.indexOfFirst { GridClock.minutesOf(it.startTime) == minutes }
        if (startIdx < 0) {
            return null to ParseIssue(at, "上课时间 ${GridClock.formatMinutes(minutes)} 与当前学期的节次时间对不上")
        }
        var count = 1
        if (props["DTEND"] != null) {
            val end = parseDateTime(props["DTEND"]!!)?.second
            if (end != null && end > minutes) {
                // 按"墙上时间"的结束边界找最后一节：第 i 节的结束时间 >= DTEND 即收尾。
                // 不能用课时累加判断——课间休息 20 分钟不计入课时，累加会把
                // "第 1-2 节（08:00-09:40，实际课时仅 80 分钟）"错判成占 3 节以上。
                var i = startIdx
                while (i < periods.size) {
                    if (GridClock.minutesOf(periods[i].endTime) >= end) {
                        count = i - startIdx + 1
                        break
                    }
                    i++
                }
                if (i >= periods.size) count = (periods.size - startIdx).coerceAtLeast(1)
            }
        }

        val dayOfWeek = TermCalendar.dayOfWeek(day)
        val week = TermCalendar.weekOf(day, term.startEpochDay)
        if (week < 1) return null to ParseIssue(at, "日期早于学期开始周")
        if (week > term.totalWeeks) return null to ParseIssue(at, "第 $week 周超出学期总周数（${term.totalWeeks}）")

        val weeks = weeksFromRrule(props["RRULE"], week, term.startEpochDay, term.totalWeeks)
        return ParsedCourse(
            name = unescapeText(name),
            teacher = props["DESCRIPTION"]?.takeIf { it.isNotBlank() }?.let { unescapeText(it) },
            room = props["LOCATION"]?.takeIf { it.isNotBlank() }?.let { unescapeText(it) },
            dayOfWeek = dayOfWeek,
            startPeriodIndex = startIdx + 1,
            periodCount = count,
            weekRanges = WeekRanges.format(weeks),
        ) to null
    }

    /** RRULE → 周次集合。只支持 FREQ=WEEKLY；不支持的规则按单次处理。 */
    private fun weeksFromRrule(
        rrule: String?,
        firstWeek: Int,
        termStart: Long,
        totalWeeks: Int,
    ): List<Int> {
        if (rrule.isNullOrBlank()) return listOf(firstWeek.coerceIn(1, totalWeeks))
        val parts = rrule.split(';').mapNotNull { p ->
            val kv = p.split('=', limit = 2)
            if (kv.size == 2) kv[0].trim().uppercase() to kv[1].trim() else null
        }.toMap()
        if (parts["FREQ"]?.uppercase() != "WEEKLY") return listOf(firstWeek.coerceIn(1, totalWeeks))
        val step = parts["INTERVAL"]?.toIntOrNull() ?: 1
        val weeks = mutableListOf<Int>()
        when {
            parts["COUNT"] != null -> {
                val n = parts["COUNT"]!!.toIntOrNull() ?: 1
                for (k in 0 until n) weeks += firstWeek + step * k
            }
            parts["UNTIL"] != null -> {
                val untilDay = parseDateTime(parts["UNTIL"]!!)?.first
                val last = if (untilDay != null) TermCalendar.weekOf(untilDay, termStart) else firstWeek
                var w = firstWeek
                while (w <= last) { weeks += w; w += step }
            }
            else -> weeks += firstWeek
        }
        return weeks.filter { it in 1..totalWeeks }.ifEmpty { listOf(firstWeek.coerceIn(1, totalWeeks)) }
    }

    // ── 合并与周次规范化 ────────────────────────────────────

    /** 同名 + 同星期 + 同节次的多个事件合并；隔周序列归一为 单/双周 + 显式周次 */
    private fun mergeByKey(events: List<ParsedCourse>): List<ParsedCourse> {
        val groups = LinkedHashMap<Triple<String, Int, Int>, MutableList<Int>>()
        val first = LinkedHashMap<Triple<String, Int, Int>, ParsedCourse>()
        events.forEach { e ->
            val key = Triple(e.name, e.dayOfWeek, e.startPeriodIndex)
            groups.getOrPut(key) { mutableListOf() } += WeekRanges.parse(e.weekRanges)
            first.getOrPut(key) { e }
        }
        return groups.map { (key, weeks) ->
            val base = first.getValue(key)
            val sorted = weeks.distinct().sorted()
            val uniformStep2 = sorted.size >= 2 && sorted.zipWithNext().all { (a, b) -> b - a == 2 }
            if (uniformStep2) {
                base.copy(
                    weekRanges = "${sorted.first()}-${sorted.last()}",
                    weekParity = if (sorted.first() % 2 == 1) WeekParity.ODD else WeekParity.EVEN,
                )
            } else {
                base.copy(weekRanges = WeekRanges.format(sorted), weekParity = WeekParity.EVERY)
            }
        }
    }

    // ── 工具 ────────────────────────────────────────────────

    /** 解析 ICS 日期时间 → (epochDay, 当天分钟数)；失败返回 null */
    private fun parseDateTime(value: String): Pair<Long, Int>? {
        val v = value.trim()
        val utc = v.endsWith("Z", true)
        val digits = v.filter { it.isDigit() }
        if (digits.length != 8 && digits.length != 14) return null
        val y = digits.substring(0, 4).toIntOrNull() ?: return null
        val mo = digits.substring(4, 6).toIntOrNull() ?: return null
        val d = digits.substring(6, 8).toIntOrNull() ?: return null
        if (mo !in 1..12 || d !in 1..31) return null
        val h = if (digits.length == 14) digits.substring(8, 10).toIntOrNull() ?: return null else 0
        val mi = if (digits.length == 14) digits.substring(10, 12).toIntOrNull() ?: return null else 0

        val cal = if (utc) {
            Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(y, mo - 1, d, h, mi, 0)
            }.let { utcCal ->
                Calendar.getInstance().apply { timeInMillis = utcCal.timeInMillis }
            }
        } else {
            Calendar.getInstance().apply { clear(); set(y, mo - 1, d, h, mi, 0) }
        }
        val epochDay = Math.floorDiv(
            cal.timeInMillis + cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET),
            86_400_000L,
        )
        return epochDay to (cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE))
    }

    /** 反向行折叠并去掉首尾空白；保留原始行序 */
    private fun unfold(text: String): List<String> {
        val out = mutableListOf<String>()
        text.split("\r\n", "\n", "\r").forEach { raw ->
            if (raw.isNotEmpty() && (raw[0] == ' ' || raw[0] == '\t') && out.isNotEmpty()) {
                out[out.lastIndex] = out.last() + raw.substring(1)
            } else {
                out += raw
            }
        }
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun unescapeText(s: String): String =
        s.replace("\\n", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")
}
