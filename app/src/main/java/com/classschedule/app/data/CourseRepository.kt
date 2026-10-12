package com.classschedule.app.data

import com.classschedule.app.util.GridClock
import com.classschedule.app.util.TermCalendar
import kotlinx.coroutines.flow.Flow

/**
 * 数据访问的唯一入口。ViewModel 只依赖它，不直接碰 DAO，
 * 好处是以后加"导入批次回滚"之类的逻辑时，改动集中在一个文件。
 */
class CourseRepository(private val db: AppDatabase) {

    private val termDao = db.termDao()
    private val periodDao = db.periodDao()
    private val courseDao = db.courseDao()
    private val settingDao = db.settingDao()

    val currentTerm: Flow<Term?> = termDao.observeCurrent()

    fun coursesOf(termId: Long): Flow<List<Course>> = courseDao.observeByTerm(termId)

    fun periodsOf(termId: Long): Flow<List<Period>> = periodDao.observeByTerm(termId)

    suspend fun termCount(): Int = termDao.count()

    /** 直接读一次当前学期快照，供界面首帧使用 */
    suspend fun currentTermSnapshot(): Term? = termDao.current()

    /**
     * 首次启动时准备一个可用的学期与节次模板。
     * 默认 8 小节（一天 4 个大节 × 2 小节，每小节 40 分钟），开学日取"本周周一"。
     */
    suspend fun ensureSeedData(): Long {
        termDao.current()?.let { return it.id }

        val today = TermCalendar.todayEpochDay()
        val thisMonday = today - (TermCalendar.dayOfWeek(today) - 1)

        val termId = termDao.insert(
            Term(
                name = "本学期",
                startEpochDay = thisMonday,
                totalWeeks = 20,
                isCurrent = true,
            ),
        )
        periodDao.insertAll(defaultPeriods(termId))
        seedDemoCourses(termId)
        return termId
    }

    /**
     * 首次启动时放几门示例课程，方便直接看到课表卡片样式（随手即可删除）。
     * 每门课占一个完整大节：periodCount=2，startPeriodIndex 取各大大节的起始小节。
     */
    private suspend fun seedDemoCourses(termId: Long) {
        val demo = listOf(
            Course(termId = termId, name = "高等数学", teacher = "张老师", room = "A101", dayOfWeek = 1, startPeriodIndex = 1, periodCount = 2, weekRanges = "1-20"),
            Course(termId = termId, name = "大学英语", teacher = "李老师", room = "B203", dayOfWeek = 1, startPeriodIndex = 3, periodCount = 2, weekRanges = "1-20"),
            Course(termId = termId, name = "线性代数", teacher = "王老师", room = "C305", dayOfWeek = 1, startPeriodIndex = 5, periodCount = 2, weekRanges = "1-20"),
            Course(termId = termId, name = "程序设计", teacher = "赵老师", room = "D401", dayOfWeek = 1, startPeriodIndex = 7, periodCount = 2, weekRanges = "1-20"),
            Course(termId = termId, name = "数据结构", teacher = "陈老师", room = "A210", dayOfWeek = 2, startPeriodIndex = 1, periodCount = 2, weekRanges = "1-20"),
            Course(termId = termId, name = "计算机网络", teacher = "刘老师", room = "E108", dayOfWeek = 2, startPeriodIndex = 5, periodCount = 2, weekRanges = "1-20"),
            Course(termId = termId, name = "园林设计", teacher = "孙老师", room = "F302", dayOfWeek = 3, startPeriodIndex = 1, periodCount = 2, weekRanges = "1-20"),
            Course(termId = termId, name = "体育", teacher = "周老师", room = "操场", dayOfWeek = 3, startPeriodIndex = 3, periodCount = 2, weekRanges = "1-20"),
            Course(termId = termId, name = "操作系统", teacher = "吴老师", room = "C401", dayOfWeek = 4, startPeriodIndex = 5, periodCount = 2, weekRanges = "1-20"),
            Course(termId = termId, name = "数据库原理", teacher = "郑老师", room = "D210", dayOfWeek = 4, startPeriodIndex = 7, periodCount = 2, weekRanges = "1-20"),
            Course(termId = termId, name = "概率论", teacher = "冯老师", room = "B110", dayOfWeek = 5, startPeriodIndex = 3, periodCount = 2, weekRanges = "1-20"),
            Course(termId = termId, name = "思想政治", teacher = "何老师", room = "J101", dayOfWeek = 5, startPeriodIndex = 7, periodCount = 2, weekRanges = "1-20"),
        )
        demo.forEach { courseDao.insert(it) }
    }

