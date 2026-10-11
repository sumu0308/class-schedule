package com.classschedule.app

import com.classschedule.app.data.Course
import com.classschedule.app.data.Period
import com.classschedule.app.data.WeekParity
import com.classschedule.app.ui.GridGeometry
import com.classschedule.app.ui.GridMetrics
import com.classschedule.app.util.GridClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网格排版与时间换算的单元测试。
 *
 * 排版口径（v0.6.0 起，参照学校课表的样式）：
 *   **行高固定、一格就是一个大节**（两小节连上），上课时间写在格子里。
 *   不再按真实分钟数成比例画，因为那样会：① 100 分钟的大节比 40 分钟的小节高出一倍多，
 *   一眼看不出"一上午几门课"；② 夹了午休之后整张表被拉长，手机上一屏只能看到半天。
 */
class GridGeometryTest {

    /** 用户给的学校课表口径：一"节"就是连着上完的一大节 */
    private val periods = listOf(
        Period(id = 1, termId = 1, index = 1, startTime = "08:00", endTime = "09:40"),
        Period(id = 2, termId = 1, index = 2, startTime = "10:00", endTime = "10:40"),
        Period(id = 3, termId = 1, index = 3, startTime = "14:30", endTime = "16:10"),
        Period(id = 4, termId = 1, index = 4, startTime = "16:30", endTime = "18:10"),
    )

    /** 真实作息：8 个小节，每两小节连上成一大节，中间夹一个 170 分钟的午休 */
    private val realTimetable = listOf(
        Period(id = 1, termId = 1, index = 1, startTime = "08:00", endTime = "08:40"),
        Period(id = 2, termId = 1, index = 2, startTime = "09:00", endTime = "09:40"),
        Period(id = 3, termId = 1, index = 3, startTime = "10:00", endTime = "10:40"),
        Period(id = 4, termId = 1, index = 4, startTime = "11:00", endTime = "11:40"),
        Period(id = 5, termId = 1, index = 5, startTime = "14:30", endTime = "15:10"),
        Period(id = 6, termId = 1, index = 6, startTime = "15:30", endTime = "16:10"),
        Period(id = 7, termId = 1, index = 7, startTime = "16:30", endTime = "17:10"),
        Period(id = 8, termId = 1, index = 8, startTime = "17:30", endTime = "18:10"),
    )

    // ── 时间换算 ────────────────────────────────────────────────

    @Test
    fun `时间字符串转分钟`() {
        assertEquals(480, GridClock.minutesOf("08:00"))
        assertEquals(580, GridClock.minutesOf("09:40"))
        assertEquals(870, GridClock.minutesOf("14:30"))
        assertEquals(0, GridClock.minutesOf("乱写"))
    }

    @Test
    fun `分钟转时间字符串`() {
        assertEquals("08:00", GridClock.formatMinutes(480))
        assertEquals("16:10", GridClock.formatMinutes(970))
        assertEquals("00:00", GridClock.formatMinutes(1440))
    }

    @Test
    fun `时间区间校验`() {
        assertTrue(GridClock.isValidRange("08:00", "09:40"))
        assertFalse(GridClock.isValidRange("09:40", "08:00"))
        assertFalse(GridClock.isValidRange("08:00", "08:00"))
        assertFalse(GridClock.isValidRange("abc", "09:40"))
    }

    @Test
    fun `时长文本`() {
        assertEquals("1 小时 40 分", GridClock.durationText("08:00", "09:40"))
        assertEquals("40 分", GridClock.durationText("10:00", "10:40"))
        assertEquals("1 小时", GridClock.durationText("14:30", "15:30"))
    }

    // ── 分行：两小节算一格 ──────────────────────────────────────

    @Test
    fun `两个小节合成一行，行时间合并显示`() {
        // 真实作息是 8 个 40 分钟小节，连上两小节算一门课 → 4 行
        val g = GridGeometry.build(realTimetable)

        assertEquals("8 小节应合成 4 行", 4, g.rows.size)
        assertEquals(listOf(1, 2), g.rows[0].periodIndices.map { g.periods[it].index })
        assertEquals(
            listOf("8:00-9:40", "10:00-11:40", "14:30-16:10", "16:30-18:10"),
            g.rows.map { it.timeLabel },
        )
    }

    @Test
    fun `行时间覆盖课间休息，显示成 8点到9点40`() {
        val g = GridGeometry.build(realTimetable)
        val row = g.rows[0]

        // 第 1 行含 08:00-08:40 与 09:00-09:40，中间的 20 分钟课间也算在这一行里
        assertEquals(480, row.startMinutes)
        assertEquals(580, row.endMinutes)
        assertEquals("8:00-9:40", row.timeLabel)
    }

    // ── 行高：等高，不再按比例 ──────────────────────────────────

    @Test
    fun `所有行高相同`() {
        val g = GridGeometry.build(periods)

        assertEquals("4 个节次应合成 2 行", 2, g.rows.size)
        assertEquals(g.heights[0], g.heights[1], 0.001f)
    }

