package com.classschedule.app

import com.classschedule.app.data.Course
import com.classschedule.app.data.Period
import com.classschedule.app.data.WeekParity
import com.classschedule.app.ui.GridGeometry
import com.classschedule.app.ui.GridMetrics
import com.classschedule.app.util.GridClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网格排版与时间换算的单元测试。
 *
 * 这些用例对应第一版反馈里"表格上下不对称、左边时间不对应"的问题：
 * 行高必须按真实时长成比例，否则 100 分钟的大节和 40 分钟的小节会被画成一样高。
 */
class GridGeometryTest {

    private val periods = listOf(
        Period(id = 1, termId = 1, index = 1, startTime = "08:00", endTime = "09:40"),
        Period(id = 2, termId = 1, index = 2, startTime = "10:00", endTime = "10:40"),
        Period(id = 3, termId = 1, index = 3, startTime = "14:30", endTime = "16:10"),
        Period(id = 4, termId = 1, index = 4, startTime = "16:30", endTime = "18:10"),
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

    // ── 行高比例（第一版反馈的核心）────────────────────────────

    @Test
    fun `行高与真实时长成正比`() {
        val g = GridGeometry.build(periods)

        // 第 1 节 100 分钟，第 2 节 40 分钟 → 高度比应为 2.5
        val h1 = g.heights[0]
        val h2 = g.heights[1]
        assertEquals(2.5f, h1 / h2, 0.01f)

        // 第 3、4 节都是 100 分钟，应等高于第 1 节
        assertEquals(h1, g.heights[2], 0.01f)
        assertEquals(h1, g.heights[3], 0.01f)
    }

    @Test
    fun `节次之间存在空档，偏移量体现出来`() {
        val g = GridGeometry.build(periods)

        // 第 1 节 08:00-09:40，第 2 节 10:00 开始 → 中间 20 分钟空档
        val gapPx = g.offsets[1] - (g.offsets[0] + g.heights[0])
        val expectedGap = 20 * g.pxPerMinute
        assertEquals(expectedGap, gapPx, 0.5f)
        assertTrue("空档必须为正，否则会重叠", gapPx > 0f)
    }

    @Test
    fun `偏移量按时间顺序单调递增`() {
        val g = GridGeometry.build(periods)
        for (i in 1 until g.offsets.size) {
            assertTrue("第 $i 行的偏移必须大于前一行", g.offsets[i] > g.offsets[i - 1])
        }
    }

    @Test
    fun `内容高度等于最后一节底部`() {
        val g = GridGeometry.build(periods)
        val lastIdx = g.offsets.size - 1
        assertEquals(g.offsets[lastIdx] + g.heights[lastIdx], g.contentHeight, 0.5f)
    }

    @Test
    fun `空节次表不崩溃`() {
        val g = GridGeometry.build(emptyList())
        assertEquals(0f, g.contentHeight, 0.01f)
        assertEquals(null, g.periodAt(100f))
    }

    @Test
    fun `点击定位到正确的节次`() {
        val g = GridGeometry.build(periods)

        // 第 1 节行内任意位置
        assertEquals(1, g.periodAt(g.offsets[0] + 1f))
        assertEquals(1, g.periodAt(g.offsets[0] + g.heights[0] - 1f))
        // 第 3 节行内
        assertEquals(3, g.periodAt(g.offsets[2] + g.heights[2] / 2f))
        // 落在两节之间的空档：应吸附到更近的一节，而不是返回 null
        val gapMiddle = g.offsets[1] - (20 * g.pxPerMinute) / 2f
        assertNotNull(g.periodAt(gapMiddle))
    }

    @Test
    fun `时间刻度每半小时一条`() {
        val g = GridGeometry.build(periods)
        // 08:00 到 18:10 跨越约 610 分钟 → 约 21 条刻度
        assertTrue("刻度数量应在合理范围，实际 ${g.rulerTicks.size}", g.rulerTicks.size in 15..25)
        assertEquals("08:00", g.rulerTicks.first().first)
        assertEquals(0f, g.rulerTicks.first().second, 0.01f)
    }

    @Test
    fun `时间倒置的脏数据不会让高度变成负数`() {
        val bad = listOf(
            Period(id = 1, termId = 1, index = 1, startTime = "10:00", endTime = "09:00"),
        )
        val g = GridGeometry.build(bad)
        assertTrue("高度必须为正", g.heights[0] > 0f)
    }

    // ── 比例系数的防回归测试 ────────────────────────────────────
    //
    // 背景：早期版本用"节次间隔的中位数"反推每分钟像素数，结果被午休这种长间隔污染。
    // 本作息 8 个小节里 7 个间隔是 20 分钟、只有午休是 170 分钟，排序后中位数正好落在 170，
    // 算出的系数被夹到下限，40 分钟的行只剩 30 dp，课名和教室挤在一起看不清。
    // 下面的断言保证这类"被离群值压扁"的问题不会再出现。

    /** 真实作息：8 小节 40 分钟，中间夹一个 170 分钟的午休 */
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

    @Test
    fun `午休这种长间隔不会把整张表压扁`() {
        val g = GridGeometry.build(realTimetable)

        // 不得被夹到下限——这正是旧实现的问题
        assertTrue(
            "比例系数 ${g.pxPerMinute} 不该落到下限 ${GridMetrics.MIN_PX_PER_MINUTE}；" +
                "说明又被长间隔污染了",
            g.pxPerMinute > GridMetrics.MIN_PX_PER_MINUTE + 0.01f,
        )
    }

    @Test
    fun `40 分钟小节的典型行高落在可读区间`() {
        val g = GridGeometry.build(realTimetable)
        val rowDp = g.heights[1] / 1f // 像素即 dp（测试里 density = 1 的像素口径）

        // 一行至少要放得下课名，所以不能低于 60 dp；也不该高到一天要滚好几屏
        assertTrue("40 分钟行高 ${rowDp}dp 偏矮，会挤在一起", rowDp >= 60f)
        assertTrue("40 分钟行高 ${rowDp}dp 偏高", rowDp <= 110f)
    }

    @Test
    fun `比例系数与休息长短无关`() {
        // 同一批节次，把午休从 170 分钟改成 10 分钟，系数必须不变
        val withShortLunch = realTimetable.mapIndexed { i, p ->
            if (p.index >= 5) p.copy(startTime = shiftMinutes(p.startTime, -160), endTime = shiftMinutes(p.endTime, -160))
            else p
        }
        val a = GridGeometry.build(realTimetable)
        val b = GridGeometry.build(withShortLunch)
        assertEquals("休息长短不应影响比例系数", a.pxPerMinute, b.pxPerMinute, 0.001f)
    }

    @Test
    fun `屏幕越高行越高但不超过上限`() {
        val small = GridGeometry.build(realTimetable, availableHeightDp = 400f)
        val large = GridGeometry.build(realTimetable, availableHeightDp = 3000f)

        assertTrue("可用高度更大时行高不该变小", large.heights[0] >= small.heights[0])
        assertTrue(
            "必须被上限约束住，否则一天会拉成好几屏",
            large.pxPerMinute <= GridMetrics.MAX_PX_PER_MINUTE + 0.001f,
        )
    }

    @Test
    fun `实测当前算法在本作息下的取值（用于核对文档断言）`() {
        val g = GridGeometry.build(realTimetable, availableHeightDp = 700f)
        // 这三个数被 README 与计划文档引用，改动算法时这里会失败，提醒同步文档
        assertEquals("比例系数", 2.0f, g.pxPerMinute, 0.001f)
        assertEquals("40 分钟行高", 80.0f, g.heights[0], 0.5f)
        assertEquals("40 分钟行的课名字号", 12f, GridMetrics.nameTextSizeSp(g.heights[0], 1f), 0.001f)
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
    fun `跨节次课程块覆盖多行`() {
        val courses = listOf(
            Course(
                id = 1, termId = 1, name = "连堂课", dayOfWeek = 3,
                startPeriodIndex = 1, periodCount = 2, weekRanges = "1-16",
            ),
        )
        val layout = com.classschedule.app.ui.computeLayout(courses, periods, 1, 5, 64f)
        assertEquals(1, layout.blocks.size)

        val g = layout.geometry
        val block = layout.blocks.first()
        // 块高度应等于两节高度之和加上中间空档
        val expected = (g.offsets[1] + g.heights[1]) - g.offsets[0]
        assertEquals(expected, block.bottom - block.top, 0.5f)
    }
}
