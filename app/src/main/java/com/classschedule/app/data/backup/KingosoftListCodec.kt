package com.classschedule.app.data.backup

import com.classschedule.app.data.Period
import com.classschedule.app.data.Term
import com.classschedule.app.data.WeekParity
import com.classschedule.app.util.WeekRanges

/**
 * 青果（Kingosoft）教务系统「列表」模式课表导出的解析（F-38 扩展）。
 *
 * 该格式把整门课的时间地点挤在一个单元格里：
 * ```
 * 1-15,17-18周 一[3-4] 弘善楼B103(64),1-15,17-18周 五[3-4] 弘德楼A410物联网技术实验室(72)
 * ```
 * 字段顺序固定：`<周次>周 (双) <星期单字>[<起>-<止>] <教室>(<容量>)`，多时段用逗号分隔。
 *
 * 两个必须小心的地方：
 * 1. **周次自身可能含逗号**（`1-15,17-18`）。所以不能直接按逗号切分时段，
 *    必须先用正则整段匹配"周次 + 星期 + 节次"，剩下的才是教室。
 * 2. **一门课对应多条时间条目**（同门课不同星期/节次要各占一条），
 *    与 CSV「一行一条」的口径不同。
 *
 * 只取正课表格；实验项目分项（同一 sheet 下半部分，格式反向：
 * `四[1-2节](10周)`，周次在括号里）不导入 —— 分组实验挤在同一格期，
 * 展示效果不如手动加一门「XX实验」，见计划文档实验导入取舍一节。
 */
object KingosoftListCodec {

    /** 表头提示词：出现「上课时间地点」这一列即认为是青果列表格式 */
    private const val COL_TIME_PLACE = "上课时间地点"
    private const val COL_COURSE = "课程"
    private const val COL_TEACHER = "任课教师"

    /** 实验分项表的表头特征（第二张表） */
    private val EXPERIMENT_HEADER_HINT = "实验项目"

    /**
     * 时段段：`<周次>周 (双) <星期>[<起>-<止>] <教室>`
     *
     * 周次组 `(?:\d{1,2}[sep]\d{1,2})(?:\s*,\s*\d{1,2}[sep]\d{1,2})*` 里
     * 第一个 `[sep]` 与后续逗号配合，保证 `1-15,17-18` 被整体吃掉。
     *
     * 教室部分是 `([^,]*)`（**不跨逗号**），不能用 `(.*)`：
     * 贪婪匹配会把后面所有时段全吞进教室，导致「一门课含多时段」
     * 只解析出第一条（实测踩过：8 门课里多门只出 1 条）。
     *
     * `(双)` 单独捕获成一组：它属于**本段**，不能靠「前一段文本里有没有」来判断，
     * 否则上一段的单双周会串到下一段（实测踩过）。
     *
     * 节次部分 `(\d{1,2})(?:\s*-\s*(\d{1,2}))?` 允许**单个数字**：
     * 青果导出里单节课写作 `[5]` 而不是 `[5-5]`。若强制要求起-止，
     * 这类课会被整段丢掉（实测踩过：单节课一条都解析不出来）。
     */
    private val SEGMENT = Regex(
        "((?:\\d{1,2}[${WeekRanges.RANGE_SEPARATOR_CLASS}]\\d{1,2})" +
            "(?:\\s*,\\s*\\d{1,2}[${WeekRanges.RANGE_SEPARATOR_CLASS}]\\d{1,2})*)\\s*周" +
            "\\s*(\\([^\\s()]*\\))?" +
            "\\s*([一二三四五六日])" +
            "\\s*\\[\\s*(\\d{1,2})(?:\\s*[${WeekRanges.RANGE_SEPARATOR_CLASS}]\\s*(\\d{1,2}))?\\s*\\]" +
            "\\s*([^,]*)"
    )

    /** 教室尾部的容量 `(64)`——剥离但作为提示保留在备注里 */
    private val CAPACITY = Regex("^(.*?)[\\s]*[（(]\\s*\\d+\\s*[）)]$")

    private val WEEKDAY = mapOf(
        "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6, "日" to 7,
    )

    /** 教师/学号前缀：`[200901019]王顺平` → `王顺平` */
    private val LEADING_CODE = Regex("^\\[[^\\]]*\\]\\s*")