    @Test
    fun `偏移量单调递增且等于行高整数倍`() {
        val g = GridGeometry.build(realTimetable)
        for (i in 1 until g.offsets.size) {
            assertEquals("第 $i 行应正好落在上一行底部", g.offsets[i] - g.offsets[i - 1], g.rowHeight, 0.01f)
        }
    }

    @Test
    fun `内容高度等于行数乘行高`() {
        val g = GridGeometry.build(realTimetable)
        assertEquals(g.rows.size * g.rowHeight, g.contentHeight, 0.5f)
    }

    @Test
    fun `空节次表不崩溃`() {
        val g = GridGeometry.build(emptyList())
        assertEquals(0f, g.contentHeight, 0.01f)
        assertEquals(null, g.periodAt(100f))
        assertEquals(0, g.rows.size)
    }

    @Test
    fun `点击定位到正确的行`() {
        val g = GridGeometry.build(realTimetable)

        // 第 1 行任意高度都定位到该行的第一个小节
        assertEquals(1, g.periodAt(g.offsets[0] + 1f))
        assertEquals(1, g.periodAt(g.offsets[0] + g.rowHeight - 1f))
        // 第 3 行中间
        assertEquals(5, g.periodAt(g.offsets[2] + g.rowHeight / 2f))
        // 超出范围按最近一行吸附，不返回 null
        assertEquals(1, g.periodAt(-50f))
        assertEquals(7, g.periodAt(99999f))
    }

    @Test
    fun `行下标与节次下标互相换算`() {
        val g = GridGeometry.build(realTimetable)

        // 8 小节 → 4 行：1,2→0 / 3,4→1 / 5,6→2 / 7,8→3
        assertEquals(0, g.rowOfPeriodIndex(1))
        assertEquals(0, g.rowOfPeriodIndex(2))
        assertEquals(1, g.rowOfPeriodIndex(3))
        assertEquals(3, g.rowOfPeriodIndex(8))
        assertEquals(1, g.periodIndexOfRow(0))
        assertEquals(5, g.periodIndexOfRow(2))
        assertEquals(null, g.periodIndexOfRow(9))
        // 找不到的节次兜到第 0 行，不崩
        assertEquals(0, g.rowOfPeriodIndex(99))
    }

    @Test
    fun `时间倒置的脏数据不会让行高变成负数`() {
        val bad = listOf(
            Period(id = 1, termId = 1, index = 1, startTime = "10:00", endTime = "09:00"),
        )
        val g = GridGeometry.build(bad)
        assertTrue("行高必须为正", g.rowHeight > 0f)
        assertTrue("行跨度必须为正", g.rows[0].endMinutes > g.rows[0].startMinutes)
    }

    // ── 行高自适应 ──────────────────────────────────────────────

    @Test
    fun `一天放得下就不需要滚动`() {
        // 课表是拿来"一眼看全一天"的：可用高度够，整天就必须装得下，剩下的地方留白
        val available = 610f
        val g = GridGeometry.build(realTimetable, availableHeightDp = available)

        assertTrue("整天高度 ${g.contentHeight} 不该超过可用高度 $available", g.contentHeight <= available)
        assertEquals("行高应停在理想高度，不把格子撑满屏幕", GridMetrics.TARGET_ROW_DP, g.rowHeight, 0.001f)
    }

    @Test
    fun `屏幕越高行越高但不超过理想高度`() {
        val small = GridGeometry.build(realTimetable, availableHeightDp = 400f)
        val large = GridGeometry.build(realTimetable, availableHeightDp = 3000f)

        assertTrue("可用高度更大时行高不该变小", large.rowHeight >= small.rowHeight)
        assertTrue(
            "必须被上限约束住，否则一天会拉成好几屏",
            large.rowHeight <= GridMetrics.MAX_ROW_DP + 0.001f,
        )
    }

    @Test
    fun `屏幕太矮时行高落在下限，靠滚动看全`() {
        val g = GridGeometry.build(realTimetable, availableHeightDp = 100f)
        assertEquals(GridMetrics.MIN_ROW_DP, g.rowHeight, 0.001f)
    }

    @Test
    fun `没有屏幕尺寸时用兜底行高`() {
        val g = GridGeometry.build(realTimetable)
        assertEquals(GridMetrics.FALLBACK_ROW_DP, g.rowHeight, 0.001f)
        assertTrue("兜底行高必须不低于下限", g.rowHeight >= GridMetrics.MIN_ROW_DP)
    }

    @Test
    fun `午休这种长间隔不会把整张表压扁`() {
        // 行的起止时间只是拿来做行内刻度换算，不再参与行高计算 ——
        // 所以把午休缩短，行高必须一模一样（早期版本就是被午休污染才把表压扁的）
        val withShortLunch = realTimetable.mapIndexed { _, p ->
            if (p.index >= 5) p.copy(startTime = shiftMinutes(p.startTime, -160), endTime = shiftMinutes(p.endTime, -160))
            else p
        }
        val a = GridGeometry.build(realTimetable, availableHeightDp = 610f)
        val b = GridGeometry.build(withShortLunch, availableHeightDp = 610f)

        assertEquals("休息长短不应影响行高", a.rowHeight, b.rowHeight, 0.001f)
        assertEquals("也不该影响整天高度", a.contentHeight, b.contentHeight, 0.001f)
    }

