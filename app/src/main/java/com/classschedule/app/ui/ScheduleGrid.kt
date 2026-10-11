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

    /** 一"大节"由几小节组成。课表按大节分行，一格就是一门课占位。 */
    const val PERIODS_PER_BLOCK = 2

    /**
     * 左侧固定栏总宽（节次号 + 大节时间）。
     *
     * 时间文字用 7.5 sp，最长的 "14:30-16:10" 约占 34 dp，剩下的给节次号。
     */
    const val GUTTER_WIDTH_DP = 62f

    /** 其中时间文字占的宽度，剩下的留给节次号 */
    const val TIME_LABEL_DP = 34f

    const val HEADER_HEIGHT_DP = 34f
    const val BLOCK_GAP_DP = 2.5f
    const val CORNER_DP = 7f
    const val TEXT_PADDING_DP = 5f

    /**
     * 一格（一个大节）的理想高度。
     *
     * 学校课表那一格量出来约 96 dp，这里取 110 dp —— 手机上比它略高一点更好点按，
     * 也不会让"一天四门课"只占屏幕上半截。屏幕放得下时行高不会超过这个值：
     * 课表要"一眼看全一天"，但也不是把格子撑满屏幕就好，那样反而显得松散。
     */
    const val TARGET_ROW_DP = 110f

    /** 一格的最小高度，低于这个值课名就挤了 */
    const val MIN_ROW_DP = 60f

    /**
     * 一格的最大高度。
     *
     * 行数很少（比如一天只有 2 大节）时不该把格子拉成大块，
     * 所以上限就取理想高度：够用即可，剩下的地方留白。
     */
    const val MAX_ROW_DP = TARGET_ROW_DP

    /** 没有屏幕尺寸信息时的兜底格高 */
    const val FALLBACK_ROW_DP = 80f

    fun nameTextSizeSp(rowHeightPx: Float, density: Float): Float {
        val dp = rowHeightPx / density
        return when {
            dp >= 150f -> 14f
            dp >= 120f -> 13f
            dp >= 92f -> 12f
            dp >= 68f -> 11f
            dp >= 46f -> 10f
            else -> 9f
        }
    }

    /** 课程名最多显示几行（块矮时只给一行） */
    fun nameMaxLines(rowHeightPx: Float, density: Float): Int {
        val dp = rowHeightPx / density
        return if (dp >= 110f) 3 else if (dp >= 76f) 2 else 1
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
    /** 行高（所有行相同） */
    val rowHeight: Float,
    /** 行距内容区顶部的像素偏移 */
    val offsets: FloatArray,
    val heights: FloatArray,
    /** 行高度对应的"每分钟像素数"，仅供刻度换算使用 */
    val pxPerMinute: Float,
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
     * 落在边界或范围外时按最近一行吸附，避免"点不中"。
     */
    fun periodAt(y: Float): Int? {
        if (rows.isEmpty()) return null
        if (rowHeight <= 0f) return periodIndexOfRow(0)
        val row = (y / rowHeight).toInt().coerceIn(0, rows.size - 1)
        return periodIndexOfRow(row)
    }

    companion object {
        /**
         * 计算排版几何。
         *
         * @param availableHeightDp 课表内容区的可用高度（dp）。传 0 则用兜底格高。
         */
        fun build(
            periods: List<Period>,
            availableHeightDp: Float = 0f,
            periodsPerBlock: Int = GridMetrics.PERIODS_PER_BLOCK,
        ): GridGeometry {
            if (periods.isEmpty()) {
                return GridGeometry(
                    emptyList(), emptyList(), GridMetrics.FALLBACK_ROW_DP,
                    FloatArray(0), FloatArray(0), 1f,
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

            // 行高：屏幕放得下就按理想高度（不撑满，剩下留白），行多放不下才压缩到下限
            val rowHeight = if (availableHeightDp > 0f) {
                minOf(GridMetrics.TARGET_ROW_DP, availableHeightDp / rows.size)
                    .coerceIn(GridMetrics.MIN_ROW_DP, GridMetrics.MAX_ROW_DP)
            } else {
                GridMetrics.FALLBACK_ROW_DP
            }

            val offsets = FloatArray(rows.size) { it * rowHeight }
            val heights = FloatArray(rows.size) { rowHeight }

            // 每分钟像素数：只是行高摊到时间上的换算，用于把半小时刻度放到行的正确位置
            val rowMinutes = rows.map { (it.endMinutes - it.startMinutes).coerceAtLeast(1) }
            val typicalMinutes = rowMinutes.sorted()[rowMinutes.size / 2].toFloat()

            return GridGeometry(
                periods = sorted,
                rows = rows,
                rowHeight = rowHeight,
                offsets = offsets,
                heights = heights,
                pxPerMinute = rowHeight / typicalMinutes,
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

        // 课占几个小节 → 占几行（一大节两小节，所以 1 小节也算占满整行）
        val spanRows = ((c.periodCount.coerceAtLeast(1) + GridMetrics.PERIODS_PER_BLOCK - 1) /
            GridMetrics.PERIODS_PER_BLOCK).coerceIn(1, totalRows - row)

        for (i in 0 until spanRows) {
            occupancy[c.dayOfWeek to (row + i)] =
                CellOccupancy(course = c, isStart = i == 0, span = spanRows)
        }

        // 块的像素范围：从首行顶到末行底。行是等高的，不再有时间空档。
        val top = geometry.offsetOf(row)
        val lastRow = row + spanRows - 1
        val bottom = geometry.offsetOf(lastRow) + geometry.heightOf(lastRow)
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

        // 排版在拿到实际可用高度之后才算：整天尽量铺满屏幕，不写死行高
        val layout = remember(courses, periods, week, dayCount, naturalBodyHeightDp) {
            computeLayout(
                courses = courses,
                periods = periods,
                week = week,
                dayCount = dayCount,
                dayWidthPx = GridMetrics.dayWidthDp(dayCount),
                availableHeightDp = naturalBodyHeightDp,
            )
        }
        val geometry = layout.geometry
        val contentHeightDp = with(density) { geometry.contentHeight.toDp().value }

        // 屏幕放得下就均分整宽，放不下就按固定列宽横向滚动
        val dayWidthDp = GridMetrics.dayWidthDp(dayCount)
        val baseGridWidthDp = dayCount * dayWidthDp
        val widthForDaysDp = with(density) { (viewportWidthPx - GridMetrics.GUTTER_WIDTH_DP.dp.toPx()).toDp().value }
        val stretch = (widthForDaysDp / baseGridWidthDp).coerceAtLeast(1f)
        val colWidthDp = dayWidthDp * stretch
        val gridWidthDp = colWidthDp * dayCount

        // 一屏放得下就正好铺满（不留滚动条），放不下才滚动
        val needsVerticalScroll = contentHeightDp > naturalBodyHeightDp + 0.5f
        val bodyHeightDp = if (needsVerticalScroll) contentHeightDp else naturalBodyHeightDp
        val needsHorizontalScroll = gridWidthDp > widthForDaysDp + 0.5f

        val colWidthPx = with(density) { colWidthDp.dp.toPx() }
        val gutterTotalDp = GridMetrics.GUTTER_WIDTH_DP
        val gutterWidthPx = with(density) { GridMetrics.GUTTER_WIDTH_DP.dp.toPx() }
        val timeLabelWidthPx = with(density) { GridMetrics.TIME_LABEL_DP.dp.toPx() }

        // 左侧栏与课表主体共用同一个纵向滚动状态：左边一滚，右边跟着滚，行才不会错位
        val vScroll = rememberScrollState()

        Column {
            // ── 第一行：左上角留白 + 星期表头（不参与纵向滚动）──
            Row {
                Canvas(modifier = Modifier.width(gutterTotalDp.dp).height(GridMetrics.HEADER_HEIGHT_DP.dp)) {
                    drawRect(headerBg)
                }
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

            // ── 第二行：左侧节次号 + 时间刻度 / 课表主体 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(bodyHeightDp.dp)
                    .then(if (needsVerticalScroll) Modifier.verticalScroll(vScroll) else Modifier)
                    .then(if (needsHorizontalScroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
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

                        // 节次号：只写这一行的第一个小节号（第 1 大节就写 "1"）
                        val numPaint = Paint().apply {
                            isAntiAlias = true
                            textAlign = Paint.Align.RIGHT
                            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                            textSize = 10.sp.toPx()
                            color = primaryText.toArgb()
                        }
                        // 行时间：写清这一行是几点到几点，例如 "8:00-9:40"
                        val timePaint = Paint().apply {
                            isAntiAlias = true
                            textAlign = Paint.Align.LEFT
                            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                            textSize = 7.5.sp.toPx()
                            color = secondaryText.toArgb()
                        }

                        for ((i, row) in geometry.rows.withIndex()) {
                            val top = geometry.offsets[i]
                            val h = geometry.heights[i]
                            val cy = top + h / 2f

                            // 行分隔线：和右侧网格线同高，左右一眼能对上
                            nc.drawLine(0f, top, size.width, top, gutterLine)

                            val num = geometry.periodIndexOfRow(i) ?: (i + 1)
                            nc.drawText("$num", gutterWidthPx - timeLabelWidthPx - 2f, cy + numPaint.textSize / 3f, numPaint)
                            nc.drawText(row.timeLabel, gutterWidthPx - timeLabelWidthPx, cy + timePaint.textSize / 3f, timePaint)
                        }
                    }
                }

                // ── 课表主体：网格与课程块都用 Canvas 自绘 ──
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