    /**
     * 网格 → 解析报告。
     *
     * @param grid 二维表格（空串补齐，坐标 0-based）
     * @param headerRow 表头所在行；其下方为正课数据
     * @param colTimePlace 「上课时间地点」列号
     */
    fun parseGrid(
        grid: List<List<String>>,
        headerRow: Int,
        colTimePlace: Int,
        term: Term,
        periods: List<Period>,
        sheetName: String,
    ): ParseReport {
        val colCourse = grid.getOrNull(headerRow)?.indexOfFirst { it.trim() == COL_COURSE } ?: -1
        val colTeacher = grid.getOrNull(headerRow)?.indexOfFirst { it.trim() == COL_TEACHER } ?: -1

        val courses = mutableListOf<ParsedCourse>()
        val issues = mutableListOf<ParseIssue>()
        val maxPeriod = periods.maxOfOrNull { it.index } ?: 1

        for (r in headerRow + 1 until grid.size) {
            val row = grid.getOrNull(r) ?: continue
            val rowNo = r + 1
            val nameCell = row.getOrNull(colCourse)?.trim().orEmpty()
            val timePlace = row.getOrNull(colTimePlace)?.trim().orEmpty()
            if (nameCell.isEmpty() && timePlace.isEmpty()) continue

            // 第二张表（实验项目）到此为止：表头特征串或「实验项目数：N」汇总行
            if (row.any { it.contains(EXPERIMENT_HEADER_HINT) }) break
            if (nameCell.startsWith("实验项目数")) break

            // 列表布局里「课程」列是编号（16/26/32…），真名在下一行；
            // 合成单元格广播后同一行会重复出现，这里以"本行是否有时段信息"为准。
            if (timePlace.isEmpty()) {
                // 有课程名但没有时间地点：可能是分行的续行，先不报错，
                // 真正的空课程由下面的 name 判定处理。
                if (nameCell.isEmpty()) continue
                issues += ParseIssue(
                    "工作表「$sheetName」第 $rowNo 行",
                    "课程「$nameCell」没有「$COL_TIME_PLACE」信息",
                )
                continue
            }

            val teacher = row.getOrNull(colTeacher)?.trim()
                ?.replace(LEADING_CODE, "")?.ifEmpty { null }
            val cleanName = nameCell.replace(LEADING_CODE, "")

            val segments = SEGMENT.findAll(timePlace).toList()
            if (segments.isEmpty()) {
                issues += ParseIssue(
                    "工作表「$sheetName」第 $rowNo 行",
                    "「$timePlace」里认不出星期与节次",
                )
                continue
            }

            for (m in segments) {
                // 捕获组：周次 / (双)标记 / 星期 / 起 / 止（单节时为空串）/ 教室
                val (weeksRaw, parityMark, dayCn, startS, endS, roomRaw) = m.destructured
                val day = WEEKDAY[dayCn]
                val start = startS.toIntOrNull()
                // `[5]` 这种单节写法不写止节，按同一起止处理
                val end = endS.toIntOrNull() ?: start
                if (day == null || start == null || end == null) continue
                if (end < start) continue

                val at = "工作表「$sheetName」第 $rowNo 行"
                if (start < 1 || end > maxPeriod) {
                    issues += ParseIssue(at, "节次 $start-$end 超出当前学期的 1–$maxPeriod 节")
                    continue
                }

                // 单双周：由本段自己捕获的 (双) 决定。不能用「整格里有没有 (双)」，
                // 也不能看「本段之前的文本」—— 那会把上一段的单双周串过来。
                val parity = if (parityMark != null && DOUBLE_HINT.containsMatchIn(parityMark)) {
                    WeekParity.EVEN
                } else {
                    WeekParity.EVERY
                }

                val weeks = WeekRanges.clamp(weeksRaw, term.totalWeeks)
                if (weeks.isEmpty()) {
                    issues += ParseIssue(at, "周次「$weeksRaw」全部超出本学期（共 ${term.totalWeeks} 周）")
                    continue
                }

                val cap = CAPACITY.find(roomRaw.trim())
                val room = cap?.groupValues?.get(1)?.trim()?.ifEmpty { null } ?: roomRaw.trim().ifEmpty { null }

                courses += ParsedCourse(
                    name = cleanName,
                    teacher = teacher,
                    room = room,
                    dayOfWeek = day,
                    startPeriodIndex = start,
                    periodCount = end - start + 1,
                    weekRanges = weeks,
                    weekParity = parity,
                )
            }
        }
        return ParseReport("XLSX（青果列表 · $sheetName）", courses, issues)
    }

    /** 单双周标记 `(双)`（也接受全角与「双周」写法） */
    private val DOUBLE_HINT = Regex("\\(\\s*双\\s*\\)|（\\s*双\\s*）")

    /**
     * 判断网格是不是青果列表布局：表头行里能找到「上课时间地点」列。
     * 只认这一个列名 —— 青果的二维表布局没有它，两者不会互相误判。
     */
    fun detectHeader(grid: List<List<String>>): Pair<Int, Int>? {
        for ((r, row) in grid.withIndex()) {
            val idx = row.indexOfFirst { it.trim() == COL_TIME_PLACE }
            if (idx >= 0) return r to idx
        }
        return null
    }
}
