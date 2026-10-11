package com.classschedule.app.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.classschedule.app.data.Course
import com.classschedule.app.data.Period
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

/**
 * 课表网格。
 *
 * 用 Canvas 自绘而非嵌套 Compose 布局，原因：
 *   行高按真实分钟数成比例、课程块跨行、块内要塞"课名 + 教室"两行小字，
 *   自绘在排版精度和滚动性能上都更可控。
 */
@Composable
fun ScheduleGrid(
    courses: List<Course>,
    week: Int,
    dayCount: Int,
    periods: List<Period>,
    isDark: Boolean,
    onCourseTap: (Course) -> Unit,
    onEmptyLongPress: (day: Int, periodIndex: Int) -> Unit,
) {
    val density = LocalDensity.current

    val blockGapPx = with(density) { GridMetrics.BLOCK_GAP_DP.dp.toPx() }
    val cornerPx = with(density) { GridMetrics.CORNER_DP.dp.toPx() }
    val textPadPx = with(density) { GridMetrics.TEXT_PADDING_DP.dp.toPx() }

    val lineColor = if (isDark) Color(0xFF33383F) else Color(0xFFE4E8ED)
    /** 行内每半小时的浅色横线，比行分隔线更淡 */
    val faintLineColor = if (isDark) Color(0xFF262A30) else Color(0xFFF0F3F7)
    val gutterBg = if (isDark) Color(0xFF23262B) else Color(0xFFF5F7FA)
    val headerBg = if (isDark) Color(0xFF1F2227) else Color(0xFFEDF1F6)
    val primaryText = if (isDark) Color(0xFFE8EAED) else Color(0xFF1F2328)
    val secondaryText = if (isDark) Color(0xFF8F959C) else Color(0xFF6B7280)

    val strokePaint = remember(isDark) { Paint().apply { isAntiAlias = true } }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 这里必须用 BoxWithConstraints 才能拿到课表区域真正可用的高度：
        // 整天尽量铺满屏幕的行高就靠它算出来，写死行高会出现"下半个屏幕全是空白"。
        val viewportWidthPx = with(density) { maxWidth.toPx() }
        val viewportHeightDp = maxHeight.value
        val naturalBodyHeightDp = (viewportHeightDp - GridMetrics.HEADER_HEIGHT_DP).coerceAtLeast(0f)

        // 列宽：屏幕放得下就把整宽均分给各天，放不下就按固定列宽横向滚动。
        // 留 1 px 余量，避免 dp/px 取整后把最后一列挤出屏幕。
        val dayWidthDp = GridMetrics.dayWidthDp(dayCount)
        val widthForDaysDp = with(density) {
            (viewportWidthPx - GridMetrics.GUTTER_WIDTH_DP.dp.toPx() - 1f).toDp().value
        }.coerceAtLeast(1f)
        val baseGridWidthDp = dayCount * dayWidthDp
        val stretch = (widthForDaysDp / baseGridWidthDp).coerceAtLeast(1f)
        val colWidthDp = dayWidthDp * stretch
        val gridWidthDp = colWidthDp * dayCount
        val colWidthPx = with(density) { colWidthDp.dp.toPx() }

        // 排版在拿到实际可用高度之后才算：整天尽量铺满屏幕，不写死行高
        val layout = remember(courses, periods, week, dayCount, naturalBodyHeightDp, colWidthPx) {
            computeLayout(
                courses = courses,
                periods = periods,
                week = week,
                dayCount = dayCount,
                dayWidthPx = colWidthPx,
                availableHeightDp = naturalBodyHeightDp,
            )
        }
        val geometry = layout.geometry
        val contentHeightDp = with(density) { geometry.contentHeight.toDp().value }

        // 一屏放得下就正好铺满（不留滚动条），放不下才滚动
        val needsVerticalScroll = contentHeightDp > naturalBodyHeightDp + 0.5f
        val bodyHeightDp = if (needsVerticalScroll) contentHeightDp else naturalBodyHeightDp
        val needsHorizontalScroll = gridWidthDp > widthForDaysDp + 0.5f

        val gutterTotalDp = GridMetrics.GUTTER_WIDTH_DP
        val gutterWidthPx = with(density) { GridMetrics.GUTTER_WIDTH_DP.dp.toPx() }
        /** 整点刻度占左栏最右这一条，剩下的大节时间与节次号往左排 */
        val hourRulerWidthPx = with(density) { GridMetrics.HOUR_RULER_DP.dp.toPx() }

        // 左侧栏与星期表头共用同一个横向滚动状态：横向滑动时两者一起走，列才对得上
        val hScroll = rememberScrollState()
        // 左栏与课表主体共用同一个纵向滚动状态：纵向滑动时两者一起走，行才对得上
        val vScroll = rememberScrollState()

        Column {
            // ── 第一行：左上角留白 + 星期表头（不参与纵向滚动）──
            Row {
                Canvas(modifier = Modifier.width(gutterTotalDp.dp).height(GridMetrics.HEADER_HEIGHT_DP.dp)) {
                    drawRect(headerBg)
                }
                Row(modifier = Modifier.horizontalScroll(hScroll)) {
                    Canvas(modifier = Modifier.width(gridWidthDp.dp).height(GridMetrics.HEADER_HEIGHT_DP.dp)) {
                        drawIntoCanvas { canvas ->
                            val nc = canvas.nativeCanvas
                            nc.drawRect(0f, 0f, size.width, size.height, Paint().apply { color = headerBg.toArgb() })
                            strokePaint.textAlign = Paint.Align.CENTER
                            strokePaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                            strokePaint.textSize = 11.sp.toPx()
                            strokePaint.color = primaryText.toArgb()
                            val dw = colWidthPx.dp.toPx()
                            for (d in 1..dayCount) {
                                nc.drawText(
                                    TermCalendar.dayName(d),
                                    (d - 1) * dw + dw / 2,
                                    size.height / 2 + strokePaint.textSize / 3,
                                    strokePaint,
                                )
                            }
                        }
                    }
                }
            }

            // ── 第二行：左侧栏（节次号 + 大节时间 + 整点刻度）/ 课表主体 ──
            //    纵向的滚动状态由左栏与主体共用，两边一起滚，行才不会错位；
            //    横向只滚课表主体，左栏留在原地当标尺。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(bodyHeightDp.dp)
                    .then(if (needsVerticalScroll) Modifier.verticalScroll(vScroll) else Modifier),
            ) {
                // ── 左栏：高度与内容区完全一致，所以节次号逐行对准 ──
                Canvas(
                    modifier = Modifier
                        .width(gutterTotalDp.dp)
                        .height(bodyHeightDp.dp),
                ) {
                    drawIntoCanvas { canvas ->
                        val nc = canvas.nativeCanvas
                        val gutterLine = Paint().apply { color = lineColor.toArgb(); strokeWidth = 1.dp.toPx() }
                        nc.drawRect(0f, 0f, size.width, size.height, Paint().apply { color = gutterBg.toArgb() })
                        // 左栏与课表之间的那条竖线，与右侧网格线接上
                        nc.drawLine(size.width - 0.5f, 0f, size.width - 0.5f, size.height, gutterLine)

                        // 节次号：只写这一行的第一个小节号（第 1 大节就写 "1"），靠近右边的时间栏
                        val numPaint = Paint().apply {
                            isAntiAlias = true
                            textAlign = Paint.Align.RIGHT
                            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                            textSize = 10.sp.toPx()
                            color = primaryText.toArgb()
                        }
                        // 大节时间：写清这一行是几点到几点，例如 "8:00-9:40"
                        val timePaint = Paint().apply {
                            isAntiAlias = true
                            textAlign = Paint.Align.LEFT
                            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                            textSize = 7.5.sp.toPx()
                            color = secondaryText.toArgb()
                        }
                        // 整点刻度：8:00 / 9:00 / 10:00 … 单独一列靠最右，与右侧内容区贴边
                        val hourPaint = Paint().apply {
                            isAntiAlias = true
                            textAlign = Paint.Align.RIGHT
                            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                            textSize = 9.sp.toPx()
                            color = primaryText.toArgb()
                        }

                        val timeLeft = hourRulerWidthPx
                        val numRight = timeLeft - 2f

                        for ((i, row) in geometry.rows.withIndex()) {
                            val top = geometry.offsets[i]
                            val cy = top + geometry.heights[i] / 2f
                            // 行分隔线：和右侧网格线同高，左右一眼能对上
                            nc.drawLine(0f, top, size.width, top, gutterLine)
                            val num = geometry.periodIndexOfRow(i) ?: (i + 1)
                            nc.drawText("$num", numRight, cy + numPaint.textSize / 3f, numPaint)
                            nc.drawText(row.timeLabel, timeLeft, cy + timePaint.textSize / 3f, timePaint)
                        }

                        // 整点刻度：整点正好落在课程块边界上时只补一小段，免得和行分隔线糊成一条
                        for ((label, y) in geometry.hourTicks) {
                            nc.drawText(label, size.width - 2f, y + hourPaint.textSize / 3f, hourPaint)
                        }
                    }
                }

                // ── 课表主体：网格与课程块都用 Canvas 自绘，横向单独滚动 ──
                Row(modifier = Modifier.horizontalScroll(hScroll)) {
                Canvas(
                    modifier = Modifier
                        .width(gridWidthDp.dp)
                        .height(bodyHeightDp.dp)
                        .pointerInput(layout, dayCount, colWidthPx) {
                            detectTapGestures(
                                onTap = { offset ->
                                    val day = (offset.x / colWidthPx).toInt() + 1
                                    if (day !in 1..dayCount) return@detectTapGestures
                                    val hit = layout.blocks.lastOrNull { it.day == day && it.contains(offset.x, offset.y) }
                                    if (hit != null) {
                                        onCourseTap(hit.course)
                                        return@detectTapGestures
                                    }
                                    // 点空白处不做事，避免误触
                                },
                                onLongPress = { offset ->
                                    val day = (offset.x / colWidthPx).toInt() + 1
                                    if (day !in 1..dayCount) return@detectTapGestures
                                    val occupied = layout.blocks.any { it.day == day && it.contains(offset.x, offset.y) }
                                    if (occupied) return@detectTapGestures
                                    val periodIndex = geometry.periodAt(offset.y) ?: return@detectTapGestures
                                    onEmptyLongPress(day, periodIndex)
                                },
                            )
                        },
                ) {
                    drawIntoCanvas { canvas ->
                        val nc = canvas.nativeCanvas

                        // ── 网格线 ──
                        val linePaint = Paint().apply {
                            color = lineColor.toArgb()
                            strokeWidth = 1.dp.toPx()
                        }
                        for (d in 0..dayCount) {
                            val x = d * colWidthPx
                            nc.drawLine(x, 0f, x, size.height, linePaint)
                        }
                        for (i in geometry.rows.indices) {
                            val top = geometry.offsets[i]
                            val bottom = top + geometry.heights[i]
                            nc.drawLine(0f, top, size.width, top, linePaint)
                            nc.drawLine(0f, bottom, size.width, bottom, linePaint)
                        }

                        // ── 行内每 30 分钟一条浅色横线（学校课表也是这么画的，用来估时间）──
                        val faintPaint = Paint().apply {
                            color = faintLineColor.toArgb()
                            strokeWidth = 1.dp.toPx()
                        }
                        for ((i, row) in geometry.rows.withIndex()) {
                            val top = geometry.offsets[i]
                            var m = (row.startMinutes / 30 + 1) * 30
                            while (m < row.endMinutes) {
                                val y = top + (m - row.startMinutes) * geometry.pxPerMinute
                                if (y > top + 1f && y < top + geometry.heights[i] - 1f) {
                                    nc.drawLine(0f, y, size.width, y, faintPaint)
                                }
                                m += 30
                            }
                        }

                        // ── 课程块：撑满所在格（左右到列边、上下到行边），只留一点内缩 ──
                        for (b in layout.blocks) {
                            val left = b.left * stretch + blockGapPx
                            val top = b.top + blockGapPx
                            val right = b.right * stretch - blockGapPx
                            val bottom = b.bottom - blockGapPx

                            val bg = Color(
                                b.course.colorArgb
                                    ?: CourseColors.forName(b.course.name).toArgb().toLong(),
                            )
                            nc.drawRoundRect(
                                left, top, right, bottom, cornerPx, cornerPx,
                                Paint().apply { color = bg.toArgb() },
                            )

                            val textColor = if (isLight(bg)) Color(0xFF17181A) else Color.White
                            val blockHeightPx = bottom - top
                            val nameSp = GridMetrics.nameTextSizeSp(blockHeightPx, density.density)
                            val maxLines = GridMetrics.nameMaxLines(blockHeightPx, density.density)

                            val maxWidth = right - left - textPadPx * 2

                            // ── 第一行：上课时间，写在块里面（用户要求的学校课表样式）──
                            val blockRow = geometry.rowOfPeriodIndex(b.course.startPeriodIndex)
                            val timeLabel = geometry.rows.getOrNull(blockRow)?.timeLabel
                            val timeSp = (nameSp - 1.5f).coerceAtLeast(7.5f)
                            strokePaint.color = textColor.toArgb()
                            strokePaint.textAlign = Paint.Align.LEFT
                            strokePaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                            strokePaint.textSize = timeSp.sp.toPx()
                            var y = top + textPadPx + strokePaint.textSize
                            if (!timeLabel.isNullOrEmpty()) {
                                nc.drawText(timeLabel, left + textPadPx, y, strokePaint)
                                y += strokePaint.textSize * 1.25f
                            }

                            // ── 课名（可换行，块矮时只给一行）──
                            strokePaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                            strokePaint.textSize = nameSp.sp.toPx()
                            y += strokePaint.textSize * 0.25f
                            for (line in wrapText(b.course.name, strokePaint, maxWidth, maxLines)) {
                                nc.drawText(line, left + textPadPx, y, strokePaint)
                                y += strokePaint.textSize * 1.12f
                            }

                            // 教室：只在还剩得下一行时画，字号比课名小一档
                            val room = b.course.room
                            val roomSp = (nameSp - 2f).coerceAtLeast(7.5f)
                            strokePaint.textSize = roomSp.sp.toPx()
                            if (!room.isNullOrBlank() && y + strokePaint.textSize * 0.6f < bottom - 1f) {
                                strokePaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                                nc.drawText(
                                    room.take(10),
                                    left + textPadPx,
                                    minOf(y + strokePaint.textSize * 0.45f, bottom - 2f),
                                    strokePaint,
                                )
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

/** 简单按字符宽度换行：中文没有词边界，不能按空格切 */
private fun wrapText(text: String, paint: Paint, maxWidth: Float, maxLines: Int): List<String> {
    if (text.isEmpty() || maxWidth <= 0) return emptyList()
    val lines = mutableListOf<String>()
    val current = StringBuilder()
    for (ch in text) {
        if (paint.measureText(current.toString() + ch) > maxWidth && current.isNotEmpty()) {
            lines.add(current.toString())
            current.clear()
            if (lines.size == maxLines) return lines
        }
        current.append(ch)
    }
    if (current.isNotEmpty() && lines.size < maxLines) lines.add(current.toString())
    return lines
}

/** 相对亮度，决定块内文字用黑还是白 */
private fun isLight(color: Color): Boolean =
    0.299f * color.red + 0.587f * color.green + 0.114f * color.blue > 0.58f
