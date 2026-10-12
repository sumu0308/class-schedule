package com.classschedule.app

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.classschedule.app.data.AppDatabase
import com.classschedule.app.data.Course
import com.classschedule.app.data.CourseRepository
import com.classschedule.app.data.CourseStore
import com.classschedule.app.data.Period
import com.classschedule.app.data.Term
import com.classschedule.app.data.WeekParity
import com.classschedule.app.data.backup.BackupData
import com.classschedule.app.data.backup.BackupJson
import com.classschedule.app.data.backup.CsvCodec
import com.classschedule.app.data.backup.FormatSniffer
import com.classschedule.app.data.backup.IcsCodec
import com.classschedule.app.data.backup.ImportPlanner
import com.classschedule.app.data.backup.ImportStrategy
import com.classschedule.app.data.backup.JsonException
import com.classschedule.app.data.backup.ParsedCourse
import com.classschedule.app.data.backup.PasteParser
import com.classschedule.app.data.backup.ParseIssue
import com.classschedule.app.data.backup.XlsxCodec
import com.classschedule.app.util.GridClock
import com.classschedule.app.util.TermCalendar
import com.classschedule.app.util.WeekRanges
import kotlinx.coroutines.CoroutineDispatcher
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

/**
 * 课程级导入（ICS / CSV / 粘贴文本）的预览弹窗状态。
 *
 * plannedInsert / plannedSkip 随策略实时重算（合并去重要对照现有课程），
 * 用户在确认前就能看到"到底会写进去几条"。
 */
data class CourseImportState(
    val visible: Boolean = false,
    /** 格式名，直接展示给用户："ICS" / "CSV" / "粘贴文本" */
    val format: String = "",
    val courses: List<ParsedCourse> = emptyList(),
    /** 未识别行清单（行号 + 原因），坏行不拖死好行 */
    val issues: List<ParseIssue> = emptyList(),
    val strategy: ImportStrategy = ImportStrategy.APPEND,
    val plannedInsert: Int = 0,
    val plannedSkip: Int = 0,
) {
    /** 一条都解析不出来时不允许确认（避免空导入把用户搞糊涂） */
    val canConfirm: Boolean get() = courses.isNotEmpty()
}

