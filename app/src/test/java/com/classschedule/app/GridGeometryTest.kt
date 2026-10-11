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
 * 排版口径（v0.7.0 起，回到第一版的做法 + 用户要求的两处调整）：
 *   **行高按真实时长成比例**（100 分钟的大节自然比 40 分钟的小节高），
 *   左侧栏不再写"节次几点到几点"，而是画**整点刻度**（8:00 / 9:00 / 10:00 …），
 *   上课时间写在课程块里。
 *
 * 为什么不用"间隔中位数"反推比例系数：8 小节作息里 7 个间隔是 20 分钟、
 * 只有午休是 170 分钟，排序后中位数落在 170，算出的系数被夹到下限，
 * 40 分钟的行只剩 30 dp，课名和教室挤得看不清。系数只跟节次时长有关。
 */
class GridGeometryTest {

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

    /**
     * 小节表（就是 App 里的默认作息）：8 个 40 分钟小节，两小节连上算一大节 → 4 行。
     *
     * 注意别把这里写成"一条就是一大节"（例如 08:00-09:40）：行是按
     * [GridMetrics.PERIODS_PER_BLOCK] 条挨着分组的，那样相邻两条会被合成一行、
     * 行跨度变成 220 分钟，跟 App 里看到的课表不是一回事。
     */
    private val periods = realTimetable

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

    // ── 行高：按真实时长成比例 ──────────────────────────────────

    @Test
    fun `行高与真实时长成正比`() {
        // 真实作息是 8 个 40 分钟小节 → 按两小节一行分成 4 行，每行跨度 100 分钟（含 20 分钟课间）
        val g = GridGeometry.build(periods)

        assertEquals("8 个小节两两成行", 4, g.rows.size)
        assertEquals(100f, (g.rows[0].endMinutes - g.rows[0].startMinutes).toFloat(), 0.001f)
        assertEquals(g.heights[0], g.heights[1], 0.001f)

        // 100 分钟的行必须是 40 分钟小节的 2.5 倍高 —— 这就是"按真实时长成比例"
        assertEquals("40 分钟小节的 2.5 倍才是 100 分钟的大节", 2.5f, g.heights[0] / (40f * g.pxPerMinute), 0.001f)

        // 第 2 行只有 60 分钟时，高度必须比 100 分钟的行矮
        // 四小节 → 两行：[08:00-09:40 + 10:00-10:40] 跨度 160 分钟、[11:00-11:40 + 12:00-12:40] 跨度 100 分钟
        val uneven = GridGeometry.build(
            listOf(
                Period(id = 1, termId = 1, index = 1, startTime = "08:00", endTime = "09:40"),
                Period(id = 2, termId = 1, index = 2, startTime = "10:00", endTime = "10:40"),
                Period(id = 3, termId = 1, index = 3, startTime = "11:00", endTime = "11:40"),
                Period(id = 4, termId = 1, index = 4, startTime = "12:00", endTime = "12:40"),
            ),
        )
        assertEquals(2, uneven.rows.size)
        assertEquals("行高之比 = 跨度之比", 160f / 100f, uneven.heights[0] / uneven.heights[1], 0.001f)
    }

    @Test
    fun `偏移量等于前几行高度之和`() {
        val g = GridGeometry.build(realTimetable)
        // 相邻两行之间只会隔一个课间（20 分钟），行顶就是"上一行底部 + 课间"
        for (i in 1 until g.offsets.size) {
            if (i == 2) continue // 第 2→3 行之间隔着 170 分钟午休，单独在下一个用例里断言
            assertEquals("第 $i 行顶 = 上一行底 + 课间", g.offsets[i - 1] + g.heights[i - 1] + 20f * g.pxPerMinute, g.offsets[i], 0.01f)
        }
        assertEquals("第 1 行贴顶", 0f, g.offsets[0], 0.001f)
        assertEquals("午休那一行要顶到午休结束", g.offsets[1] + g.heights[1] + 170f * g.pxPerMinute, g.offsets[2], 0.01f)
    }

    @Test
    fun `节次之间空档如实体现出来`() {
        // 第 1 行 9:40 结束、第 2 行 10:00 开始 —— 中间 20 分钟的空档要占位置，
        // 否则左侧整点刻度就会和内容错位（这正是第一版比例排版的用意）
        val g = GridGeometry.build(realTimetable)

        assertEquals("第 1 行底与第 2 行顶之间应有 20 分钟空档", 20f * g.pxPerMinute, g.offsets[1] - g.offsets[0] - g.heights[0], 0.01f)
        // 午休：11:40 结束、14:30 开始 = 170 分钟
        val lunchGap = g.offsets[2] - g.offsets[1] - g.heights[1]
        assertEquals("午休空档 170 分钟", 170f * g.pxPerMinute, lunchGap, 0.01f)
    }

