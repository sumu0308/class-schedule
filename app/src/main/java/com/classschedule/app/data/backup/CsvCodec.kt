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
 * 列数不强制：按位置解析，缺的列取默认值，只有关键列（课程名/星期/起始节次/周次）
 * 非法才报错——手工编辑的极简表因此也能导入。
 *
 * 导出方向 0.10.0 随导出功能移除；HEADER 保留作为列顺序的权威说明（也是表头识别的提示词）。
 */
object CsvCodec {

    val HEADER = listOf(
        "name", "teacher", "room", "dayOfWeek", "startPeriodIndex",
        "periodCount", "weekRanges", "weekParity", "colorArgb", "note",
    )

    /**
     * 关键列下标：这几列缺了就没法构成一门课，逐行带原因报错。
     * 其余列（教师/教室/单双周/颜色/备注）可缺省——手工编辑的极简表往往只写这几列。
     */
    private const val COL_NAME = 0
    private const val COL_DAY = 3
    private const val COL_START = 4
    private const val COL_COUNT = 5
    private const val COL_WEEKS = 6

    private val HEADER_HINTS = setOf(
        "name", "teacher", "room", "dayofweek", "startperiodindex", "periodcount",
        "weekranges", "weekparity", "colorargb", "note",
        "课程名", "教师", "教室", "星期", "起始节次", "节数", "周次", "单双周", "颜色", "备注",
    )

    /**
     * @param totalWeeks 当前学期总周数，用于把周次裁剪到学期内（与手工录入同口径）
     * @param maxPeriod  当前节次的最大序号，用于拒绝超出节次表的课（否则会静默不可见）
     */
    fun parse(text: String, totalWeeks: Int, maxPeriod: Int): ParseReport =
        parseRecords(splitRecords(text), totalWeeks, maxPeriod)

    /**
     * 记录流解析（XLSX 的列表布局直接复用：把网格行转成记录喂进来）。
     *
     * 按**位置**解析：缺失的列取默认值（教师/教室/单双周/颜色/备注本就有缺省），
     * 只有关键列（课程名 / 星期 / 起始节次 / 周次）缺失或非法才报错——
     * 不再硬性要求 ≥10 列，7 列的手工表也能进（每行缺列只报一次，不整份报废）。
     */
    internal fun parseRecords(records: List<List<String>>, totalWeeks: Int, maxPeriod: Int): ParseReport {
        val courses = mutableListOf<ParsedCourse>()
        val issues = mutableListOf<ParseIssue>()

        records.forEachIndexed { index, fields ->
            val lineNo = index + 1
            if (fields.all { it.isBlank() }) return@forEachIndexed // 空行跳过
            if (index == 0 && looksLikeHeader(fields)) return@forEachIndexed // 表头跳过
            val at = "第 $lineNo 行"
            fun col(i: Int): String = fields.getOrNull(i)?.trim() ?: ""

            val name = col(COL_NAME)
            if (name.isEmpty()) {
                issues += ParseIssue(at, "课程名为空（第 1 列）")
                return@forEachIndexed
            }
            val day = col(COL_DAY).toIntOrNull()
            if (day == null || day !in 1..7) {
                issues += ParseIssue(at, "星期「${col(COL_DAY)}」不是 1–7 的数字（第 4 列）")
                return@forEachIndexed
            }
            val start = col(COL_START).toIntOrNull()
            if (start == null || start < 1) {
                issues += ParseIssue(at, "起始节次「${col(COL_START)}」不是正整数（第 5 列）")
                return@forEachIndexed
            }
            // 占用节数可缺省，默认 1 节
            val count = col(COL_COUNT).ifEmpty { "1" }.toIntOrNull()
            if (count == null || count < 1) {
                issues += ParseIssue(at, "占用节数「${col(COL_COUNT)}」不是正整数（第 6 列）")
                return@forEachIndexed
            }
            // 节次上界：超过节次表的课会静默不可见，改为逐行报错（口径同粘贴导入）
            if (start > maxPeriod || start + count - 1 > maxPeriod) {
                issues += ParseIssue(at, "节次超出当前学期的 1–$maxPeriod 节")
                return@forEachIndexed
            }
            val weekRaw = col(COL_WEEKS)
            if (WeekRanges.isBlankOrInvalid(weekRaw)) {
                issues += ParseIssue(at, "周次「$weekRaw」无法解析（第 7 列，如 1-16）")
                return@forEachIndexed
            }
            // 周次裁剪到学期总周数（与手工录入同口径）；裁完为空说明全超范围，报错不静默
            val weeks = WeekRanges.clamp(weekRaw, totalWeeks)
            if (weeks.isEmpty()) {
                issues += ParseIssue(at, "周次「$weekRaw」全部超出本学期（共 $totalWeeks 周）")
                return@forEachIndexed
            }
            val parity = when (col(7).uppercase()) {
                "", "EVERY" -> WeekParity.EVERY
                "ODD" -> WeekParity.ODD
                "EVEN" -> WeekParity.EVEN
                else -> {
                    issues += ParseIssue(at, "单双周「${col(7)}」应为 EVERY / ODD / EVEN")
                    return@forEachIndexed
                }
            }
            val colorRaw = col(8)
            val color = colorRaw.ifEmpty { null }?.toLongOrNull()
            if (colorRaw.isNotEmpty() && color == null) {
                issues += ParseIssue(at, "颜色「$colorRaw」不是十进制数字")
                return@forEachIndexed
            }
            courses += ParsedCourse(
                name = name,
                teacher = col(1).ifEmpty { null },
                room = col(2).ifEmpty { null },
                dayOfWeek = day,
                startPeriodIndex = start,
                periodCount = count,
                weekRanges = weeks,
                weekParity = parity,
                colorArgb = color,
                note = col(9).ifEmpty { null },
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