    /**
     * 默认节次时间表。
     *
     * 结构：一天 4 个大节，每个大节含 2 个小节，每小节 40 分钟，小节之间休息 20 分钟。
     * 第 1 小节 08:00-08:40 + 休息 + 第 2 小节 09:00-09:40 → 第 1 大节 09:40 结束。
     * 用户可在「学期 → 节次时间」里逐节修改、增删。
     */
    fun defaultPeriods(termId: Long): List<Period> {
        val times = listOf(
            // 第一大节
            "08:00" to "08:40",
            "09:00" to "09:40",
            // 第二大节
            "10:00" to "10:40",
            "11:00" to "11:40",
            // 第三大节
            "14:30" to "15:10",
            "15:30" to "16:10",
            // 第四大节
            "16:30" to "17:10",
            "17:30" to "18:10",
        )
        return times.mapIndexed { i, (s, e) ->
            Period(termId = termId, index = i + 1, startTime = s, endTime = e)
        }
    }

    /**
     * 把节次按"大节"分组，用于课程编辑时的"第 N 大节"快捷选择。
     * 用户脑子里想的是"周一下午第 3 大节"，而不是"第 5、6 小节"。
     *
     * 为什么按"两两配对"而不是按时间间隔切分：
     * 实测作息里**所有相邻小节的休息都是 20 分钟**（8:40→9:00、9:40→10:00、10:40→11:00 都一样），
     * 跨大节的边界没有任何更长的间隔可供识别，唯一可靠的依据就是"两个小节构成一个大节"这一结构约定。
     * 午休（11:40→14:30，170 分钟）虽然是长间隔，但它只区分上下午，不区分大节。
     *
     * @param periodsPerBlock 每个大节包含几个小节，默认 2
     * @return 每个大节包含的节次下标列表（下标从 1 开始）
     */
    companion object {
        const val DEFAULT_PERIODS_PER_BLOCK = 2

        fun groupIntoBlocks(
            periods: List<Period>,
            periodsPerBlock: Int = DEFAULT_PERIODS_PER_BLOCK,
        ): List<List<Int>> {
            if (periods.isEmpty()) return emptyList()
            val size = periodsPerBlock.coerceAtLeast(1)
            val indexes = periods.sortedBy { it.index }.map { it.index }
            return indexes.chunked(size)
        }
    }

    /** 新增一节：接在最后一节之后，默认一小时 */
    fun nextPeriod(termId: Long, existing: List<Period>): Period {
        val last = existing.maxByOrNull { it.index }
        val startMinutes = last?.let { GridClock.minutesOf(it.endTime) + 20 } ?: (8 * 60)
        val endMinutes = startMinutes + 60
        return Period(
            termId = termId,
            index = (last?.index ?: 0) + 1,
            startTime = GridClock.formatMinutes(startMinutes),
            endTime = GridClock.formatMinutes(endMinutes),
        )
    }

    suspend fun insertPeriod(period: Period) = periodDao.insertAll(listOf(period))

    suspend fun deletePeriod(period: Period) = periodDao.delete(period)

    suspend fun saveCourse(course: Course): Long =
        if (course.id == 0L) courseDao.insert(course) else {
            courseDao.update(course)
            course.id
        }

    suspend fun deleteCourse(course: Course) = courseDao.delete(course)

    suspend fun updateTerm(term: Term) = termDao.update(term)

    suspend fun updatePeriod(period: Period) = periodDao.update(period)

    /**
     * 一次性修正旧版本留下的默认节次。
     *
     * 历史上出现过两代默认模板，升级上来时都该换成当前的 8 小节作息：
     *   - 0.1.0：12 小节、45 分钟一节（首节 08:00-08:45）
     *   - 0.2.0：4 大节、整段 100 分钟（首节 08:00-09:40）
     *   - 当前 ：8 小节、40 分钟一节（首节 08:00-08:40）
     *
     * 判定用的是"节次数量 + 首节起止时间"这一特征组合，不会误伤用户自己调过的时间表。
     * 键值表里的标记保证只跑一次。
     *
     * @return 是否做了替换
     */
    suspend fun migrateLegacyDefaultPeriods(termId: Long): Boolean {
        if (settingDao.get(AppDatabase.KEY_DEFAULT_PERIODS_MIGRATED) == "true") return false

        val current = periodDao.listByTerm(termId)
        val first = current.firstOrNull()
        val isLegacyDefault = when {
            current.size == 12 && first?.startTime == "08:00" && first.endTime == "08:45" -> true
            current.size == 4 && first?.startTime == "08:00" && first.endTime == "09:40" -> true
            else -> false
        }

        if (isLegacyDefault) {
            current.forEach { periodDao.delete(it) }
            periodDao.insertAll(defaultPeriods(termId))
        }
        // 无论是否替换都打标记：用户自定义过的时间表同样不该被以后的版本覆盖
        settingDao.put(AppSetting(AppDatabase.KEY_DEFAULT_PERIODS_MIGRATED, "true"))
        return isLegacyDefault
    }

    suspend fun listCourses(termId: Long): List<Course> = courseDao.listByTerm(termId)
}
