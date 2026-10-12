package com.classschedule.app.data

import com.classschedule.app.data.backup.BackupData
import com.classschedule.app.data.backup.ImportCounts
import com.classschedule.app.data.backup.ImportStrategy
import com.classschedule.app.data.backup.ParsedCourse
import kotlinx.coroutines.flow.Flow

/**
 * ScheduleViewModel 依赖的数据访问契约。
 *
 * 拆出接口的动机：ViewModel 的业务逻辑（保存校验、级联删除、撤销、学期切换刷新）
 * 以前零单测——直接测要启动 Room。把依赖收窄成这个接口后，测试里用内存假实现即可，
 * 不需要 Robolectric、不碰真实数据库。
 *
 * 唯一实现是 [CourseRepository]，方法语义与它一致。
 */
interface CourseStore {

    /** 当前学期（isCurrent = 1 的那条），数据变化时自动重发 */
    val currentTerm: Flow<Term?>

    fun coursesOf(termId: Long): Flow<List<Course>>

    fun periodsOf(termId: Long): Flow<List<Period>>

    /** 首次启动准备默认学期 / 节次 / 示例课程；返回学期 id */
    suspend fun ensureSeedData(): Long

    /** 旧版本默认节次模板的一次性更换 */
    suspend fun migrateLegacyDefaultPeriods(termId: Long): Boolean

    /** 当前学期的一次性快照，供界面首帧使用 */
    suspend fun currentTermSnapshot(): Term?

    suspend fun listCourses(termId: Long): List<Course>

    suspend fun saveCourse(course: Course): Long

    suspend fun deleteCourse(course: Course)

    /** 只更新学期档案字段（名称 / 开学日 / 总周数）；isCurrent 不受此方法影响 */
    suspend fun updateTerm(term: Term)

    suspend fun updatePeriod(period: Period)

    suspend fun insertPeriod(period: Period)

    suspend fun deletePeriod(period: Period)

    /** 新增一节：接在现有节次之后，默认一小时 */
    fun nextPeriod(termId: Long, existing: List<Period>): Period

    // ── 导入（M4，计划文档第十章；导出已按用户决策移除） ──────

    /**
     * 课程级导入（ICS / CSV / 粘贴文本解析结果的落库）。
     * REPLACE_TERM 为单事务：先删后插，失败整体回滚。
     *
     * @return 实际插入条数与跳过条数（合并模式命中去重键的记为跳过）
     */
    suspend fun importCourses(
        termId: Long,
        courses: List<ParsedCourse>,
        strategy: ImportStrategy,
    ): ImportCounts

    /** JSON 整库恢复：单事务内清空三张表后按文件重建 */
    suspend fun replaceAllData(data: BackupData)

    /** 全库快照，供 JSON 导入前的撤销凭据使用 */
    suspend fun snapshotAll(): BackupData
}
