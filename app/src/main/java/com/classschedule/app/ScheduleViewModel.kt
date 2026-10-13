package com.classschedule.app

import android.app.Application
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.classschedule.app.data.AppDatabase
import com.classschedule.app.data.Appearance
import com.classschedule.app.data.BackgroundImagePort
import com.classschedule.app.data.BackgroundImageStore
import com.classschedule.app.data.Course
import com.classschedule.app.data.CourseRepository
import com.classschedule.app.data.CourseStore
import com.classschedule.app.data.Period
import com.classschedule.app.data.Term
import com.classschedule.app.data.ThemeMode
import com.classschedule.app.data.label
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
    /**
     * 背景图文件读写（v0.12.0）。生产环境传真实实现，单测传 null 或内存假实现——
     * 它要 Context 与真实文件系统，不适合在纯 JVM 单测里直接构造。
     */
    private val backgroundStore: BackgroundImagePort? = null,
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

    /**
     * 外观配置（v0.12.0）。
     *
     * 初值是 [Appearance.DEFAULT] 而不是等数据库读完再给值：
     * 冷启动时若把主题色板留空，MaterialTheme 会拿到默认色板再跳一次，
     * 视觉上是"闪一下再变"。先用默认配置立刻出界面，读完再切到用户配置，
     * 用户配置与默认相同时就没有任何跳变。
     */
    private val _appearance = MutableStateFlow(Appearance.DEFAULT)
    val appearance: StateFlow<Appearance> = _appearance.asStateFlow()

    /** 改外观前的快照，供提示条的「撤销」使用 */
    private var appearanceUndo: Appearance? = null

    /**
     * 待撤销的动作。与提示条一一对应：每条提示推入时都会重置它，
     * UI 只有在它非 [None] 时才给提示条挂「撤销」按钮，消费后即失配——
     * 杜绝"保存 B 的提示条撤销了早已删掉的 A"这类作用域错乱。
     */
    private sealed interface PendingUndo {
        data object None : PendingUndo
        data class DeleteCourse(val course: Course) : PendingUndo
        data class RestoreBackup(val data: BackupData) : PendingUndo
        /** 恢复改动前的外观配置（主题模式 + 主色） */
        data class RestoreAppearance(val before: Appearance) : PendingUndo
    }

    private var pendingUndo: PendingUndo = PendingUndo.None

    /** 当前是否有可撤销的动作（UI 据此决定提示条是否显示「撤销」） */
    fun canUndo(): Boolean = pendingUndo != PendingUndo.None

    /** 推送提示并（默认）清空上一个待撤销动作，保证提示与撤销凭据一一对应 */
    private fun pushMessage(text: String, undo: PendingUndo = PendingUndo.None) {
        pendingUndo = undo
        _message.value = text
    }

    /** JSON 确认弹窗打开期间暂存的原文（确认时再解码一次，双保险） */
    private var pendingJsonText: String? = null

    init {
        viewModelScope.launch {
            // 外观配置（v0.12.0）单独读一次：它与学期数据无关，
            // 不必等 ensureSeedData 跑完才生效
            val saved = runCatching { withContext(ioDispatcher) { store.loadAppearance() } }
                .getOrDefault(Appearance.DEFAULT)
            _appearance.value = saved
        }

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
                pushMessage("节次时间已更新为新的默认作息（8:00-9:40 起），可在「学期 → 节次时间」里修改")
            }
        }

        viewModelScope.launch {
            store.currentTerm.collect { t ->
                if (t == null) {
                    // 没有当前学期（如恢复了不含 isCurrent 的备份、或处于中间态）：
                    // 显式清空，别让 _term 带着一个已不存在的学期 id 继续服务——
                    // 那种脏值会让后续保存撞上外键约束，而用户看不到任何课。
                    if (_term.value != null) {
                        _term.value = null
                        _periods.value = emptyList()
                        _courses.value = emptyList()
                    }
                    return@collect
                }
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

        // 节次的"最大序号"，不是"条数"：deletePeriod() 只删行、不重排剩余 index，
        // 删掉中间一节后 index 会出现断层（如 {1,2,4,…,8}），用 size 收敛会把课挪位。
        val maxPeriod = _periods.value.maxOfOrNull { it.index } ?: 1
        val startP = s.startPeriod.coerceIn(1, maxPeriod)
        val endP = s.endPeriod.coerceIn(startP, maxPeriod)

        // 裁剪后才落库：全部超出本学期时不能静默存成空串（那门课会永不可见、且看不出存在），
        // 直接报错让用户改周次，比"提示已裁剪、实际存了看不见"诚实。
        val clampedWeeks = WeekRanges.clamp(s.weekExpr, term.totalWeeks)
        if (clampedWeeks.isEmpty()) {
            _edit.value = s.copy(error = "周次全部超出本学期（共 ${term.totalWeeks} 周），未保存")
            return
        }

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
            weekRanges = clampedWeeks,
            colorArgb = s.colorArgb,
        )

        viewModelScope.launch {
            // 兜底：学期被换掉/清空等极端情况下写库可能撞外键约束，
            // 不能让它冒泡成崩溃——把原因回吐到弹窗里，用户至少能改。
            val failure = runCatching { withContext(ioDispatcher) { store.saveCourse(course) } }
                .exceptionOrNull()
            if (failure != null) {
                _edit.value = s.copy(error = "保存失败：${failure.message ?: failure.javaClass.simpleName}")
                return@launch
            }
            _edit.value = CourseEditState()
            val extra = if (WeekRanges.hasOutOfRange(s.weekExpr, term.totalWeeks)) {
                "（超出本学期的周次已裁剪到 ${term.totalWeeks} 周）"
            } else ""
            pushMessage((if (s.isNew) "已添加「$name」" else "已保存「$name」") + extra)
        }
    }

    fun deleteEditing() {
        val id = _edit.value.editingId ?: return
        val target = _courses.value.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            withContext(ioDispatcher) { store.deleteCourse(target) }
            _edit.value = CourseEditState()
            pushMessage("已删除「${target.name}」", PendingUndo.DeleteCourse(target))
        }
    }

    fun undoDelete() {
        val pending = pendingUndo as? PendingUndo.DeleteCourse ?: return
        pendingUndo = PendingUndo.None
        val c = pending.course
        viewModelScope.launch {
            withContext(ioDispatcher) { store.saveCourse(c.copy(id = 0)) }
            pushMessage("已恢复「${c.name}」")
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    /** UI 层（SAF 读写失败等）复用消息栏推送提示 */
    fun showMessage(text: String) {
        pushMessage(text)
    }

    // ── 外观设置（v0.12.0） ──────────────────────────────

    /**
     * 切换主题模式。
     *
     * 记录撤销快照的时机是**第一次**改动，而不是每次都记：
     * 用户在设置页连点三下模式，提示条只该撤销这三下作为一个整体，
     * 回到进来之前的样子。
     */
    fun setThemeMode(mode: ThemeMode) = updateAppearance(
        new = { it.copy(themeMode = mode) },
        message = "已切换到${mode.label()}",
    )

    /** 选主色；传 null 表示恢复内置默认蓝 */
    fun setPrimaryColor(argb: Long?) = updateAppearance(
        new = { it.copy(primaryArgb = argb) },
        message = if (argb == null) "已恢复默认主色" else "已更换主色",
    )

    /** 恢复默认外观（跟随系统 + 默认蓝 + 无背景图），可撤销 */
    fun resetAppearance() {
        // 恢复默认意味着连背景图一起清掉：配置里不留残余路径，
        // 文件删掉后即使配置被撤销回来，路径也指向不存在的文件而回落到纯色
        val oldBackground = _appearance.value.backgroundPath
        updateAppearance(
            new = { Appearance.DEFAULT },
            message = "已恢复默认外观",
        )
        if (oldBackground != null) {
            viewModelScope.launch {
                withContext(ioDispatcher) { backgroundStore?.delete(oldBackground) }
            }
        }
    }

    private fun updateAppearance(new: (Appearance) -> Appearance, message: String) {
        val before = _appearance.value
        val after = new(before)
        if (after == before) return

        // 只有从"没动过"到"动过"的第一次才记快照
        if (appearanceUndo == null) appearanceUndo = before

        // 先改内存：重组立刻发生，用户在设置页就能看到即时效果
        _appearance.value = after
        viewModelScope.launch {
            // 落库失败只在消息栏提一句，不回滚内存里的值：
            // 回滚会让界面"闪回旧配色"，比没存上更让人困惑
            val failure = runCatching { withContext(ioDispatcher) { store.saveAppearance(after) } }
                .exceptionOrNull()
            pushMessage(
                if (failure == null) message else "$message（未保存，重启后会失效）",
                PendingUndo.RestoreAppearance(appearanceUndo ?: before),
            )
        }
    }

    /** 外观撤销：由 [PendingUndo.RestoreAppearance] 分派进来 */
    private fun undoAppearance(before: Appearance) {
        appearanceUndo = null
        _appearance.value = before
        viewModelScope.launch {
            withContext(ioDispatcher) { store.saveAppearance(before) }
            pushMessage("已恢复之前的外观")
        }
    }

    /** 调整背景蒙版浓度（0..1）；只影响背景图，不参与撤销（滑动是连续微调，逐格撤销没有意义） */
    fun setBackgroundDim(dim: Float) {
        val clamped = dim.coerceIn(0f, 1f)
        if (_appearance.value.backgroundDim == clamped) return
        _appearance.value = _appearance.value.copy(backgroundDim = clamped)
        viewModelScope.launch {
            runCatching { withContext(ioDispatcher) { store.saveAppearance(_appearance.value) } }
        }
    }

    /**
     * 导入自定义背景图（v0.12.0）。
     *
     * 文件读写放在 [backgroundStore]（可注入，便于单测替换），
     * ViewModel 只负责编排"落盘 → 写配置 → 推提示"这条顺序。
     *
     * 参数可空：[uri] 只被透传给存储层，ViewModel 自己从不读它的内容。
     * 声明可空是为了让纯 JVM 单测能驱动这段编排（android.jar 是桩实现，
     * 造不出真实 Uri）；真实存储层收到 null 会返回失败而不是崩。
     *
     * 注意：**只有落盘成功才改配置**。失败时配置里仍指向旧图，
     * 用户看到的还是原来那张，不会出现"配置说有图、实际空白"的错位。
     */
    fun importBackground(uri: Uri?) {
        val store = backgroundStore
        if (store == null) {
            pushMessage("无法导入背景图：初始化未完成")
            return
        }
        val previous = _appearance.value.backgroundPath
        viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                store.import(uri, previous)
            }
            when (result) {
                is BackgroundImageStore.ImportResult.Success -> {
                    updateAppearance(
                        new = { it.copy(backgroundPath = result.fileName) },
                        message = "已更换背景图",
                    )
                }
                is BackgroundImageStore.ImportResult.Failure -> {
                    // 失败不记撤销：配置根本没变
                    pushMessage(result.reason)
                }
            }
        }
    }

    /** 去掉背景图，回到纯色（同时删除文件） */
    fun clearBackground() {
        val store = backgroundStore
        if (store == null) {
            // 存储层不可用是更根本的问题，先报它；
            // 否则"本来就没有背景图"会把这条错误盖掉，排查时会误以为是死代码
            pushMessage("无法清除背景图：初始化未完成")
            return
        }
        val current = _appearance.value.backgroundPath ?: return
        // 先清配置再删文件：顺序反了的话，中途失败会留下"配置指向已删文件"的空引用
        updateAppearance(
            new = { it.copy(backgroundPath = null) },
            message = "已恢复纯色背景",
        )
        viewModelScope.launch {
            withContext(ioDispatcher) { store.delete(current) }
        }
    }

    /**
     * 读取背景位图供界面绘制。
     *
     * 与 [appearance] 分开返回：位图是不可变的绘制资源，而配置是会被反复修改的
     * 状态，两者混在一个 StateFlow 里会让每次改主题都重新解码一遍图片。
     */
    suspend fun loadBackground(): ImageBitmap? = withContext(ioDispatcher) {
        backgroundStore?.load(_appearance.value.backgroundPath)
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
            pushMessage("学期设置已保存")
        }
    }

    fun updatePeriodTime(period: Period, start: String, end: String) {
        // 保存路径的最后一道闸：PeriodEditor 里已有行内校验，这里兜底，
        // 防止任何调用方（含将来的批量导入）把非法时间写进库。
        // 注意不能用宽容的 minutesOf 校验——它会把 "08:70" 容错成 08:59 后放行。
        if (!GridClock.isValidRange(start, end)) {
            pushMessage("时间格式不对，或结束早于开始（如 08:00 / 09:40），未保存")
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
            pushMessage("已添加第 ${next.index} 节（${next.startTime}-${next.endTime}），可继续修改时间")
        }
    }

    /** 删除一节，同时清理占用该节的课程，避免留下看不见的脏数据 */
    fun deletePeriod(period: Period) {
        if (_periods.value.size <= 1) {
            pushMessage("至少要保留一节")
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
            pushMessage("已删除第 ${period.index} 节$extra")
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
                    pushMessage("学期信息还没准备好，稍后再试")
                    return
                }
                openCourseImport(XlsxCodec.parse(bytes, term, _periods.value))
            }
            isOleMagic(bytes) -> {
                pushMessage("暂不支持 .xls 旧格式，请用 Excel / WPS 另存为 .xlsx 后再导入")
            }
            else -> {
                val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
                if (text.isBlank()) {
                    pushMessage("无法读取该文件（可能已损坏或是空文件）")
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
                val term = _term.value ?: run {
                    pushMessage("学期信息还没准备好，稍后再试")
                    return
                }
                // ICS 的节次匹配依赖当前学期的节次快照；解析是纯函数，直接调
                openCourseImport(IcsCodec.parse(text, term, _periods.value))
            }
            FormatSniffer.FileFormat.CSV -> {
                val term = _term.value ?: run {
                    pushMessage("学期信息还没准备好，稍后再试")
                    return
                }
                // 周次/节次的规范化口径与手工录入一致：裁剪到学期总周数、收敛到节次表
                openCourseImport(
                    CsvCodec.parse(text, term.totalWeeks, _periods.value.maxOfOrNull { it.index } ?: 1),
                )
            }
        }
    }

    /** 粘贴文本导入：语法见计划文档 10.2.4 */
    fun importPasteText(text: String) {
        val term = _term.value
        if (term == null) {
            pushMessage("学期信息还没准备好，稍后再试")
            return
        }
        openCourseImport(
            PasteParser.parse(text, term.totalWeeks, _periods.value.maxOfOrNull { it.index } ?: 1),
        )
    }

    private fun openCourseImport(report: com.classschedule.app.data.backup.ParseReport) {
        if (report.courses.isEmpty() && report.issues.isEmpty()) {
            pushMessage("没有解析到任何课程，请检查文件内容")
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
            // 课程级导入没有撤销凭据：pushMessage 默认会清空上一个待撤销动作，
            // 提示条上的「撤销」也会因 canUndo()==false 而不显示（不再给空按钮）
            pushMessage(
                buildString {
                    append("已导入 ${counts.inserted} 门课程")
                    if (counts.skipped > 0) append("，跳过已存在的 ${counts.skipped} 门")
                },
            )
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
                    summary = buildString {
                        append(
                            "已恢复备份：${parsed.terms.size} 个学期 / " +
                                "${parsed.periods.size} 个节次 / ${parsed.courses.size} 门课程",
                        )
                        // 当前只有单学期 UI：多于一个学期时，除了"当前"那个其余看不到，
                        // 明确告知，免得用户以为恢复丢了数据
                        if (parsed.terms.size > 1) {
                            append("（App 目前只显示当前学期，其余学期暂不可见）")
                        }
                    }
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
            val snap = snapshot ?: return@launch
            pendingJsonText = null
            _jsonImport.value = JsonImportState()
            pushMessage(summary, PendingUndo.RestoreBackup(snap))
        }
    }

    fun dismissJsonImport() {
        pendingJsonText = null
        _jsonImport.value = JsonImportState()
    }

    /**
     * 统一的撤销入口（提示条的"撤销"按钮）。按当前待撤销动作分派：
     *   - RestoreBackup：恢复导入前的整库快照；
     *   - DeleteCourse：恢复刚删掉的那门课；
     *   - RestoreAppearance：恢复改动前的外观；
     *   - None：无操作（UI 也不会显示按钮）。
     * 凭据随消息一起被消费，所以"撤销"永远只作用于它那条提示对应的那次操作。
     */
    fun undoLast() {
        when (val pending = pendingUndo) {
            is PendingUndo.RestoreBackup -> {
                pendingUndo = PendingUndo.None
                viewModelScope.launch {
                    withContext(ioDispatcher) { store.replaceAllData(pending.data) }
                    pushMessage("已恢复到导入前的数据")
                }
            }
            is PendingUndo.RestoreAppearance -> {
                pendingUndo = PendingUndo.None
                undoAppearance(pending.before)
            }
            is PendingUndo.DeleteCourse -> undoDelete()
            PendingUndo.None -> Unit
        }
    }

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ScheduleViewModel(
                    store = CourseRepository(AppDatabaseProvider.get(app)),
                    // 背景图存 applicationContext：配置变化（旋转、深浅切换）不会丢
                    backgroundStore = BackgroundImageStore.of(app),
                ) as T
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