    @Test
    fun `内容高度等于首行顶到末行底`() {
        val g = GridGeometry.build(realTimetable)
        assertEquals(g.offsets.last() + g.heights.last(), g.contentHeight, 0.5f)
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

        // 行内任意位置都定位到该行的第一个小节
        assertEquals(1, g.periodAt(g.offsets[0] + 1f))
        assertEquals(1, g.periodAt(g.offsets[0] + g.heights[0] - 1f))
        // 第 3 行（下午第 1 大节）任意位置都是 5
        assertEquals(5, g.periodAt(g.offsets[2] + 1f))
        assertEquals(5, g.periodAt(g.offsets[2] + g.heights[2] - 1f))

        // 空档（课间、午休）里按"最近一行的中心"吸附，不会点不中。
        // 判定口径就是"离哪一行的中心更近"，所以取每一段真实空档、用其中点两侧来验。
        for (i in 0 until g.rows.size - 1) {
            val gapTop = g.offsets[i] + g.heights[i]
            val gapBottom = g.offsets[i + 1]
            val gap = gapBottom - gapTop
            if (gap <= 1f) continue
            val centerI = g.offsets[i] + g.heights[i] / 2f
            val centerJ = g.offsets[i + 1] + g.heights[i + 1] / 2f
            val flip = (centerI + centerJ) / 2f
            assertEquals(
                "第 $i 行与第 ${i + 1} 行之间靠上一侧应吸附到第 $i 行",
                g.periodIndexOfRow(i), g.periodAt(flip - 0.5f),
            )
            assertEquals(
                "第 $i 行与第 ${i + 1} 行之间靠下一侧应吸附到第 ${i + 1} 行",
                g.periodIndexOfRow(i + 1), g.periodAt(flip + 0.5f),
            )
        }

        // 超出范围同样吸附
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
        assertTrue("行高必须为正", g.heights[0] > 0f)
        assertTrue("行跨度必须为正", g.rows[0].endMinutes > g.rows[0].startMinutes)
    }

    // ── 比例系数：屏幕放得下就按兜底手感，放不下才压缩 ──────────

    @Test
    fun `没有屏幕尺寸时用兜底系数`() {
        val g = GridGeometry.build(realTimetable)
        assertEquals(GridMetrics.FALLBACK_PX_PER_MINUTE, g.pxPerMinute, 0.001f)
        // 40 分钟的小节正好 58 dp —— 第一版的行高，课名 + 教室两行字放得下
        assertEquals(58f, 40f * g.pxPerMinute, 0.01f)
        // 100 分钟的大节 = 145 dp
        assertEquals(145f, g.heights[0], 0.01f)
    }

    @Test
    fun `一天放得下就用兜底系数，整天高度不超屏幕`() {
        // 本作息整天跨度 610 分钟，兜底系数下要 884.5 dp；
        // 屏幕给到 1200 dp 时比例不再变大（第一版的手感就是上限）
        val g = GridGeometry.build(realTimetable, availableHeightDp = 1200f)
        assertEquals(GridMetrics.FALLBACK_PX_PER_MINUTE, g.pxPerMinute, 0.001f)
        assertTrue("整天高度 ${g.contentHeight} 不该超过可用高度 1200", g.contentHeight <= 1200f)
    }

    @Test
    fun `屏幕装不下时按可用高度压缩比例，但不低于可读下限`() {
        // 610 分钟要装进 610 dp → 系数 1.0，低于兜底的 1.45，所以按 1.0 排
        val squeezed = GridGeometry.build(realTimetable, availableHeightDp = 610f)
        assertEquals(1f, squeezed.pxPerMinute, 0.001f)
        assertTrue("整天高度 ${squeezed.contentHeight} 不该超过可用高度 610", squeezed.contentHeight <= 610f)

        // 屏幕太矮也不能把行压到看不清：系数停在下限，靠纵向滚动看全
        val tiny = GridGeometry.build(realTimetable, availableHeightDp = 100f)
        assertEquals(GridMetrics.MIN_PX_PER_MINUTE, tiny.pxPerMinute, 0.001f)
        assertTrue("下限之下必须靠滚动，不能硬压", tiny.contentHeight > 100f)
    }

    @Test
    fun `比例系数被上限约束，屏幕再高也不会把刻度拉长`() {
        // 兜底系数本身就是上限：屏幕再高也只用 1.45（第一版的手感），
        // 不做"屏幕越高行越高"，否则一天会被拉成好几屏
        val g = GridGeometry.build(realTimetable, availableHeightDp = 100_000f)
        assertEquals(GridMetrics.FALLBACK_PX_PER_MINUTE, g.pxPerMinute, 0.001f)
        assertTrue(g.pxPerMinute <= GridMetrics.MAX_PX_PER_MINUTE)
    }

