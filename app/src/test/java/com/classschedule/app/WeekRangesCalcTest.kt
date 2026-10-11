package com.classschedule.app

import com.classschedule.app.util.TermCalendar
import com.classschedule.app.util.WeekRanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 周次解析与日期换算的单元测试。
 * 这两块是课表显示正确性的地基，算错了整张课表都是错的，所以先用测试钉死。
 */
class WeekRangesCalcTest {

    // ── 周次表达式解析 ──────────────────────────────────────────

    @Test
    fun `解析连续段`() {
        assertEquals(setOf(1, 2, 3, 4, 5, 6, 7, 8), WeekRanges.parse("1-8"))
    }

    @Test
    fun `解析多段与乱序并去重`() {
        assertEquals(setOf(1, 2, 3, 5, 6), WeekRanges.parse("5-6,1-3,1"))
    }

    @Test
    fun `支持中文逗号与波浪号`() {
        assertEquals(setOf(1, 2, 10, 11), WeekRanges.parse("1~2，10-11"))
    }

    @Test
    fun `倒序区间也能解析`() {
        assertEquals(setOf(3, 4, 5), WeekRanges.parse("5-3"))
    }

    @Test
    fun `非法输入被忽略而不崩溃`() {
        assertEquals(setOf(2), WeekRanges.parse("abc,2,0,-,99"))
    }

    @Test
    fun `空串解析为空集`() {
        assertTrue(WeekRanges.parse("").isEmpty())
        assertTrue(WeekRanges.parse("   ").isEmpty())
    }

    @Test
    fun `规范化输出升序且合并连续段`() {
        assertEquals("1-3,5-6", WeekRanges.format(listOf(6, 5, 1, 2, 3, 3)))
        assertEquals("1,3,5", WeekRanges.format(listOf(5, 1, 3)))
        assertEquals("", WeekRanges.format(emptyList()))
    }

    @Test
    fun `超出总周数的部分被裁剪`() {
        assertEquals("1-18", WeekRanges.clamp("1-20", 18))
        assertTrue(WeekRanges.hasOutOfRange("1-20", 18))
        assertFalse(WeekRanges.hasOutOfRange("1-18", 18))
    }

    @Test
    fun `往返一致`() {
        val expr = "1-8,10-16"
        assertEquals(expr, WeekRanges.format(WeekRanges.parse(expr)))
    }

    @Test
    fun `包含判断`() {
        assertTrue(WeekRanges.contains("1-8,10", 10))
        assertFalse(WeekRanges.contains("1-8,10", 9))
    }

    // ── 日期与周次换算 ──────────────────────────────────────────

    @Test
    fun `1970-01-01 是星期四`() {
        assertEquals(4, TermCalendar.dayOfWeek(0))
    }

    @Test
    fun `已知日期星期正确`() {
        // 2024-01-01 是星期一，其 epochDay 为 19723
        assertEquals(1, TermCalendar.dayOfWeek(19723))
        // 2024-01-06 是星期六
        assertEquals(6, TermCalendar.dayOfWeek(19728))
        // 2024-01-07 是星期日
        assertEquals(7, TermCalendar.dayOfWeek(19729))
    }

    @Test
    fun `开学当天是第 1 周`() {
        val start = 19723L // 2024-01-01 周一
        assertEquals(1, TermCalendar.weekOf(start, start))
        assertEquals(1, TermCalendar.weekOf(start + 6, start)) // 同周周日仍是第 1 周
    }

    @Test
    fun `跨周正确进位`() {
        val start = 19723L
        assertEquals(2, TermCalendar.weekOf(start + 7, start))
        assertEquals(3, TermCalendar.weekOf(start + 14, start))
        assertEquals(10, TermCalendar.weekOf(start + 63, start))
    }

    @Test
    fun `开学前返回第 0 周或负数`() {
        val start = 19723L
        assertEquals(0, TermCalendar.weekOf(start - 1, start))
        assertTrue(TermCalendar.weekOf(start - 7, start) <= 0)
    }

    @Test
    fun `周内任意一天都算同一周`() {
        val start = 19723L // 周一
        for (offset in 0..6) {
            assertEquals("offset=$offset 应属第 2 周", 2, TermCalendar.weekOf(start + 7 + offset, start))
        }
    }

    @Test
    fun `第 N 周周一可反算`() {
        val start = 19723L
        assertEquals(start, TermCalendar.mondayOfWeek(1, start))
        assertEquals(start + 7, TermCalendar.mondayOfWeek(2, start))
        assertEquals(start + 21, TermCalendar.mondayOfWeek(4, start))
    }

    @Test
    fun `指定周与星期的日期可定位`() {
        val start = 19723L
        // 第 1 周周三 = 开学日 + 2 天
        assertEquals(start + 2, TermCalendar.epochDayOf(1, 3, start))
        // 第 3 周周日 = 开学日 + 2*7 + 6 天
        assertEquals(start + 20, TermCalendar.epochDayOf(3, 7, start))
    }

    @Test
    fun `格式化日期`() {
        assertEquals("1月1日", TermCalendar.formatMonthDay(19723))
        assertEquals("1/6", TermCalendar.formatShort(19728))
    }

    @Test
    fun `单双周判定与周次交集`() {
        // 模拟"1-8 周的单周"：应命中 1,3,5,7
        val weeks = WeekRanges.parse("1-8")
        val oddWeeks = weeks.filter { it % 2 == 1 }
        assertEquals(listOf(1, 3, 5, 7), oddWeeks)

        val evenWeeks = weeks.filter { it % 2 == 0 }
        assertEquals(listOf(2, 4, 6, 8), evenWeeks)
    }
}
