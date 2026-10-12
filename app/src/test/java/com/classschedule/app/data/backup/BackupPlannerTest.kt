package com.classschedule.app.data.backup

import com.classschedule.app.data.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入策略规划与格式嗅探（计划文档 10.3 / 10.4）。
 */
class BackupPlannerTest {

    private fun course(name: String, day: Int, start: Int): Course =
        Course(termId = 1, name = name, dayOfWeek = day, startPeriodIndex = start, periodCount = 2, weekRanges = "1-20")

    private fun parsed(name: String, day: Int, start: Int): ParsedCourse =
        ParsedCourse(name = name, dayOfWeek = day, startPeriodIndex = start, periodCount = 2, weekRanges = "1-20")

    // ── ImportPlanner ──────────────────────────────────────

    @Test
    fun `合并模式_同名同天同节次才算重复`() {
        val existing = listOf(course("高等数学", 1, 1))
        val parsedList = listOf(
            parsed("高等数学", 1, 1), // 重复：同名同天同节次
            parsed("高等数学", 2, 1), // 不重复：不同天
            parsed("高等数学", 1, 3), // 不重复：不同节次
            parsed("大学英语", 1, 1), // 不重复：不同名
        )
        val plan = ImportPlanner.plan(existing, parsedList, ImportStrategy.MERGE)
        assertEquals(3, plan.toInsert.size)
        assertEquals(1, plan.skipped)
        assertTrue(plan.toInsert.none { it.name == "高等数学" && it.dayOfWeek == 1 && it.startPeriodIndex == 1 })
    }

    @Test
    fun `合并模式_没有重复时全部插入`() {
        val existing = listOf(course("高等数学", 1, 1))
        val parsedList = listOf(parsed("大学英语", 1, 3), parsed("体育", 2, 1))
        val plan = ImportPlanner.plan(existing, parsedList, ImportStrategy.MERGE)
        assertEquals(2, plan.toInsert.size)
        assertEquals(0, plan.skipped)
    }

    @Test
    fun `合并模式_空库时全部插入`() {
        val plan = ImportPlanner.plan(emptyList(), listOf(parsed("高数", 1, 1)), ImportStrategy.MERGE)
        assertEquals(1, plan.toInsert.size)
        assertEquals(0, plan.skipped)
    }

    @Test
    fun `追加与覆盖模式不跳过任何行`() {
        val existing = listOf(course("高等数学", 1, 1))
        val parsedList = listOf(parsed("高等数学", 1, 1), parsed("大学英语", 1, 3))
        listOf(ImportStrategy.APPEND, ImportStrategy.REPLACE_TERM).forEach { strategy ->
            val plan = ImportPlanner.plan(existing, parsedList, strategy)
            assertEquals("$strategy 应全量", 2, plan.toInsert.size)
            assertEquals("$strategy 不该有跳过", 0, plan.skipped)
        }
    }

    // ── FormatSniffer ──────────────────────────────────────

    @Test
    fun `嗅探_大括号开头是JSON`() {
        assertEquals(FormatSniffer.FileFormat.JSON, FormatSniffer.sniff("""{"format":"x"}"""))
        assertEquals(FormatSniffer.FileFormat.JSON, FormatSniffer.sniff("  \n {\"a\":1}"))
    }

    @Test
    fun `嗅探_含VCALENDAR是ICS`() {
        assertEquals(
            FormatSniffer.FileFormat.ICS,
            FormatSniffer.sniff("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR"),
        )
        assertEquals(FormatSniffer.FileFormat.ICS, FormatSniffer.sniff("begin:vcalendar\n"))
    }

    @Test
    fun `嗅探_其余按CSV兜底`() {
        assertEquals(FormatSniffer.FileFormat.CSV, FormatSniffer.sniff("name,teacher\n高数,张老师"))
        assertEquals(FormatSniffer.FileFormat.CSV, FormatSniffer.sniff("随便什么文本"))
    }

    @Test
    fun `嗅探_不信任扩展名_按内容判断`() {
        // 用户把备份文件改名成 .txt 也应当识别为 JSON
        assertEquals(FormatSniffer.FileFormat.JSON, FormatSniffer.sniff("""{"format":"class-schedule-backup"}"""))
    }
}
