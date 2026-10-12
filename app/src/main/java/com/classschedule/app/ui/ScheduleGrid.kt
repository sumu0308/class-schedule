package com.classschedule.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.classschedule.app.data.Course
import com.classschedule.app.data.Period
import com.classschedule.app.data.Term
import com.classschedule.app.data.WeekParity
import com.classschedule.app.util.GridClock
import com.classschedule.app.util.TermCalendar
import com.classschedule.app.util.WeekRanges

/** 某一格被哪门课占用。course 为 null 表示该格空着。 */
data class CellOccupancy(
    val course: Course?,
    /** 是否为该课程块的起始格（只有起始格绘制课程名） */
    val isStart: Boolean = false,
    val span: Int = 1,
)

/** 一次排版的全部结果 */
data class ScheduleLayout(
    val geometry: GridGeometry,
    val occupancy: Map<Pair<Int, Int>, CellOccupancy>,
    /** 每门课的块在像素坐标系里的位置，绘制与点击命中共用 */
    val blocks: List<CourseBlock>,
)

/** 一门课在网格里的矩形（相对内容区左上角，单位像素） */
data class CourseBlock(
    val course: Course,
    val day: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    /** 点击是否命中 */
    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom
}

/** 网格尺寸常量 */
object GridMetrics {
    /** 一天几列时用的列宽（5/6 天正好占满一屏，7 天略超一屏、可横向滑动） */
    fun dayWidthDp(dayCount: Int): Float = if (dayCount <= 5) 64f else 52f

    /** 一"大节"由几小节组成。一行就是一个大节，课程块内标注的时间也按大节合并。 */
    const val PERIODS_PER_BLOCK = 2

    /**
     * 左侧固定栏总宽（节次号 + 大节时间 + 整点刻度）。
     *
     * 自左向右分三段，加起来正好是这一栏的全部宽度：
     *   [0, PERIOD_NUM_DP)                              节次号  "1"      8.5 sp
     *   [.., width - HOUR_RULER_DP)                      大节时间 "14:30-16:10" 7 sp
     *   [width - HOUR_RULER_DP, width]                   整点刻度 "18:00"  9 sp
     *
     * 三段都不能再挤：整点刻度要完整的 "18:00"（5 字符），大节时间最长 11 字符，
     * 节次号虽然只有 1–2 位但要留出与时间的间隙。
     */
    const val GUTTER_WIDTH_DP = 64f
    const val PERIOD_NUM_DP = 16f
    const val HOUR_RULER_DP = 30f

    const val HEADER_HEIGHT_DP = 34f
    const val BLOCK_GAP_DP = 2.5f
    const val CORNER_DP = 7f
    const val TEXT_PADDING_DP = 5f

    /**
     * 每分钟占多少像素的兜底值。
     *
     * 1.45 = 40 分钟的小节正好占 58 dp —— 这是第一版的行高口径，
     * 也是"课名 + 教室两行字"能舒服放下的最小高度。
     * 没有屏幕尺寸信息（或算出来的值更小）时用它。
     */
    const val FALLBACK_PX_PER_MINUTE = 1.45f

    /**
     * 每分钟占多少像素的下限。
     *
     * 0.95 → 40 分钟的小节 = 38 dp，再矮课名就挤了（这是可读性底线）。
     * 必须小于兜底值，否则"整天铺满屏幕"会把行压到看不清。
     */
    const val MIN_PX_PER_MINUTE = 0.95f

    /**
     * 每分钟占多少像素的上限。
     *
     * 屏幕很高时不该让一分钟占太多像素，否则一天要滚好几屏。
     * 2.2 → 40 分钟的小节 = 88 dp，100 分钟的大节 = 220 dp，已经足够宽松。
     */
    const val MAX_PX_PER_MINUTE = 2.2f

    fun nameTextSizeSp(blockHeightPx: Float, density: Float): Float {
        val dp = blockHeightPx / density
        return when {
            dp >= 150f -> 14f
            dp >= 110f -> 13f
            dp >= 80f -> 12f
            dp >= 62f -> 11f
            dp >= 48f -> 10f
            else -> 9f
        }
    }

    /** 课程名最多显示几行（块矮时只给一行） */
    fun nameMaxLines(blockHeightPx: Float, density: Float): Int {
        val dp = blockHeightPx / density
        return if (dp >= 100f) 3 else if (dp >= 66f) 2 else 1
    }
}

