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
     * 左侧固定栏（节次号 + 时间刻度）的宽度。
     *
     * 一天 8 小节时整张表必然要纵向滚动，此时纵向空间已经不够用，
     * 横向就更不该浪费——8 小节 × 40 分钟的行本身很矮，节次号才是主要定位依据，
     * 时间刻度可以让位。所以"要滚动"时收窄到 48 dp，7 天内一屏放得下时保留 70 dp。
     */
    fun gutterWidthDp(needsVerticalScroll: Boolean): Float = if (needsVerticalScroll) 26f else GUTTER_WIDTH_DP

    const val GUTTER_WIDTH_DP = 36f
    const val TIME_RULER_DP = 34f
    const val HEADER_HEIGHT_DP = 34f
    const val BLOCK_GAP_DP = 2.5f
    const val CORNER_DP = 7f
    const val TEXT_PADDING_DP = 5f

    /**
     * 每分钟对应的像素数范围。
     *
     * 上下限的来由：行高 = 节次时长 × 该系数。
     *   下限 0.9 → 40 分钟的行 = 36 dp，再矮就放不下课名（这是可读性底线）
     *   上限 2.2 → 40 分钟的行 = 88 dp，再高一天就要滚很久
     */
    const val MIN_PX_PER_MINUTE = 0.9f
    const val MAX_PX_PER_MINUTE = 2.2f

    /** 空课表时的兜底系数 */
    const val FALLBACK_PX_PER_MINUTE = 1.6f

    /** 想让"典型的一节课"占多高。实际会按屏幕高度调整，这是基准值。 */
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
         * 每分钟像素数（pxPerMinute）的确定方式**不看节次之间的间隔**，只看节次本身的时长：
         * 先取"典型节次时长"的中位数，让这样一节占到 [GridMetrics.TARGET_TYPICAL_ROW_DP] 的高度，
         * 再夹到 [GridMetrics.MIN_PX_PER_MINUTE]..[GridMetrics.MAX_PX_PER_MINUTE]。
         *
         * 为什么不看间隔：早期版本用"间隔中位数"反推系数，结果被午休这种长间隔污染——
         * 8 小节作息里 7 个间隔是 20 分钟、只有午休是 170 分钟，排序后中位数正好落在 170，
         * 算出的系数被夹到下限，40 分钟的行只剩 30 dp，挤得看不清。
         * 用节次时长做基准就不会有这个偏移：本作息的中位时长是 40 分钟，与休息长短无关。
         *
         * @param availableHeightDp 课表区域的可用高度（dp）。传入时会让整天尽量铺满屏幕；
         *        传 0 则只用基准值。
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

            // 典型节次时长：取时长中位数（本作息 = 40 分钟）
            val sortedDurations = durations.sorted()
            val typicalMinutes = sortedDurations[sortedDurations.size / 2].toFloat()

            // 基准系数：让典型的一节占 TARGET_TYPICAL_ROW_DP 高
            var pxPerMinute = GridMetrics.TARGET_TYPICAL_ROW_DP / typicalMinutes

            // 有屏幕高度信息时，让整天尽量铺满；但不突破可读性上下限
            if (availableHeightDp > 0f) {
                val spanMinutes = (ends.max() - starts.min()).coerceAtLeast(1).toFloat()
                val fit = ((availableHeightDp - GridMetrics.HEADER_HEIGHT_DP * 2f) / spanMinutes)
                if (fit > pxPerMinute) pxPerMinute = fit
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
                ticks.add(GridClock.formatMinutes(m) to (m - base) * pxPerMinute)
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

    val dayWidthPx = with(density) { GridMetrics.dayWidthDp(dayCount).dp.toPx() }
    val headerHeightPx = with(density) { GridMetrics.HEADER_HEIGHT_DP.dp.toPx() }
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
        val viewportWidthPx = with(density) { maxWidth.toPx() }
        val availableHeightDp = maxHeight.value

        // 排版在拿到实际可用高度之后才算：整天尽量铺满屏幕，不写死行高
        val layout = remember(courses, periods, week, dayCount, availableHeightDp) {
            computeLayout(
                courses = courses,
                periods = periods,
                week = week,
                dayCount = dayCount,
                dayWidthPx = GridMetrics.dayWidthDp(dayCount),
                availableHeightDp = availableHeightDp,
            )
        }
        val geometry = layout.geometry

        // 需要纵向滚动时收窄左侧栏，把宽度让给 7 列课表
        val needsVerticalScroll = geometry.contentHeight > availableHeightDp
        val gutterTotalDp = GridMetrics.gutterWidthDp(needsVerticalScroll)
        val gutterWidthDp = if (needsVerticalScroll) gutterTotalDp * 0.62f else GridMetrics.GUTTER_WIDTH_DP
        val rulerWidthDp = gutterTotalDp - gutterWidthDp
        val gutterWidthPx = with(density) { gutterWidthDp.dp.toPx() }
        val rulerWidthPx = with(density) { rulerWidthDp.dp.toPx() }

        // 屏幕放得下就均分整宽，放不下就按固定列宽横向滚动
        val contentWidthPx = dayCount * dayWidthPx
        val availableForDays = viewportWidthPx - gutterWidthPx - rulerWidthPx
        val stretch = (availableForDays / contentWidthPx).coerceAtLeast(1f)
        val colWidthPx = dayWidthPx * stretch
        val gridWidthPx = colWidthPx * dayCount
        val contentHeightPx = geometry.contentHeight

        // 表头与左侧时间栏跟随纵向滚动，保证"行标题"始终和内容对齐
        val vScroll = rememberScrollState()

        Row(modifier = Modifier.fillMaxSize()) {

            // ── 左侧固定：节次号 + 时间（不随内容横向滚动，只跟着纵向滚动）──
            Canvas(
                modifier = Modifier
                    .width(with(density) { (gutterWidthPx + rulerWidthPx).toDp() })
                    .fillMaxSize()
                    .verticalScroll(vScroll),
            ) {
                drawIntoCanvas { canvas ->
                    val nc = canvas.nativeCanvas
                    // 表头角落
                    nc.drawRect(0f, 0f, size.width, headerHeightPx, Paint().apply { color = headerBg.toArgb() })
                    nc.drawRect(0f, headerHeightPx, size.width, size.height, Paint().apply { color = gutterBg.toArgb() })

                    strokePaint.textAlign = Paint.Align.RIGHT
                    strokePaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    strokePaint.textSize = with(density) { 11.sp.toPx() }
                    strokePaint.color = primaryText.toArgb()

                    for ((i, period) in geometry.periods.withIndex()) {
                        val top = headerHeightPx + geometry.offsets[i]
                        val h = geometry.heights[i]
                        // 节次号：行内垂直居中
                        nc.drawText("${period.index}", gutterWidthPx - 3f, top + h / 2f + strokePaint.textSize / 3f, strokePaint)
                    }

                    // 右侧细刻度：每半小时一条（左侧栏收窄时字号也降一档）
                    strokePaint.textAlign = Paint.Align.LEFT
                    strokePaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                    strokePaint.textSize = with(density) { (if (needsVerticalScroll) 8f else 9f).sp.toPx() }
                    strokePaint.color = secondaryText.toArgb()
                    for ((label, y) in geometry.rulerTicks) {
                        val yy = headerHeightPx + y
                        if (yy > size.height) break
                        nc.drawText(label, gutterWidthPx + 3f, yy + strokePaint.textSize * 0.9f, strokePaint)
                    }
                }
            }

            // ── 右侧：星期表头 + 课表主体（纵向滚动；放不下时横向滚动）──
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(vScroll)
                    .horizontalScroll(rememberScrollState()),
            ) {
                Canvas(
                    modifier = Modifier
                        .width(with(density) { gridWidthPx.toDp() })
                        .height(with(density) { headerHeightPx.toDp() }),
                ) {
                    drawIntoCanvas { canvas ->
                        val nc = canvas.nativeCanvas
                        nc.drawRect(0f, 0f, size.width, size.height, Paint().apply { color = headerBg.toArgb() })
                        strokePaint.textAlign = Paint.Align.CENTER
                        strokePaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        strokePaint.textSize = with(density) { 11.sp.toPx() }
                        strokePaint.color = primaryText.toArgb()
                        for (d in 1..dayCount) {
                            nc.drawText(
                                TermCalendar.dayName(d),
                                (d - 1) * colWidthPx + colWidthPx / 2,
                                size.height / 2 + strokePaint.textSize / 3,
                                strokePaint,
                            )
                        }
                    }
                }

                Canvas(
                    modifier = Modifier
                        .width(with(density) { gridWidthPx.toDp() })
                        .height(with(density) { contentHeightPx.toDp() })
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
                            strokeWidth = with(density) { 1f.dp.toPx() }
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

                        // ── 课程块 ──
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
                            strokePaint.textSize = with(density) { nameSp.sp.toPx() }

                            val maxWidth = right - left - textPadPx * 2
                            var y = top + textPadPx + strokePaint.textSize
                            for (line in wrapText(b.course.name, strokePaint, maxWidth, maxLines)) {
                                nc.drawText(line, left + textPadPx, y, strokePaint)
                                y += strokePaint.textSize * 1.12f
                            }

                            // 教室：只在还剩得下一行时画，字号比课名小一档
                            val room = b.course.room
                            val roomSp = (nameSp - 1.5f).coerceAtLeast(8f)
                            strokePaint.textSize = with(density) { roomSp.sp.toPx() }
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