    @Test
    fun `午休这种长间隔不会把整张表压扁`() {
        // 系数只跟节次时长有关，跟休息长短无关 ——
        // 早期版本用"间隔中位数"反推系数，被午休污染后才把 40 分钟的行压到 30 dp。
        // 午休缩短后整天变矮，可用高度给同样多时系数会变大（不再需要压那么狠），
        // 但**行与行之间的比例必须不变**。
        val withShortLunch = realTimetable.map { p ->
            if (p.index >= 5) {
                p.copy(startTime = shiftMinutes(p.startTime, -160), endTime = shiftMinutes(p.endTime, -160))
            } else {
                p
            }
        }
        val a = GridGeometry.build(realTimetable)
        val b = GridGeometry.build(withShortLunch)

        assertEquals("没有屏幕尺寸时两边都该用兜底系数", a.pxPerMinute, b.pxPerMinute, 0.001f)
        assertEquals("40 分钟小节的高度必须一模一样", a.heights[0], b.heights[0], 0.001f)
        assertEquals("100 分钟大节的高度也必须一模一样", a.heights[1], b.heights[1], 0.001f)
    }

    @Test
    fun `整点刻度从第一个整点开始每小时一条`() {
        val g = GridGeometry.build(realTimetable)

        // 本作息 8:00 开始、18:10 结束 → 8:00 到 18:00 共 11 条
        assertEquals(11, g.hourTicks.size)
        assertEquals("8:00", g.hourTicks.first().first)
        assertEquals("18:00", g.hourTicks.last().first)
        assertEquals("第一条整点贴顶", 0f, g.hourTicks.first().second, 0.01f)
        // 9:00 距顶部 60 分钟 × 系数，且必须落在第 1 行内部（8:00-9:40）
        val nine = g.hourTicks[1].second
        assertEquals(60f * g.pxPerMinute, nine, 0.01f)
        assertTrue("9:00 应落在第 1 行内", nine > g.offsets[0] && nine < g.offsets[0] + g.heights[0])
        // 14:00 落在午休空档里（11:40-14:30），刻度不会和任何行冲突
        val fourteen = g.hourTicks.first { it.first == "14:00" }.second
        assertTrue(
            "14:00 应落在午休空档内",
            fourteen > g.offsets[1] + g.heights[1] && fourteen < g.offsets[2],
        )
    }

    @Test
    fun `半小时细线按行内绝对时间换算`() {
        val g = GridGeometry.build(realTimetable)

        // 第 1 行 8:00-9:40：行内的 8:30 / 9:00 / 9:30 三条细线
        val top = g.offsets[0]
        val y0830 = top + (510 - g.rows[0].startMinutes) * g.pxPerMinute
        assertEquals("8:30 距行顶 30 分钟", 30f * g.pxPerMinute, y0830 - top, 0.01f)
        assertTrue("细线必须落在行内", y0830 > top && y0830 < top + g.heights[0])
    }

    @Test
    fun `实测当前算法在本作息下的取值（用于核对文档断言）`() {
        // 这几个数被下方断言写死：改动算法时这里会失败，提醒同步文档
        // 本作息整天跨度 = 08:00→18:10 = 610 分钟
        val g = GridGeometry.build(realTimetable)
        assertEquals("没有屏幕约束时用兜底系数", 1.45f, g.pxPerMinute, 0.001f)
        assertEquals("40 分钟的小节 = 58 dp", 58f, 40f * g.pxPerMinute, 0.01f)
        assertEquals("100 分钟的大节 = 145 dp", 145f, g.heights[0], 0.01f)
        assertEquals("整天高度 610 × 1.45 = 884.5 dp", 884.5f, g.contentHeight, 0.5f)
        assertEquals("整点刻度从 8:00 到 18:00 共 11 条", 11, g.hourTicks.size)
        assertEquals("首条整点刻度是 8:00", "8:00", g.hourTicks.first().first)

        // 屏幕只给 610 dp 时按 1.0 排，正好装下
        val squeezed = GridGeometry.build(realTimetable, availableHeightDp = 610f)
        assertEquals("610 分钟整天装进 610 dp", 1f, squeezed.pxPerMinute, 0.001f)
        assertEquals("整天高度", 610f, squeezed.contentHeight, 0.5f)
        assertEquals("40 dp 的行用 9 sp 课名字号", 9f, GridMetrics.nameTextSizeSp(40f * squeezed.pxPerMinute, 1f), 0.001f)
        assertEquals("40 dp 的行只给课名一行", 1, GridMetrics.nameMaxLines(40f * squeezed.pxPerMinute, 1f))
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
        assertEquals(g.offsets[0] + g.heights[0], block.bottom, 0.5f)
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

        // 跨行的课程块从首行顶画到末行底
        assertEquals(g.offsets[0], block.top, 0.5f)
        assertEquals(g.offsets[1] + g.heights[1], block.bottom, 0.5f)
    }
}
