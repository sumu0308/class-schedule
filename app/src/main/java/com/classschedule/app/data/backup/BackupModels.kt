package com.classschedule.app.data.backup

import com.classschedule.app.data.Course
import com.classschedule.app.data.Course.Companion.SOURCE_FILE
import com.classschedule.app.data.Period
import com.classschedule.app.data.Term
import com.classschedule.app.data.WeekParity

/**
 * 一条解析出来的课程：字段与 [Course] 一致，但还没有落到任何学期
 * （id 由数据库分配，termId 由写入层决定）。
 */
data class ParsedCourse(
    val name: String,
    val teacher: String? = null,
    val room: String? = null,
    val dayOfWeek: Int,
    val startPeriodIndex: Int,
    val periodCount: Int,
    val weekRanges: String,
    val weekParity: WeekParity = WeekParity.EVERY,
    val colorArgb: Long? = null,
    val note: String? = null,
    /** 导入进来的课程统一标记为文件来源，便于将来源清理 */
    val source: String = SOURCE_FILE,
) {
    fun toCourse(termId: Long): Course = Course(
        termId = termId,
        name = name,
        teacher = teacher,
        room = room,
        dayOfWeek = dayOfWeek,
        startPeriodIndex = startPeriodIndex,
        periodCount = periodCount,
        weekParity = weekParity,
        weekRanges = weekRanges,
        colorArgb = colorArgb,
        note = note,
        source = source,
    )
}

/** 解析过程中发现的一个问题：哪一行、为什么 */
data class ParseIssue(val location: String, val reason: String)

/** 解析结果：合法课程 + 问题清单（坏行不拖死好行） */
data class ParseReport(
    /** 格式名，用于界面展示，如 "ICS" / "CSV" / "粘贴文本" */
    val format: String,
    val courses: List<ParsedCourse>,
    val issues: List<ParseIssue>,
)

/** 课程级导入的写入策略（见计划文档 10.3） */
enum class ImportStrategy { APPEND, REPLACE_TERM, MERGE }

/** 导入结果统计 */
data class ImportCounts(val inserted: Int, val skipped: Int)

/** 整库快照：JSON 备份的内容，也是导入撤销的凭据 */
data class BackupData(
    val terms: List<Term>,
    val periods: List<Period>,
    val courses: List<Course>,
)

/** JSON 备份文件的预览：各表条数，或结构错误原因 */
data class JsonPreview(
    val terms: Int,
    val periods: Int,
    val courses: Int,
    val error: String? = null,
) {
    val ok: Boolean get() = error == null
}