    @Test
    fun `半小时刻度按行内时间原样换算`() {
        // 行是等高的，行内的 30 分钟刻度靠 pxPerMinute 换算：
        // pxPerMinute 取"行高 ÷ 典型行时长"，本作息典型行是 100 分钟
        val g = GridGeometry.build(realTimetable, availableHeightDp = 610f)

        assertEquals(100f, (g.rows[0].endMinutes - g.rows[0].startMinutes).toFloat(), 0.001f)
        assertEquals("典型行 100 分钟", 100f, g.rowHeight / g.pxPerMinute, 0.01f)
        // 08:30 的刻度落在第 1 行顶部往下 30 分钟处
        val y0830 = g.offsets[0] + (30 - 0) * g.pxPerMinute
        assertTrue("刻度应落在行内", y0830 > g.offsets[0] && y0830 < g.offsets[0] + g.rowHeight)
    }

    @Test
    fun `实测当前算法在本作息下的取值（用于核对文档断言）`() {
        // 四个数被下方断言写死：改动算法时这里会失败，提醒同步文档
        val g = GridGeometry.build(realTimetable, availableHeightDp = 610f)
        assertEquals("4 行放得下，按理想行高", 110f, g.rowHeight, 0.001f)
        assertEquals("整天高度", 440f, g.contentHeight, 0.001f)
        assertEquals("110 dp 的行用 12 sp 课名字号", 12f, GridMetrics.nameTextSizeSp(g.rowHeight, 1f), 0.001f)
        assertEquals("110 dp 的行给课名三行", 3, GridMetrics.nameMaxLines(g.rowHeight, 1f))
    }

    private fun shiftMinutes(time: String, delta: Int): String =
        GridClock.formatMinutes(GridClock.minutesOf(time) + delta)

    // ── 单双周与显示过滤 ────────────────────────────────────────

    @Test
    fun `单双周过滤逻辑`() {
        val courses = listOf(
            Course(id = 1, termId = 1, name = "每周课", dayOfWeek = 1, startPeriodIndex = 1, weekRanges = "1-16"),
            Course(
                id = 2, termId = 1, name = "单周课", dayOfWeek = 1, startPeriodIndex = 2,
                weekRanges = "1-16", weekParity = WeekParity.ODD,
            ),
        )
        // 第 1 周（单周）：两门都上
        val odd = com.classschedule.app.ui.computeLayout(courses, periods, 1, 5, 64f)
        assertEquals(2, odd.blocks.size)
        // 第 2 周（双周）：只剩每周课
        val even = com.classschedule.app.ui.computeLayout(courses, periods, 2, 5, 64f)
        assertEquals(1, even.blocks.size)
        assertEquals("每周课", even.blocks.first().course.name)
    }

    @Test
    fun `超出周次范围的课不显示`() {
        val courses = listOf(
            Course(id = 1, termId = 1, name = "后半学期", dayOfWeek = 2, startPeriodIndex = 1, weekRanges = "9-16"),
        )
        assertEquals(0, com.classschedule.app.ui.computeLayout(courses, periods, 5, 5, 64f).blocks.size)
        assertEquals(1, com.classschedule.app.ui.computeLayout(courses, periods, 10, 5, 64f).blocks.size)
    }

    @Test
    fun `课程块撑满所在行`() {
        val courses = listOf(
            Course(id = 1, termId = 1, name = "普通课", dayOfWeek = 3, startPeriodIndex = 1, weekRanges = "1-16"),
        )
        val layout = com.classschedule.app.ui.computeLayout(courses, periods, 1, 5, 64f)
        val g = layout.geometry
        val block = layout.blocks.first()

        // 一格 = 一整行：就算课只占 1 个小节，块也应该画满这一行，不留半格空白
        assertEquals(g.offsets[0], block.top, 0.5f)
        assertEquals(g.offsets[0] + g.rowHeight, block.bottom, 0.5f)
    }

    @Test
    fun `跨行的课程块覆盖多行`() {
        val courses = listOf(
            Course(
                id = 1, termId = 1, name = "连上三小节", dayOfWeek = 3,
                startPeriodIndex = 1, periodCount = 3, weekRanges = "1-16",
            ),
        )
        val layout = com.classschedule.app.ui.computeLayout(courses, periods, 1, 5, 64f)
        val g = layout.geometry
        val block = layout.blocks.first()

        // 小节 1,2,3 横跨第 1、2 行 → 块从第 1 行顶画到第 2 行底
        assertEquals(g.offsets[0], block.top, 0.5f)
        assertEquals(g.offsets[1] + g.heights[1], block.bottom, 0.5f)
    }
}
