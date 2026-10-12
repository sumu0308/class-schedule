package com.classschedule.app.data.backup

import com.classschedule.app.data.Course

/**
 * 课程级导入的写入策略规划（计划文档 10.3）。
 *
 * 纯函数：只决定"哪些行插入、哪些行跳过"，不碰数据库——
 * 这样策略语义可以用普通单测钉死，ViewModel 也能在确认前
 * 拿它算预览条数。
 */
object ImportPlanner {

    data class Plan(val toInsert: List<ParsedCourse>, val skipped: Int)

    /** 去重键：课程名 + 星期 + 起始节次（同名同天同节次视为同一门课） */
    fun keyOf(course: Course): Triple<String, Int, Int> =
        Triple(course.name, course.dayOfWeek, course.startPeriodIndex)

    fun keyOf(course: ParsedCourse): Triple<String, Int, Int> =
        Triple(course.name, course.dayOfWeek, course.startPeriodIndex)

    fun plan(existing: List<Course>, parsed: List<ParsedCourse>, strategy: ImportStrategy): Plan =
        when (strategy) {
            ImportStrategy.APPEND, ImportStrategy.REPLACE_TERM -> Plan(parsed, 0)
            ImportStrategy.MERGE -> {
                val existingKeys = existing.map { keyOf(it) }.toSet()
                val fresh = parsed.filterNot { keyOf(it) in existingKeys }
                Plan(fresh, parsed.size - fresh.size)
            }
        }
}