/**
 * 课表的一行：一个"大节"。
 *
 * 学校课表不是按 40 分钟一小节排版的，而是**两小节连上**算一格
 * （8:00-9:40 是一门课，10:00-11:40 是下一门）。所以一行可能包含不止一个 period：
 * 例如第 1 行含 08:00-08:40 与 09:00-09:40 两小节，时间文字合并显示成 "8:00-9:40"。
 *
 * 一行的 [startMinutes]..[endMinutes] 是该行所有小节里最早的开始到最晚的结束，
 * 跨小节的那些时间（例如 08:40-09:00 的课间休息）都算在这一行里面。
 */
data class GridRow(
    /** 行内各小节在 [GridGeometry.periods] 里的下标，升序 */
    val periodIndices: List<Int>,
    val startMinutes: Int,
    val endMinutes: Int,
) {
    /** 行时间文字，如 "8:00-9:40" */
    val timeLabel: String
        get() = "${GridClock.formatMinutesCompact(startMinutes)}-${GridClock.formatMinutesCompact(endMinutes)}"
}

/**
 * 把节次时间换算成像素坐标。
 *
 * 排版口径（v0.5.0 起，参照学校课表的样式）：
 *   ——**行高固定**，每格就是一个大节，不再让 100 分钟的段比 40 分钟的小节高。
 *   行与行之间有明显的分隔线，具体的上课时间写在格子里（课表块顶部）。
 *   这样左右天然对齐，也不会因为作息里夹了午休就把整张表拉得歪歪扭扭。
 */