/** JSON 整库导入的二次确认弹窗状态 */
data class JsonImportState(
    val visible: Boolean = false,
    val terms: Int = 0,
    val periods: Int = 0,
    val courses: Int = 0,
    val error: String? = null,
) {
    val canConfirm: Boolean get() = error == null && terms > 0
}

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleViewModel(
    private val store: CourseStore,
    /**
     * IO 调度器可注入：生产环境用默认的 Dispatchers.IO，
     * 单测里换成测试调度器，避免 withContext 的真实线程切换把断言变成 race。
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

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

    private val _courseImport = MutableStateFlow(CourseImportState())
    val courseImport: StateFlow<CourseImportState> = _courseImport.asStateFlow()

    private val _jsonImport = MutableStateFlow(JsonImportState())
    val jsonImport: StateFlow<JsonImportState> = _jsonImport.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 刚被删除的课程，供"撤销"使用 */
    private var lastDeleted: Course? = null

    /** JSON 整库导入前的数据快照，供"撤销恢复"使用（10.2.1） */
    private var importUndoSnapshot: BackupData? = null

    /** JSON 确认弹窗打开期间暂存的原文（确认时再解码一次，双保险） */
    private var pendingJsonText: String? = null

    init {
        viewModelScope.launch {
            // 首次启动准备默认学期与节次；已有数据则原样返回
            val termId = withContext(ioDispatcher) { store.ensureSeedData() }

            // 从 0.1.0 升级上来时，把旧的 12 小节默认模板换成新作息，免去用户手改 12 行
            val migrated = withContext(ioDispatcher) { store.migrateLegacyDefaultPeriods(termId) }

            // 先直接取一次快照，界面不用等 Flow 的第一次发射
            val snapshot = withContext(ioDispatcher) { store.currentTermSnapshot() }
            if (snapshot != null) {
                _term.value = snapshot
                _week.value = currentWeekOf(snapshot)
            }
            if (migrated) {
                _message.value = "节次时间已更新为新的默认作息（8:00-9:40 起），可在「学期 → 节次时间」里修改"
            }
        }

        viewModelScope.launch {
            store.currentTerm.collect { t ->
                if (t == null) return@collect
                val isDifferentTerm = _term.value?.id != t.id
                _term.value = t
                if (isDifferentTerm) _week.value = currentWeekOf(t)
            }
        }

        // 学期变化时用 flatMapLatest 切换到新学期的节次 Flow（旧 Flow 会自动取消）。
        // 不能用嵌套 collect：Room 的 Flow 永不完成，内层会一直挂起、外层收不到后续学期更新。
        viewModelScope.launch {
            store.currentTerm
                .flatMapLatest { t -> if (t == null) emptyFlow() else store.periodsOf(t.id) }
                .collect { _periods.value = it }
        }

        // 课程列表同理跟随学期切换（曾经绑死在种子学期的 id 上，多学时会串数据）。
        // 当前 App 只有一个学期，行为与旧实现一致；等以后支持多学期，这里就是正确的。
        viewModelScope.launch {
            store.currentTerm
                .flatMapLatest { t -> if (t == null) emptyFlow() else store.coursesOf(t.id) }
                .collect { _courses.value = it }
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
            withContext(ioDispatcher) { store.saveCourse(course) }
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
            withContext(ioDispatcher) { store.deleteCourse(target) }
            lastDeleted = target
            _edit.value = CourseEditState()
            _message.value = "已删除「${target.name}」"
        }
    }

    fun undoDelete() {
        val c = lastDeleted ?: return
        lastDeleted = null
        viewModelScope.launch {
            withContext(ioDispatcher) { store.saveCourse(c.copy(id = 0)) }
            _message.value = "已恢复「${c.name}」"
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    /** UI 层（SAF 读写失败等）复用消息栏推送提示 */
    fun showMessage(text: String) {
        _message.value = text
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
            withContext(ioDispatcher) {
                store.updateTerm(
                    t.copy(name = s.name.trim(), startEpochDay = s.startEpochDay, totalWeeks = weeks),
                )
            }
            _termEdit.value = TermEditState()
            goToWeek(currentWeek())
            _message.value = "学期设置已保存"
        }
    }

    fun updatePeriodTime(period: Period, start: String, end: String) {
        // 保存路径的最后一道闸：PeriodEditor 里已有行内校验，这里兜底，
        // 防止任何调用方（含将来的批量导入）把非法时间写进库。
        // 注意不能用宽容的 minutesOf 校验——它会把 "08:70" 容错成 08:59 后放行。
        if (!GridClock.isValidRange(start, end)) {
            _message.value = "时间格式不对，或结束早于开始（如 08:00 / 09:40），未保存"
            return
        }
        viewModelScope.launch {
            withContext(ioDispatcher) {
                store.updatePeriod(period.copy(startTime = start, endTime = end))
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
        val next = store.nextPeriod(term.id, existing)
        viewModelScope.launch {
            withContext(ioDispatcher) { store.insertPeriod(next) }
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
            var affectedCount = 0
            withContext(ioDispatcher) {
                val affected = store.listCourses(period.termId).filter { c ->
                    period.index >= c.startPeriodIndex &&
                        period.index < c.startPeriodIndex + c.periodCount
                }
                affected.forEach { store.deleteCourse(it) }
                affectedCount = affected.size
                store.deletePeriod(period)
            }
            // 文案用删除前捕获的 period.index 和 IO 块里数出来的受影响课程数，
            // 不读 _periods：Room 的 Flow 还没重发，那是删除前的旧快照，读了会显示错
            val extra = if (affectedCount > 0) "，同时清理了占用该节的 $affectedCount 门课程" else ""
            _message.value = "已删除第 ${period.index} 节$extra"
        }
    }

    // ── 数据导入（M4，计划文档第十章；导出已按用户决策移除） ──

    /**
     * 文件导入的统一入口（0.11.0 起按字节读入）：
     *   - zip 魔数（PK）→ XLSX 课表（10.2.5）
     *   - OLE 魔数（旧 .xls）→ 明确提示另存为 .xlsx
     *   - 其余按 UTF-8 解码（剥 BOM）后走内容嗅探：
     *     JSON 走整库恢复的二次确认，ICS / CSV 走课程级导入预览。
     */
    fun importFileBytes(bytes: ByteArray) {
        when {
            isZipMagic(bytes) -> {
                val term = _term.value
                if (term == null) {
                    _message.value = "学期信息还没准备好，稍后再试"
                    return
                }
                openCourseImport(XlsxCodec.parse(bytes, term, _periods.value))
            }
            isOleMagic(bytes) -> {
                _message.value = "暂不支持 .xls 旧格式，请用 Excel / WPS 另存为 .xlsx 后再导入"
            }
            else -> {
                val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
                if (text.isBlank()) {
                    _message.value = "无法读取该文件（可能已损坏或是空文件）"
                } else {
                    importFileText(text)
                }
            }
        }
    }

    /** xlsx 本质是 zip：前 4 字节 PK\x03\x04（也容忍空归档 PK\x05\x06） */
    private fun isZipMagic(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
            (bytes[2] == 3.toByte() && bytes[3] == 4.toByte() || bytes[2] == 5.toByte() && bytes[3] == 6.toByte())

    /** 旧 .xls 是 OLE 复合文档：D0 CF 11 E0 */
    private fun isOleMagic(bytes: ByteArray): Boolean =
        bytes.size >= 8 && bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() &&
            bytes[2] == 0x11.toByte() && bytes[3] == 0xE0.toByte()

    /** 文本入口：内容嗅探格式（不信任扩展名），JSON 走整库恢复，ICS / CSV 走课程级预览 */
    fun importFileText(text: String) {
        when (FormatSniffer.sniff(text)) {
            FormatSniffer.FileFormat.JSON -> previewJsonImport(text)
            FormatSniffer.FileFormat.ICS -> {
                val term = _term.value
                if (term == null) {
                    _message.value = "学期信息还没准备好，稍后再试"
                    return
                }
                // ICS 的节次匹配依赖当前学期的节次快照；解析是纯函数，直接调
                openCourseImport(IcsCodec.parse(text, term, _periods.value))
            }
            FormatSniffer.FileFormat.CSV -> openCourseImport(CsvCodec.parse(text))
        }
    }

    /** 粘贴文本导入：语法见计划文档 10.2.4 */
    fun importPasteText(text: String) {
        val term = _term.value
        if (term == null) {
            _message.value = "学期信息还没准备好，稍后再试"
            return
        }
        openCourseImport(
            PasteParser.parse(text, term.totalWeeks, _periods.value.size),
        )
    }

    private fun openCourseImport(report: com.classschedule.app.data.backup.ParseReport) {
        if (report.courses.isEmpty() && report.issues.isEmpty()) {
            _message.value = "没有解析到任何课程，请检查文件内容"
            return
        }
        val plan = ImportPlanner.plan(_courses.value, report.courses, ImportStrategy.APPEND)
        _courseImport.value = CourseImportState(
            visible = true,
            format = report.format,
            courses = report.courses,
            issues = report.issues,
            strategy = ImportStrategy.APPEND,
            plannedInsert = plan.toInsert.size,
            plannedSkip = plan.skipped,
        )
    }

    /** 切换策略时按新策略重算预览条数（合并模式要对照现有课程去重） */
    fun setImportStrategy(strategy: ImportStrategy) {
        val s = _courseImport.value
        val plan = ImportPlanner.plan(_courses.value, s.courses, strategy)
        _courseImport.value = s.copy(
            strategy = strategy,
            plannedInsert = plan.toInsert.size,
            plannedSkip = plan.skipped,
        )
    }

    fun confirmCourseImport() {
        val s = _courseImport.value
        val term = _term.value ?: return
        if (!s.canConfirm) return
        viewModelScope.launch {
            val counts = withContext(ioDispatcher) {
                store.importCourses(term.id, s.courses, s.strategy)
            }
            _courseImport.value = CourseImportState()
            // 批量写入之后，单课撤销的上下文已失效，清掉避免误恢复
            lastDeleted = null
            _message.value = buildString {
                append("已导入 ${counts.inserted} 门课程")
                if (counts.skipped > 0) append("，跳过已存在的 ${counts.skipped} 门")
            }
        }
    }

    fun dismissCourseImport() {
        _courseImport.value = CourseImportState()
    }

    /** JSON 备份预览：只解码不写入，展示各表条数或结构错误原因 */
    fun previewJsonImport(text: String) {
        val p = BackupJson.inspect(text)
        pendingJsonText = if (p.ok) text else null
        _jsonImport.value = JsonImportState(
            visible = true,
            terms = p.terms,
            periods = p.periods,
            courses = p.courses,
            error = p.error,
        )
    }

    /**
     * 确认 JSON 整库恢复（10.2.1）：
     * 先快照当前全库数据（撤销凭据）→ 单事务清空重建 → 提示可撤销。
     */
    fun confirmJsonImport() {
        val text = pendingJsonText ?: return
        viewModelScope.launch {
            var error: String? = null
            var snapshot: BackupData? = null
            var summary = ""
            withContext(ioDispatcher) {
                try {
                    val parsed = BackupJson.decode(text)
                    snapshot = store.snapshotAll()
                    store.replaceAllData(parsed)
                    // 文案在 IO 块里就地生成：此时 parsed 必然非空，不需要 !! 兜底
                    summary = "已恢复备份：${parsed.terms.size} 个学期 / " +
                        "${parsed.periods.size} 个节次 / ${parsed.courses.size} 门课程"
                } catch (e: JsonException) {
                    error = "备份文件结构有问题：${e.message}"
                } catch (e: IllegalArgumentException) {
                    error = "备份文件内容不合法：${e.message}"
                } catch (e: Exception) {
                    // 兜底：手改过的备份可能触发数据库约束（如重复 id）等意外，
                    // 单事务已回滚，这里只负责把原因告诉用户，不崩协程
                    error = "恢复失败：${e.message ?: e.javaClass.simpleName}"
                }
            }
            if (error != null) {
                _jsonImport.value = _jsonImport.value.copy(error = error)
                return@launch
            }
            importUndoSnapshot = snapshot
            lastDeleted = null
            pendingJsonText = null
            _jsonImport.value = JsonImportState()
            _message.value = summary
        }
    }

    fun dismissJsonImport() {
        pendingJsonText = null
        _jsonImport.value = JsonImportState()
    }

    /**
     * 统一的撤销入口（Snackbar 的"撤销"按钮）：
     * 有导入快照就恢复整库，否则按单课删除撤销。
     * 两者互斥——导入成功时会清掉 lastDeleted，删除时不会有导入快照。
     */
    fun undoLast() {
        val snapshot = importUndoSnapshot
        if (snapshot != null) {
            importUndoSnapshot = null
            viewModelScope.launch {
                withContext(ioDispatcher) { store.replaceAllData(snapshot) }
                _message.value = "已恢复到导入前的数据"
            }
        } else {
            undoDelete()
        }
    }

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ScheduleViewModel(CourseRepository(AppDatabaseProvider.get(app))) as T
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
