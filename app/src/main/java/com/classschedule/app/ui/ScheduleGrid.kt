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
import kotlin.math.abs

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
    fun dayWidthDp(dayCount: Int): Float = if (dayCount <= 5) 66f else 56f

    /**
     * 左侧固定栏总宽（节次号 + 时间刻度）。
     *
     * 一天 8 小节时整张表必然要纵向滚动，纵向空间已经不够用，
     * 横向就更不该浪费 —— 节次号是主要定位依据，时间刻度给一个紧凑格式即可。
     * 30 dp 是 8 sp 的 "8:00" 量出来的最小可用宽度。
     */
    const val GUTTER_WIDTH_DP = 30f

    /** 其中时间刻度占的宽度，剩下的留给节次号 */
    const val TIME_RULER_DP = 18f

    const val HEADER_HEIGHT_DP = 34f
    const val BLOCK_GAP_DP = 2.5f
    const val CORNER_DP = 7f
    const val TEXT_PADDING_DP = 5f

    /**
     * 每分钟对应的像素数范围。
     *
     * 上下限的来由：行高 = 节次时长 × 该系数。
     *   下限 0.95 → 40 分钟的行 = 38 dp，再矮课名就挤了（这是可读性底线）
     *   上限 2.2  → 40 分钟的行 = 88 dp，再高一天就要滚很久
     *
     * 注意下限必须小于"整天铺满屏幕所需的系数"（本作息约 1.05），
     * 否则一整天的课表会被顶出屏幕、必须滚动才能看全。
     */
    const val MIN_PX_PER_MINUTE = 0.95f
    const val MAX_PX_PER_MINUTE = 2.2f

    /** 空课表或没有屏幕尺寸信息时的兜底系数（= 典型一节占 [TARGET_TYPICAL_ROW_DP]，与 [build] 一致） */
    const val FALLBACK_PX_PER_MINUTE = 2f

    /** 想让"典型的一节课"占多高。没有屏幕尺寸信息时按它排版；有屏幕高度时优先整天铺满。 */
    const val TARGET_TYPICAL_ROW_DP = 80f

    /**
     * 课程块字号按块高自适应。
     * 40 分钟的小节在手机上大约 80–88 dp，100 分钟的大节约 220 dp，
     * 用固定字号会在矮行里溢出、在高行里显得很小，所以字号随可用高度收敛。
     */
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
 * 把节次时间换算成像素坐标。
 *
 * 关键点：**行高按真实时长成比例**，而不是固定行高。
 * 这样 100 分钟的大节自然比 40 分钟的小节高，左侧时间栏与内容严格对齐，
 * 节次之间的空档也会如实显示出来。
 */
