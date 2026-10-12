package com.classschedule.app.data.backup

import com.classschedule.app.data.Course
import com.classschedule.app.data.WeekParity
import com.classschedule.app.util.WeekRanges

/**
 * CSV 导入（F-31，格式约定见计划文档 10.2.2）。
 *
 * 一行一门课，逗号分隔，RFC 4180 引号转义。表头为本 App 历史导出用过的英文列名；
 * 导入时兼容中文表头（课程名/星期 等），第一行看起来像表头就跳过。
 * 每一行独立校验：坏行进 issues（带行号与原因），合法行照常返回。
 *
 * 导出方向 0.10.0 随导出功能移除；HEADER 保留——它同时是"期望列数"的解析依据。
 */
object CsvCodec {

    val HEADER = listOf(
        "name", "teacher", "room", "dayOfWeek", "startPeriodIndex",
        "periodCount", "weekRanges", "weekParity", "colorArgb", "note",
    )

    private val HEADER_HINTS = setOf(
        "name", "teacher", "room", "dayofweek", "startperiodindex", "periodcount",
        "weekranges", "weekparity", "colorargb", "note",
        "课程名", "教师", "教室", "星期", "起始节次", "节数", "周次", "单双周", "颜色", "备注",
    )

    fun parse(text: String): ParseReport = parseRecords(splitRecords(text))

    /**
     * 记录流解析（XLSX 的列表布局直接复用：把网格行转成记录喂进来）。
     * 每行独立校验：坏行进 issues（带行号与原因），合法行照常返回。
     */
    internal fun parseRecords(records: List<List<String>>): ParseReport {
        val courses = mutableListOf<ParsedCourse>()
        val issues = mutableListOf<ParseIssue>()
        var dataLineNo = 0

        records.forEachIndexed { index, fields ->
            val lineNo = index + 1
            if (fields.all { it.isBlank() }) return@forEachIndexed // 空行跳过
            if (index == 0 && looksLikeHeader(fields)) return@forEachIndexed // 表头跳过
            dataLineNo++
            val at = "第 $lineNo 行"
            if (fields.size < HEADER.size) {
                issues += ParseIssue(at, "列数不足：应有 ${HEADER.size} 列，实际 ${fields.size} 列")
                return@forEachIndexed
            }
            val name = fields[0].trim()
            if (name.isEmpty()) {
                issues += ParseIssue(at, "课程名为空")
                return@forEachIndexed
            }
            val day = fields[3].trim().toIntOrNull()
            if (day == null || day !in 1..7) {
                issues += ParseIssue(at, "星期「${fields[3].trim()}」不是 1–7 的数字")
                return@forEachIndexed
            }
            val start = fields[4].trim().toIntOrNull()
            if (start == null || start < 1) {
                issues += ParseIssue(at, "起始节次「${fields[4].trim()}」不是正整数")
                return@forEachIndexed
            }
            val count = fields[5].trim().toIntOrNull()
            if (count == null || count < 1) {
                issues += ParseIssue(at, "占用节数「${fields[5].trim()}」不是正整数")
                return@forEachIndexed
            }
            if (WeekRanges.isBlankOrInvalid(fields[6])) {
                issues += ParseIssue(at, "周次「${fields[6].trim()}」无法解析")
                return@forEachIndexed
            }
            val parity = when (fields[7].trim().uppercase()) {
                "", "EVERY" -> WeekParity.EVERY
                "ODD" -> WeekParity.ODD
                "EVEN" -> WeekParity.EVEN
                else -> {
                    issues += ParseIssue(at, "单双周「${fields[7].trim()}」应为 EVERY / ODD / EVEN")
                    return@forEachIndexed
                }
            }
            val color = fields[8].trim().let { if (it.isEmpty()) null else it.toLongOrNull() }
            if (fields[8].trim().isNotEmpty() && color == null) {
                issues += ParseIssue(at, "颜色「${fields[8].trim()}」不是十进制数字")
                return@forEachIndexed
            }
            courses += ParsedCourse(
                name = name,
                teacher = fields[1].trim().ifEmpty { null },
                room = fields[2].trim().ifEmpty { null },
                dayOfWeek = day,
                startPeriodIndex = start,
                periodCount = count,
                weekRanges = WeekRanges.clamp(fields[6].trim(), 60),
                weekParity = parity,
                colorArgb = color,
                note = fields[9].trim().ifEmpty { null },
            )
        }
        return ParseReport("CSV", courses, issues)
    }

    // ── RFC 4180 记录切分：处理引号内的逗号、换行与双写引号 ──

    private fun splitRecords(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var fields = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        fun endField() { fields.add(field.toString()); field.clear() }
        fun endRecord() { endField(); records.add(fields); fields = mutableListOf() }

        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i += 2; continue }
                    inQuotes = false; i++; continue
                }
                field.append(c); i++; continue
            }
            when (c) {
                '"' -> { inQuotes = true; i++ }
                ',' -> { endField(); i++ }
                '\r' -> { i++; if (i < text.length && text[i] == '\n') i++; endRecord() }
                '\n' -> { i++; endRecord() }
                else -> { field.append(c); i++ }
            }
        }
        if (field.isNotEmpty() || fields.isNotEmpty()) endRecord()
        return records
    }

    private fun looksLikeHeader(fields: List<String>): Boolean {
        val normalized = fields.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (normalized.isEmpty()) return false
        // 第一行的"星期"列不是 1–7 的数字，且至少命中一个表头提示词
        val dayLooksNumeric = fields.getOrNull(3)?.trim()?.toIntOrNull() in 1..7
        if (dayLooksNumeric) return false
        return normalized.any { it in HEADER_HINTS }
    }
}
