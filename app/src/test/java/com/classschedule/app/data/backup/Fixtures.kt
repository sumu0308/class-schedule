package com.classschedule.app.data.backup

import com.classschedule.app.data.Course
import com.classschedule.app.data.Period
import com.classschedule.app.data.Term
import com.classschedule.app.data.WeekParity
import com.classschedule.app.util.TermCalendar
import com.classschedule.app.util.WeekRanges
import java.util.Calendar

/**
 * 编解码测试的共享夹具。
 *
 * 学期开学日取 2026-09-07 对齐到周一（断言 dayOfWeek == 1，防止有人改了
 * 常量忘了对齐，导致 ICS 的日期↔星期换算全错）。
 */
internal object Fixtures {

    fun epochDayOf(y: Int, m: Int, d: Int): Long {
        val cal = Calendar.getInstance().apply { clear(); set(y, m - 1, d) }
        val offset = cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET)
        return Math.floorDiv(cal.timeInMillis + offset, 86_400_000L)
    }

    /** 2026-09-07 当周的周一（就是开学日） */
    val termStart: Long = run {
        var d = epochDayOf(2026, 9, 7)
        d -= (TermCalendar.dayOfWeek(d) - 1)
        d
    }

    fun term(id: Long = 1, name: String = "2026 秋", totalWeeks: Int = 20): Term =
        Term(id = id, name = name, startEpochDay = termStart, totalWeeks = totalWeeks, isCurrent = id == 1L)

    /** 与 CourseRepository.defaultPeriods 一致的 8 小节默认作息 */
    fun periods(termId: Long = 1): List<Period> = listOf(
        Period(id = 1, termId = termId, index = 1, startTime = "08:00", endTime = "08:40"),
        Period(id = 2, termId = termId, index = 2, startTime = "09:00", endTime = "09:40"),
        Period(id = 3, termId = termId, index = 3, startTime = "10:00", endTime = "10:40"),
        Period(id = 4, termId = termId, index = 4, startTime = "11:00", endTime = "11:40"),
        Period(id = 5, termId = termId, index = 5, startTime = "14:30", endTime = "15:10"),
        Period(id = 6, termId = termId, index = 6, startTime = "15:30", endTime = "16:10"),
        Period(id = 7, termId = termId, index = 7, startTime = "16:30", endTime = "17:10"),
        Period(id = 8, termId = termId, index = 8, startTime = "17:30", endTime = "18:10"),
    )

    /**
     * 覆盖各类边界的一批课程：
     * 跨课间休息的两节连排、单双周、不连续周次段、含逗号/引号的教师名。
     */
    fun courses(termId: Long = 1): List<Course> = listOf(
        Course(
            id = 10, termId = termId, name = "高等数学", teacher = "张, 老师", room = "A101",
            dayOfWeek = 1, startPeriodIndex = 1, periodCount = 2, weekRanges = "1-16",
        ),
        Course(
            id = 11, termId = termId, name = "大学英语", teacher = "李老师", room = "B203",
            dayOfWeek = 3, startPeriodIndex = 5, periodCount = 2, weekRanges = "1-20",
            weekParity = WeekParity.ODD,
        ),
        Course(
            id = 12, termId = termId, name = "体育", teacher = null, room = "操场",
            dayOfWeek = 5, startPeriodIndex = 3, periodCount = 1,
            weekRanges = "2,4,6,8,10,12,14,16,18,20", weekParity = WeekParity.EVEN,
        ),
        Course(
            id = 13, termId = termId, name = "实验课", teacher = "赵老师", room = null,
            dayOfWeek = 2, startPeriodIndex = 7, periodCount = 2, weekRanges = "1-4,7-10",
            colorArgb = 0xFF123456, note = "带\"引号\"的备注", source = Course.SOURCE_FILE,
        ),
    )

    fun backupData(): BackupData = BackupData(
        terms = listOf(term()),
        periods = periods(),
        courses = courses(),
    )

    /** 课程在当前学期的实际显示周次（周次表达式 ∩ 单双周） */
    fun displayWeeks(c: Course): Set<Int> = WeekRanges.parse(c.weekRanges).filter { w ->
        when (c.weekParity) {
            WeekParity.EVERY -> true
            WeekParity.ODD -> w % 2 == 1
            WeekParity.EVEN -> w % 2 == 0
        }
    }.toSet()

    fun displayWeeks(p: ParsedCourse): Set<Int> = WeekRanges.parse(p.weekRanges).filter { w ->
        when (p.weekParity) {
            WeekParity.EVERY -> true
            WeekParity.ODD -> w % 2 == 1
            WeekParity.EVEN -> w % 2 == 0
        }
    }.toSet()

    // ── XLSX 造包：ZipOutputStream 现场生成，不提交二进制夹具 ──

    class SheetSpec(
        val name: String,
        val rows: List<List<String>>,
        val merges: List<String> = emptyList(),
    )

    /**
     * 造一个最小合法的 xlsx：所有非空单元格进共享字符串（真实 Excel 也是这样存的），
     * sheet 顺序通过 workbook.xml.rels 映射。merges 如 ["A2:D2"]。
     */
    fun buildXlsx(vararg sheets: SheetSpec): ByteArray {
        val shared = LinkedHashMap<String, Int>()
        sheets.forEach { s ->
            s.rows.forEach { row ->
                row.forEach { cell -> if (cell.isNotEmpty() && cell !in shared) shared[cell] = shared.size }
            }
        }
        val sharedXml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"0\" uniqueCount=\"${shared.size}\">")
            shared.keys.forEach { text ->
                append("<si><t xml:space=\"preserve\">${escapeXml(text)}</t></si>")
            }
            append("</sst>")
        }
        val workbookXml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ")
            append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>")
            sheets.forEachIndexed { i, s ->
                append("<sheet name=\"${escapeXml(s.name)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>")
            }
            append("</sheets></workbook>")
        }
        val relsXml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
            sheets.forEachIndexed { i, _ ->
                append("<Relationship Id=\"rId${i + 1}\" ")
                append("Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" ")
                append("Target=\"worksheets/sheet${i + 1}.xml\"/>")
            }
            append("</Relationships>")
        }
        val sheetXmls = sheets.mapIndexed { i, s -> worksheetXml(s, i + 1, shared) }
        return zipOf(
            "xl/sharedStrings.xml" to sharedXml,
            "xl/workbook.xml" to workbookXml,
            "xl/_rels/workbook.xml.rels" to relsXml,
            *sheetXmls.mapIndexed { i, xml -> "xl/worksheets/sheet${i + 1}.xml" to xml }.toTypedArray(),
        )
    }

    /** 手写 worksheet xml 的场景（inlineStr / t="str" / 数字单元格 / 多 run 共享串） */
    fun buildXlsxRaw(
        worksheetXml: String,
        sharedStringsXml: String? = null,
        sheetName: String = "课表",
    ): ByteArray {
        val workbookXml =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                "<sheets><sheet name=\"$sheetName\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"
        val relsXml =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" " +
                "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" " +
                "Target=\"worksheets/sheet1.xml\"/></Relationships>"
        val entries = mutableListOf("xl/workbook.xml" to workbookXml, "xl/_rels/workbook.xml.rels" to relsXml)
        if (sharedStringsXml != null) entries += "xl/sharedStrings.xml" to sharedStringsXml
        entries += "xl/worksheets/sheet1.xml" to worksheetXml
        return zipOf(*entries.toTypedArray())
    }

    private fun worksheetXml(sheet: SheetSpec, sheetNo: Int, shared: Map<String, Int>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
        sheet.rows.forEachIndexed { r, row ->
            append("<row r=\"${r + 1}\">")
            row.forEachIndexed { c, cell ->
                if (cell.isNotEmpty()) {
                    val idx = shared[cell] ?: 0
                    append("<c r=\"${columnName(c)}${r + 1}\" t=\"s\"><v>$idx</v></c>")
                }
            }
            append("</row>")
        }
        append("</sheetData>")
        if (sheet.merges.isNotEmpty()) {
            append("<mergeCells count=\"${sheet.merges.size}\">")
            sheet.merges.forEach { append("<mergeCell ref=\"$it\"/>") }
            append("</mergeCells>")
        }
        append("</worksheet>")
    }

    /** 0 → "A"、25 → "Z"、26 → "AA" */
    internal fun columnName(c: Int): String {
        var n = c + 1
        val sb = StringBuilder()
        while (n > 0) {
            n--
            sb.insert(0, ('A' + n % 26))
            n /= 26
        }
        return sb.toString()
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(baos).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return baos.toByteArray()
    }
}