class GridGeometry(
    val periods: List<Period>,
    val pxPerMinute: Float,
    /** periods[i] 对应行距内容区顶部的像素偏移 */
    val offsets: FloatArray,
    val heights: FloatArray,
    /** 表头时间刻度：每半小时一条 */
    val rulerTicks: List<Pair<String, Float>>,
) {
    val contentHeight: Float get() = offsets.lastOrNull()?.plus(heights.lastOrNull() ?: 0f) ?: 0f

    /** 某个节次行顶部的像素偏移 */
    fun offsetOf(index: Int): Float = offsets.getOrElse(index) { 0f }

    fun heightOf(index: Int): Float = heights.getOrElse(index) { 0f }

    /**
     * 找出某个 y 坐标落在哪个节次行上。
     * 落在两个节次之间的空档时，取距离更近的那一个，避免"点不中"。
     */
    fun periodAt(y: Float): Int? {
        if (periods.isEmpty()) return null
        for (i in periods.indices) {
            val top = offsets[i]
            val bottom = top + heights[i]
            if (y >= top && y <= bottom) return periods[i].index
        }
        var best = periods.first().index
        var bestDist = Float.MAX_VALUE
        for (i in periods.indices) {
            val center = offsets[i] + heights[i] / 2f
            val d = abs(center - y)
            if (d < bestDist) {
                bestDist = d
                best = periods[i].index
            }
        }
        return best
    }

    companion object {
        /**
         * 计算排版几何。
         *
         * 系数（pxPerMinute）的确定方式**不看节次之间的间隔**，只看节次本身的时长，
         * 并且**以"一整天正好铺满课表区域"为目标**：
         *
         *   1. 算出让 `[最早开始, 最晚结束]` 这段跨度正好占满可用高度的系数；
         *   2. 夹到 [GridMetrics.MIN_PX_PER_MINUTE]..[GridMetrics.MAX_PX_PER_MINUTE]：
         *      太大不好（一天要滚很久），太小也不好（课名挤成一团）。
         *
         * 为什么不看间隔：早期版本用"间隔中位数"反推系数，结果被午休这种长间隔污染 ——
         * 8 小节作息里 7 个间隔是 20 分钟、只有午休是 170 分钟，排序后中位数落在 170，
         * 算出的系数被夹到下限，40 分钟的行只剩 30 dp，挤得看不清。
         * 用节次时长做基准就不会有这个偏移：本作息的中位时长是 40 分钟，与休息长短无关。
         *
         * 为什么目标是"铺满"：课表是拿来"一眼看全一天"的。若先把典型一节课定成 80 dp
         * 再按屏幕放大，8 小节作息要 1220 dp，而手机课表区域只有 640 dp 左右 ——
         * 一屏只能看到半天，得滚动才能看完，这不符合课表的使用方式。
         *
         * @param availableHeightDp 课表内容区的可用高度（dp）。传 0 则只用兜底系数。
         */
        fun build(periods: List<Period>, availableHeightDp: Float = 0f): GridGeometry {
            if (periods.isEmpty()) {
                return GridGeometry(
                    emptyList(), GridMetrics.FALLBACK_PX_PER_MINUTE,
                    FloatArray(0), FloatArray(0), emptyList(),
                )
            }
            val sorted = periods.sortedBy { it.index }
            val starts = sorted.map { GridClock.minutesOf(it.startTime) }
            val ends = sorted.map { GridClock.minutesOf(it.endTime) }
            val durations = sorted.map { (GridClock.minutesOf(it.endTime) - GridClock.minutesOf(it.startTime)).coerceAtLeast(1) }

            val spanMinutes = (ends.max() - starts.min()).coerceAtLeast(1).toFloat()

            // 首要目标：整天正好铺满可用高度
            var pxPerMinute = if (availableHeightDp > 0f) {
                availableHeightDp / spanMinutes
            } else {
                GridMetrics.FALLBACK_PX_PER_MINUTE
            }

            pxPerMinute = pxPerMinute.coerceIn(GridMetrics.MIN_PX_PER_MINUTE, GridMetrics.MAX_PX_PER_MINUTE)

            val base = starts.min()
            val offsets = FloatArray(sorted.size) { (starts[it] - base) * pxPerMinute }
            val heights = FloatArray(sorted.size) { durations[it] * pxPerMinute }

            // 时间刻度：从第一个节次开始，每 30 分钟一条
            val ticks = mutableListOf<Pair<String, Float>>()
            val lastMinute = ends.max()
            var m = base
            while (m <= lastMinute) {
                ticks.add(GridClock.formatMinutesCompact(m) to (m - base) * pxPerMinute)
                m += 30
            }

            return GridGeometry(sorted, pxPerMinute, offsets, heights, ticks)
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
        val idx = periods.indexOfFirst { it.index == c.startPeriodIndex }
        if (idx < 0) continue
        val span = c.periodCount.coerceAtLeast(1).coerceAtMost(periods.size - idx)

        for (i in 0 until span) {
            occupancy[c.dayOfWeek to periods[idx + i].index] =
                CellOccupancy(course = c, isStart = i == 0, span = span)
        }

        // 块的像素范围：从首节顶部到末节底部。跨到空档时把空档也一并覆盖（视觉上连续）
        val top = geometry.offsetOf(idx)
        val lastIdx = idx + span - 1
        val bottom = geometry.offsetOf(lastIdx) + geometry.heightOf(lastIdx)
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
        val widthForDaysDp = with(density) { (viewportWidthPx - (GridMetrics.GUTTER_WIDTH_DP + GridMetrics.TIME_RULER_DP).dp.toPx()).toDp().value }
        val stretch = (widthForDaysDp / baseGridWidthDp).coerceAtLeast(1f)
        val colWidthDp = dayWidthDp * stretch
        val gridWidthDp = colWidthDp * dayCount

        // 一屏放得下就正好铺满（不留滚动条），放不下才滚动
        val needsVerticalScroll = contentHeightDp > naturalBodyHeightDp + 0.5f
        val bodyHeightDp = if (needsVerticalScroll) contentHeightDp else naturalBodyHeightDp
        val needsHorizontalScroll = gridWidthDp > widthForDaysDp + 0.5f

        val colWidthPx = with(density) { colWidthDp.dp.toPx() }
        val gutterTotalDp = GridMetrics.GUTTER_WIDTH_DP + GridMetrics.TIME_RULER_DP
        val gutterWidthPx = with(density) { GridMetrics.GUTTER_WIDTH_DP.dp.toPx() }

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

                        for ((i, period) in geometry.periods.withIndex()) {
                            val top = geometry.offsets[i]
                            val h = geometry.heights[i]
                            // 节次之间的分隔线，与右侧网格线同高
                            nc.drawLine(0f, top, size.width, top, gutterLine)
                            // 节次号：行内垂直居中
                            strokePaint.textAlign = Paint.Align.RIGHT
                            strokePaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                            strokePaint.textSize = 11.sp.toPx()
                            strokePaint.color = primaryText.toArgb()
                            nc.drawText(
                                "${period.index}",
                                gutterWidthPx - 2f,
                                top + h / 2f + strokePaint.textSize / 3f,
                                strokePaint,
                            )
                        }

                        // 右侧时间刻度：每半小时一条
                        strokePaint.textAlign = Paint.Align.LEFT
                        strokePaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                        strokePaint.textSize = 8.sp.toPx()
                        strokePaint.color = secondaryText.toArgb()
                        for ((label, y) in geometry.rulerTicks) {
                            nc.drawText(label, gutterWidthPx + 2f, y + strokePaint.textSize * 0.9f, strokePaint)
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
                        for (i in geometry.periods.indices) {
                            val top = geometry.offsets[i]
                            val bottom = top + geometry.heights[i]
                            nc.drawLine(0f, top, size.width, top, linePaint)
                            nc.drawLine(0f, bottom, size.width, bottom, linePaint)
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

                            strokePaint.color = textColor.toArgb()
                            strokePaint.textAlign = Paint.Align.LEFT
                            strokePaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                            strokePaint.textSize = nameSp.sp.toPx()

                            val maxWidth = right - left - textPadPx * 2
                            var y = top + textPadPx + strokePaint.textSize
                            for (line in wrapText(b.course.name, strokePaint, maxWidth, maxLines)) {
                                nc.drawText(line, left + textPadPx, y, strokePaint)
                                y += strokePaint.textSize * 1.12f
                            }

                            // 教室：只在还剩得下一行时画，字号比课名小一档
                            val room = b.course.room
                            val roomSp = (nameSp - 1.5f).coerceAtLeast(8f)
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