class GridGeometry(
    val periods: List<Period>,
    /** 每行包含哪些 period（按下标），行与行不重叠 */
    val rows: List<GridRow>,
    /** 每一行距内容区顶部的像素偏移，按真实时间成比例 */
    val offsets: FloatArray,
    /** 每一行的高度，同样按真实时间成比例 */
    val heights: FloatArray,
    /** 每分钟占多少像素；整点刻度、行内半小时细线都用它换算 */
    val pxPerMinute: Float,
    /** 整点刻度：("8:00", 距内容区顶部的像素偏移) */
    val hourTicks: List<Pair<String, Float>> = emptyList(),
) {
    val contentHeight: Float get() = offsets.lastOrNull()?.plus(heights.lastOrNull() ?: 0f) ?: 0f

    fun offsetOf(row: Int): Float = offsets.getOrElse(row) { 0f }

    fun heightOf(row: Int): Float = heights.getOrElse(row) { 0f }

    /** 行序号 → 该行第一个小节的 period.index（长按新增课程时用） */
    fun periodIndexOfRow(row: Int): Int? {
        val r = rows.getOrNull(row) ?: return null
        return periods.getOrNull(r.periodIndices.firstOrNull() ?: return null)?.index
    }

    /** 某个 period.index 落在哪一行 */
    fun rowOfPeriodIndex(periodIndex: Int): Int {
        val pIdx = periods.indexOfFirst { it.index == periodIndex }
        if (pIdx < 0) return 0
        val row = rows.indexOfFirst { pIdx in it.periodIndices }
        return if (row < 0) 0 else row
    }

    /**
     * 找出某个 y 坐标落在哪一行，返回该行第一个小节的 period.index。
     *
     * 行的边界就是节次边界，落在两行之内的空档（例如午休）时吸附到更近的一行，
     * 避免长按空白处新增课程时"点不中"。
     */
    fun periodAt(y: Float): Int? {
        if (rows.isEmpty()) return null
        for (i in rows.indices) {
            val top = offsets[i]
            val bottom = top + heights[i]
            if (y >= top && y <= bottom) return periodIndexOfRow(i)
        }
        var best = 0
        var bestDist = Float.MAX_VALUE
        for (i in rows.indices) {
            val center = offsets[i] + heights[i] / 2f
            val d = kotlin.math.abs(center - y)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return periodIndexOfRow(best)
    }

    companion object {
        /**
         * 计算排版几何。
         *
         * 排版口径（回到第一版的做法）：**行高按真实时长成比例**。
         *   100 分钟的大节自然比 40 分钟的小节高，节次之间的空档也如实显示，
         *   左侧的整点刻度因此与内容严格对齐 —— 这是课表最直观的读法。
         *
         * 比例系数（pxPerMinute）只看节次时长，不看节次之间的间隔：
         *   早期版本用"间隔中位数"反推系数，被午休这种长间隔污染过
         *   （8 小节作息里 7 个间隔 20 分钟、只有午休 170 分钟，中位数落在 170），
         *   算出的系数被夹到下限，40 分钟的行只剩 30 dp，课名和教室挤在一起看不清。
         *
         * 屏幕放得下就用兜底系数（40 分钟 = 58 dp，第一版的手感）；
         * 放不下才按可用高度压到 [GridMetrics.MIN_PX_PER_MINUTE] 为止，再放不下就滚动。
         *
         * @param availableHeightDp 课表内容区的可用高度（dp）。传 0 则只用兜底系数。
         */
        fun build(
            periods: List<Period>,
            availableHeightDp: Float = 0f,
            periodsPerBlock: Int = GridMetrics.PERIODS_PER_BLOCK,
        ): GridGeometry {
            if (periods.isEmpty()) {
                return GridGeometry(
                    emptyList(), emptyList(),
                    FloatArray(0), FloatArray(0),
                    GridMetrics.FALLBACK_PX_PER_MINUTE,
                )
            }
            val sorted = periods.sortedBy { it.index }
            val chunks = sorted.indices.chunked(periodsPerBlock.coerceAtLeast(1))

            val rows = chunks.map { chunk ->
                val starts = chunk.map { GridClock.minutesOf(sorted[it].startTime) }
                val ends = chunk.map { GridClock.minutesOf(sorted[it].endTime) }
                val start = starts.min()
                // 脏数据（结束早于开始）兜成正数，避免行高或刻度算出负值
                val span = (ends.max() - start).coerceAtLeast(1)
                GridRow(
                    periodIndices = chunk.toList(),
                    startMinutes = start,
                    endMinutes = start + span,
                )
            }

            val baseMinute = rows.minOf { it.startMinutes }
            val lastMinute = rows.maxOf { it.endMinutes }
            val spanMinutes = (lastMinute - baseMinute).coerceAtLeast(1).toFloat()

            // 兜底系数（第一版手感）优先；屏幕装不下才压缩，但不再低于可读下限
            var pxPerMinute = GridMetrics.FALLBACK_PX_PER_MINUTE
            if (availableHeightDp > 0f) {
                val fit = availableHeightDp / spanMinutes
                if (fit < pxPerMinute) pxPerMinute = fit
            }
            pxPerMinute = pxPerMinute.coerceIn(
                GridMetrics.MIN_PX_PER_MINUTE,
                GridMetrics.MAX_PX_PER_MINUTE,
            )

            val offsets = FloatArray(rows.size) { (rows[it].startMinutes - baseMinute) * pxPerMinute }
            val heights = FloatArray(rows.size) {
                (rows[it].endMinutes - rows[it].startMinutes) * pxPerMinute
            }

            // 整点刻度：从第一个节次所在的那个整点开始，每小时一条直到最晚结束
            val ticks = mutableListOf<Pair<String, Float>>()
            var m = (baseMinute / 60) * 60
            while (m <= lastMinute) {
                ticks.add(GridClock.formatMinutesCompact(m) to (m - baseMinute) * pxPerMinute)
                m += 60
            }

            return GridGeometry(
                periods = sorted,
                rows = rows,
                offsets = offsets,
                heights = heights,
                pxPerMinute = pxPerMinute,
                hourTicks = ticks,
            )
        }
    }
}

/**
 * 计算一学期课表在指定周的排布。
 *
 * 显示判定（与计划文档 4.3 一致）：
 *   第 N 周在 weekRanges 内 → 且单双周匹配 → 该课在本周出现
 */
fun computeLayout(
    courses: List<Course>,
    periods: List<Period>,
    week: Int,
    dayCount: Int,
    dayWidthPx: Float,
    availableHeightDp: Float = 0f,
): ScheduleLayout {
    val geometry = GridGeometry.build(periods, availableHeightDp)
    val validIndexes = periods.map { it.index }.toSet()

    val visible = courses
        .filter { it.dayOfWeek in 1..dayCount }
        .filter { it.startPeriodIndex in validIndexes }
        .filter { WeekRanges.contains(it.weekRanges, week) }
        .filter { course ->
            when (course.weekParity) {
                WeekParity.EVERY -> true
                WeekParity.ODD -> week % 2 == 1
                WeekParity.EVEN -> week % 2 == 0
            }
        }

    // 先放短的，再放长的，保证跨节次的大块覆盖同格的小块
    val occupancy = HashMap<Pair<Int, Int>, CellOccupancy>()
    val blocks = mutableListOf<CourseBlock>()
    val ordered = visible.sortedBy { it.periodCount }

    for (c in ordered) {
        val row = geometry.rowOfPeriodIndex(c.startPeriodIndex)
        val totalRows = geometry.rows.size
        if (totalRows <= 0) continue

        // 课占几行：按**时间**算，不能按"小节数 ÷ 每大节小节数"硬除。
        // 例如第 1 大节（08:00-09:40）与第 3 大节（14:30-16:10）之间隔着午休，
        // 硬除会算出"跨 2 行"，把下午那一行也涂上这门课的颜色。
        val lastIdx = periods.indexOfFirst { it.index == c.startPeriodIndex } + c.periodCount.coerceAtLeast(1) - 1
        val lastRow = if (lastIdx in periods.indices) geometry.rowOfPeriodIndex(periods[lastIdx].index) else row
        val spanRows = (lastRow - row + 1).coerceIn(1, totalRows - row)

        for (i in 0 until spanRows) {
            occupancy[c.dayOfWeek to (row + i)] =
                CellOccupancy(course = c, isStart = i == 0, span = spanRows)
        }

        // 块的像素范围：从首行顶到末行底，中间的空档（课间、午休）一并覆盖，视觉上连续
        val top = geometry.offsetOf(row)
        val bottomRow = row + spanRows - 1
        val bottom = geometry.offsetOf(bottomRow) + geometry.heightOf(bottomRow)
        val left = (c.dayOfWeek - 1) * dayWidthPx
        blocks.add(
            CourseBlock(
                course = c,
                day = c.dayOfWeek,
                left = left,
                top = top,
                right = left + dayWidthPx,
                bottom = bottom,
            ),
        )
    }

    return ScheduleLayout(geometry = geometry, occupancy = occupancy, blocks = blocks)
}

// ─────────────────────────────────────────────────────────────
// 卡片式课表（v0.9.0 起）：每小节一行，每门课合并一大节为一卡
// ─────────────────────────────────────────────────────────────

/** 一节课在网格里的放置：从第 rowStart 个小节行起，向下占 span 个小节行 */
private data class Placed(
    val course: Course,
    val rowStart: Int,
    val span: Int,
)

/**
 * 行高 / 间距 / 左栏 / 表头 常量（dp）。
 *
 * 左栏（[GUTTER_W]）只放小节序号，不再放时间刻度 —— 7 天同屏时每一列宽约
 * (屏宽-左栏)/7，左栏每省下 1 dp 就会还给每一列 1/7 dp，所以这里压到刚好放下
 * 一位数字（28 dp）。卡片外边距 [CARD_OUT_PAD] 与内边距 [CARD_IN_PAD] 同理，
 * 都是直接从列宽里扣的，能小则小。
 */
private val ROW_H = 64.dp
private val GAP = 5.dp
private val GUTTER_W = 28.dp
private val HEADER_H = 46.dp
private val CARD_OUT_PAD = 2.dp
private val CARD_IN_PAD = 4.dp

/**
 * 卡片式课表。
 *
 * 与旧版（Canvas 自绘、按"大节"成行）不同，这里按**小节**画行、用 Compose 布局直接摆放卡片：
 *   - 左栏只显示小节序号（大节起始行加粗）；
 *   - 每门课占有其 startPeriodIndex 起、periodCount 个小节，合并成一张卡；
 *   - 卡片上显示合并后的上课时间（如 08:00–09:40）+ 课名 + 教师·教室；
 *   - 表头显示星期与日期，今天高亮；
 *   - 点击课程卡 → 编辑；长按空白处 → 新增。
 *
 * 按周显示过滤（weekRanges + 单双周）与旧版 [computeLayout] 保持一致。
 */
@Composable
fun ScheduleGrid(
    courses: List<Course>,
    week: Int,
    dayCount: Int,
    periods: List<Period>,
    isDark: Boolean,
    term: Term?,
    onCourseTap: (Course) -> Unit,
    onEmptyLongPress: (day: Int, periodIndex: Int) -> Unit,
) {
    val density = LocalDensity.current
    val sortedPeriods = remember(periods) { periods.sortedBy { it.index } }
    val rowCount = sortedPeriods.size

    val todayDow = TermCalendar.dayOfWeek(TermCalendar.todayEpochDay())
    val currentMonday = term?.let {
        TermCalendar.mondayOfWeek(
            TermCalendar.weekOf(TermCalendar.todayEpochDay(), it.startEpochDay),
            it.startEpochDay,
        )
    }
    val viewedMonday = term?.let { TermCalendar.mondayOfWeek(week, it.startEpochDay) }
    val isCurrentWeek = viewedMonday != null && viewedMonday == currentMonday

    // 与 computeLayout 一致的按周过滤
    val validIndexes = sortedPeriods.map { it.index }.toSet()
    val visible = courses
        .filter { it.dayOfWeek in 1..dayCount }
        .filter { it.startPeriodIndex in validIndexes }
        .filter { WeekRanges.contains(it.weekRanges, week) }
        .filter { c ->
            when (c.weekParity) {
                WeekParity.EVERY -> true
                WeekParity.ODD -> week % 2 == 1
                WeekParity.EVEN -> week % 2 == 0
            }
        }

    val placedByDay = (1..dayCount).associateWith { d ->
        visible.filter { it.dayOfWeek == d }.mapNotNull { c ->
            val rs = sortedPeriods.indexOfFirst { p -> p.index == c.startPeriodIndex }
            if (rs < 0) null else {
                val span = c.periodCount.coerceAtLeast(1).coerceAtMost(rowCount - rs).coerceAtLeast(1)
                Placed(c, rs, span)
            }
        }
    }

    val lineColor = if (isDark) Color(0xFF33383F) else Color(0xFFECEEF0)
    val lineStrong = if (isDark) Color(0xFF454C56) else Color(0xFFDDE2E7)
    val gutterBg = if (isDark) Color(0xFF202329) else Color(0xFFFAFBFB)
    val headerBg = if (isDark) Color(0xFF1E2126) else Color(0xFFEDF1F6)
    val primaryText = if (isDark) Color(0xFFE8EAED) else Color(0xFF2B2F33)
    val mutedText = if (isDark) Color(0xFF8F959C) else Color(0xFF8A9099)
    val todayAccent = Color(0xFF0F9D8F)
    val todayTint = if (isDark) Color(0x1A3B3A) else Color(0xFFE7F6F3)

    val rowPx = with(density) { ROW_H.toPx() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // ── 表头：左上角 + 星期/日期 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(HEADER_H)
                .background(headerBg),
        ) {
            Box(
                modifier = Modifier
                    .width(GUTTER_W)
                    .fillMaxHeight()
                    .background(gutterBg)
                    .border(0.5.dp, lineColor),
                contentAlignment = Alignment.Center,
            ) {
                Text("节", fontSize = 11.sp, color = mutedText)
            }
            for (d in 1..dayCount) {
                val isToday = isCurrentWeek && d == todayDow
                val dateEpoch = viewedMonday?.plus((d - 1).toLong())
                val dateText = if (isToday) "今天" else (dateEpoch?.let { TermCalendar.formatShort(it) } ?: "–")
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(if (isToday) todayTint else Color.Transparent),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = TermCalendar.dayName(d),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        maxLines = 1,
                        color = if (isToday) todayAccent else primaryText,
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = dateText,
                        fontSize = 10.sp,
                        maxLines = 1,
                        color = if (isToday) todayAccent else mutedText,
                    )
                }
            }
        }

        // ── 主体：左栏 + 各天 ──
        Row(modifier = Modifier.fillMaxWidth()) {
            // 左栏：小节序号
            Column(
                modifier = Modifier
                    .width(GUTTER_W)
                    .background(gutterBg)
                    .border(0.5.dp, lineColor),
            ) {
                for (i in sortedPeriods.indices) {
                    val isBigStart = i % GridMetrics.PERIODS_PER_BLOCK == 0
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ROW_H),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "${i + 1}",
                            fontWeight = if (isBigStart) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 11.sp,
                            color = primaryText,
                        )
                        if (i != 0) {
                            val divider = if (isBigStart) lineStrong else lineColor
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(0.5.dp)
                                    .offset(y = (-ROW_H.value / 2).dp)
                                    .background(divider),
                            )
                        }
                    }
                }
            }

            // 各天列
            for (d in 1..dayCount) {
                val isToday = isCurrentWeek && d == todayDow
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height((rowCount * ROW_H.value).dp)
                        .background(if (isToday) todayTint else Color.Transparent)
                        .border(0.5.dp, lineColor)
                        .pointerInput(d, sortedPeriods, rowCount) {
                            detectTapGestures(
                                onLongPress = { offset ->
                                    if (rowCount <= 0) return@detectTapGestures
                                    val row = (offset.y / rowPx).toInt().coerceIn(0, rowCount - 1)
                                    val period = sortedPeriods.getOrNull(row) ?: return@detectTapGestures
                                    val occupied = placedByDay[d].orEmpty()
                                        .any { p -> row in p.rowStart until (p.rowStart + p.span) }
                                    if (!occupied) onEmptyLongPress(d, period.index)
                                },
                            )
                        },
                ) {
                    // 行分隔线（每小节一行）
                    for (i in sortedPeriods.indices) {
                        val isBig = i % GridMetrics.PERIODS_PER_BLOCK == 0
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(0.5.dp)
                                .offset(y = (i * ROW_H.value).dp)
                                .background(if (i != 0 && isBig) lineStrong else lineColor),
                        )
                    }
                    // 课程卡
                    placedByDay[d].orEmpty().forEach { p ->
                        val startP = sortedPeriods[p.rowStart]
                        val endP = sortedPeriods[p.rowStart + p.span - 1]
                        val top = p.rowStart * ROW_H.value + GAP.value / 2f
                        val h = p.span * ROW_H.value - GAP.value
                        CourseCard(
                            course = p.course,
                            startTime = startP.startTime,
                            endTime = endP.endTime,
                            span = p.span,
                            onClick = onCourseTap,
                            modifier = Modifier
                                .offset(y = top.dp)
                                .fillMaxWidth()
                                .padding(horizontal = CARD_OUT_PAD)
                                .height(h.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单张课程卡。
 *
 * 7 天同屏时一列正文只有 35–40 dp 宽，所以这里的排版原则是**先保证信息完整、再谈好看**：
 *   - 课名：11 sp，最多占 1–3 行（按卡片高度决定），靠自动换行而不是省略号，
 *     5 个字的课名在 40 dp 里换两行即可完整显示；
 *   - 时间：起止时间**各占一行**（[startTime] 换行 [endTime]）。写成 "08:00-09:40"
 *     一行时，Compose 会在任意字符处折行，出现 "08:0 / 0-0 / 0:40" 这种读不出来的断法；
 *   - 教师 / 教室：各占一行放在卡片底部，会被 [Spacer] 推到最下面，中间留白随卡片高度伸缩；
 *   - 只占一小节的窄卡（[span] <= 1）高度不足以放下四行字，只显示课名与时间。
 */
@Composable
private fun CourseCard(
    course: Course,
    startTime: String,
    endTime: String,
    span: Int,
    onClick: (Course) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = course.colorArgb?.let { Color(it.toULong()) } ?: CourseColors.forName(course.name)
    val textColor = if (isLight(bg)) Color(0xFF17181A) else Color.White
    val teacher = course.teacher?.takeIf { it.isNotBlank() }
    val room = course.room?.takeIf { it.isNotBlank() }
    val singleRow = span <= 1

    Box(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .clickable { onClick(course) }
            .padding(horizontal = CARD_IN_PAD, vertical = CARD_IN_PAD),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = course.name,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                color = textColor,
                maxLines = if (singleRow) 1 else 3,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = startTime + "\n" + endTime,
                fontSize = 9.sp,
                lineHeight = 11.sp,
                color = textColor.copy(alpha = 0.78f),
                maxLines = 2,
            )
            if (!singleRow && (teacher != null || room != null)) {
                Spacer(Modifier.weight(1f))
                if (teacher != null) {
                    Text(
                        text = teacher,
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        color = textColor.copy(alpha = 0.9f),
                        maxLines = 1,
                    )
                }
                if (room != null) {
                    Text(
                        text = room,
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        color = textColor.copy(alpha = 0.9f),
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

/** 相对亮度，决定块内文字用黑还是白 */
private fun isLight(color: Color): Boolean =
    0.299f * color.red + 0.587f * color.green + 0.114f * color.blue > 0.58f
