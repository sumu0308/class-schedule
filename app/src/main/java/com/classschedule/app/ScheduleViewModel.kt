package com.classschedule.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.classschedule.app.data.AppDatabase
import com.classschedule.app.data.Course
import com.classschedule.app.data.CourseRepository
import com.classschedule.app.data.Period
import com.classschedule.app.data.Term
import com.classschedule.app.data.WeekParity
import com.classschedule.app.util.TermCalendar
import com.classschedule.app.util.WeekRanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

/** 课程编辑弹窗的状态。editingId 为 null 表示新增。 */
data class CourseEditState(
    val visible: Boolean = false,
    val editingId: Long? = null,
    val name: String = "",
    val teacher: String = "",
    val room: String = "",
    val dayOfWeek: Int = 1,
    val startPeriod: Int = 1,
    val endPeriod: Int = 1,
    val weekExpr: String = "1-20",
    val parity: WeekParity = WeekParity.EVERY,
    val colorArgb: Long? = null,
    val error: String? = null,
) {
    val isNew: Boolean get() = editingId == null
}

/** 学期设置弹窗的状态 */
data class TermEditState(
    val visible: Boolean = false,
    val name: String = "",
    val startEpochDay: Long = 0,
    val totalWeeks: Int = 20,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleViewModel(
    app: Application,
    private val repo: CourseRepository,
) : AndroidViewModel(app) {

    private val _term = MutableStateFlow<Term?>(null)
    val term: StateFlow<Term?> = _term.asStateFlow()

    private val _courses = MutableStateFlow<List<Course>>(emptyList())
    val courses: StateFlow<List<Course>> = _courses.asStateFlow()

    private val _periods = MutableStateFlow<List<Period>>(emptyList())
    val periods: StateFlow<List<Period>> = _periods.asStateFlow()

    private val _week = MutableStateFlow(1)
    val week: StateFlow<Int> = _week.asStateFlow()

    private val _showWeekend = MutableStateFlow(false)
    val showWeekend: StateFlow<Boolean> = _showWeekend.asStateFlow()

    private val _edit = MutableStateFlow(CourseEditState())
    val edit: StateFlow<CourseEditState> = _edit.asStateFlow()

    private val _termEdit = MutableStateFlow(TermEditState())
    val termEdit: StateFlow<TermEditState> = _termEdit.asStateFlow()

    private val _periodEditorVisible = MutableStateFlow(false)
    val periodEditorVisible: StateFlow<Boolean> = _periodEditorVisible.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 刚被删除的课程，供"撤销"使用 */
    private var lastDeleted: Course? = null

    init {
        viewModelScope.launch {
            // 首次启动准备默认学期与节次；已有数据则原样返回
            val termId = withContext(Dispatchers.IO) { repo.ensureSeedData() }

            // 从 0.1.0 升级上来时，把旧的 12 小节默认模板换成新作息，免去用户手改 12 行
            val migrated = withContext(Dispatchers.IO) { repo.migrateLegacyDefaultPeriods(termId) }

            // 先直接取一次快照，界面不用等 Flow 的第一次发射
            val snapshot = withContext(Dispatchers.IO) { repo.currentTermSnapshot() }
            if (snapshot != null) {
                _term.value = snapshot
                _week.value = currentWeekOf(snapshot)
            }
            if (migrated) {
                _message.value = "节次时间已更新为新的默认作息（8:00-9:40 起），可在「学期 → 节次时间」里修改"
            }

            // 课程与节次跟随学期变化
            repo.coursesOf(termId).collect { _courses.value = it }
        }

        viewModelScope.launch {
            repo.currentTerm.collect { t ->
                if (t == null) return@collect
                val isDifferentTerm = _term.value?.id != t.id
                _term.value = t
                if (isDifferentTerm) _week.value = currentWeekOf(t)
            }
        }

        // 学期变化时用 flatMapLatest 切换到新学期的节次 Flow（旧 Flow 会自动取消）。
        // 不能用嵌套 collect：Room 的 Flow 永不完成，内层会一直挂起、外层收不到后续学期更新。
        viewModelScope.launch {
            repo.currentTerm
                .flatMapLatest { t -> if (t == null) emptyFlow() else repo.periodsOf(t.id) }
                .collect { _periods.value = it }
        }
    }

    // ── 周次导航 ────────────────────────────────────────────────

    private fun currentWeekOf(t: Term): Int {
        val w = TermCalendar.weekOf(TermCalendar.todayEpochDay(), t.startEpochDay)
        return w.coerceIn(1, t.totalWeeks.coerceAtLeast(1))
    }

    /** 今天是本学期第几周（越界时收敛到 1..总周数） */
    fun currentWeek(): Int = _term.value?.let { currentWeekOf(it) } ?: 1

    /** 判断"当前查看的周"是不是就是本周 */
    fun isViewingCurrentWeek(): Boolean = _week.value == currentWeek()

    fun dayCount(): Int = if (_showWeekend.value) 7 else 5

    fun toggleWeekend() {
        _showWeekend.value = !_showWeekend.value
    }

    fun goToWeek(w: Int) {
        val total = _term.value?.totalWeeks ?: 20
        _week.value = w.coerceIn(1, total)
    }

    fun stepWeek(delta: Int) = goToWeek(_week.value + delta)

    fun goToCurrentWeek() = goToWeek(currentWeek())

    // ── 课程编辑 ────────────────────────────────────────────────

    fun startAdd(day: Int, periodIndex: Int) {
        val total = _term.value?.totalWeeks ?: 20
        _edit.value = CourseEditState(
            visible = true,
            dayOfWeek = day.coerceIn(1, 7),
            startPeriod = periodIndex,
            endPeriod = periodIndex,
            weekExpr = "1-$total",
        )
    }

    fun startEdit(course: Course) {
        _edit.value = CourseEditState(
            visible = true,
            editingId = course.id,
            name = course.name,
            teacher = course.teacher.orEmpty(),
            room = course.room.orEmpty(),
            dayOfWeek = course.dayOfWeek,
            startPeriod = course.startPeriodIndex,
            endPeriod = course.startPeriodIndex + course.periodCount - 1,
            weekExpr = course.weekRanges,
            parity = course.weekParity,
            colorArgb = course.colorArgb,
        )
    }

    fun updateEdit(transform: (CourseEditState) -> CourseEditState) {
        _edit.value = transform(_edit.value).copy(error = null)
    }

    fun dismissEdit() {
        _edit.value = CourseEditState()
    }

    fun saveEdit() {
        val s = _edit.value
        val term = _term.value ?: return
        val name = s.name.trim()

        if (name.isEmpty()) {
            _edit.value = s.copy(error = "课程名不能为空")
            return
        }
        if (WeekRanges.isBlankOrInvalid(s.weekExpr)) {
            _edit.value = s.copy(error = "周次格式不对，正确写法如：1-16 或 1-8,10-16")
            return
        }
        if (s.endPeriod < s.startPeriod) {
            _edit.value = s.copy(error = "结束节次不能早于开始节次")
            return
        }

        val maxPeriod = _periods.value.size.coerceAtLeast(1)
        val startP = s.startPeriod.coerceIn(1, maxPeriod)
        val endP = s.endPeriod.coerceIn(startP, maxPeriod)

        val course = Course(
            id = s.editingId ?: 0L,
            termId = term.id,
            name = name,
            teacher = s.teacher.trim().ifEmpty { null },
            room = s.room.trim().ifEmpty { null },
            dayOfWeek = s.dayOfWeek.coerceIn(1, 7),
            startPeriodIndex = startP,
            periodCount = endP - startP + 1,
            weekParity = s.parity,
            weekRanges = WeekRanges.clamp(s.weekExpr, term.totalWeeks),
            colorArgb = s.colorArgb,
        )

        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.saveCourse(course) }
            _edit.value = CourseEditState()
            val extra = if (WeekRanges.hasOutOfRange(s.weekExpr, term.totalWeeks)) {
                "（超出本学期的周次已裁剪到 ${term.totalWeeks} 周）"
            } else ""
            _message.value = (if (s.isNew) "已添加「$name」" else "已保存「$name」") + extra
        }
    }

    fun deleteEditing() {
        val id = _edit.value.editingId ?: return
        val target = _courses.value.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.deleteCourse(target) }
            lastDeleted = target
            _edit.value = CourseEditState()
            _message.value = "已删除「${target.name}」"
        }
    }

    fun undoDelete() {
        val c = lastDeleted ?: return
        lastDeleted = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.saveCourse(c.copy(id = 0)) }
            _message.value = "已恢复「${c.name}」"
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    // ── 学期设置 ────────────────────────────────────────────────

    fun openTermEdit() {
        val t = _term.value ?: return
        _termEdit.value = TermEditState(
            visible = true,
            name = t.name,
            startEpochDay = t.startEpochDay,
            totalWeeks = t.totalWeeks,
        )
    }

    fun updateTermEdit(transform: (TermEditState) -> TermEditState) {
        _termEdit.value = transform(_termEdit.value)
    }

    fun dismissTermEdit() {
        _termEdit.value = TermEditState()
    }

    fun saveTermEdit() {
        val s = _termEdit.value
        val t = _term.value ?: return
        if (s.name.isBlank()) return
        val weeks = s.totalWeeks.coerceIn(1, 30)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.updateTerm(
                    t.copy(name = s.name.trim(), startEpochDay = s.startEpochDay, totalWeeks = weeks),
                )
            }
            _termEdit.value = TermEditState()
            goToWeek(currentWeek())
            _message.value = "学期设置已保存"
        }
    }

    fun updatePeriodTime(period: Period, start: String, end: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.updatePeriod(period.copy(startTime = start, endTime = end))
            }
        }
    }

    // ── 节次设置 ────────────────────────────────────────────────

    fun openPeriodEditor() {
        _periodEditorVisible.value = true
    }

    fun closePeriodEditor() {
        _periodEditorVisible.value = false
    }

    /** 新增一节：接在现有节次之后，默认时长一小时 */
    fun addPeriod() {
        val term = _term.value ?: return
        val existing = _periods.value
        val next = repo.nextPeriod(term.id, existing)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.insertPeriod(next) }
            _message.value = "已添加第 ${next.index} 节（${next.startTime}-${next.endTime}），可继续修改时间"
        }
    }

    /** 删除一节，同时清理占用该节的课程，避免留下看不见的脏数据 */
    fun deletePeriod(period: Period) {
        if (_periods.value.size <= 1) {
            _message.value = "至少要保留一节"
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val affected = repo.listCourses(period.termId).filter { c ->
                    period.index >= c.startPeriodIndex &&
                        period.index < c.startPeriodIndex + c.periodCount
                }
                affected.forEach { repo.deleteCourse(it) }
                repo.deletePeriod(period)
            }
            _message.value = if (_periods.value.size <= 1) {
                "已删除该节"
            } else {
                "已删除第 ${period.index} 节"
            }
        }
    }

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ScheduleViewModel(app, CourseRepository(AppDatabaseProvider.get(app))) as T
            }
        }
    }
}

/** 数据库单例 */
object AppDatabaseProvider {
    @Volatile
    private var instance: AppDatabase? = null

    fun get(app: Application): AppDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(app, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
            .also { instance = it }
    }
}
