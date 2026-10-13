package com.classschedule.app

import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import com.classschedule.app.data.Appearance
import com.classschedule.app.data.BackgroundImagePort
import com.classschedule.app.data.BackgroundImageStore
import com.classschedule.app.data.Course
import com.classschedule.app.data.CourseRepository
import com.classschedule.app.data.CourseStore
import com.classschedule.app.data.Period
import com.classschedule.app.data.Term
import com.classschedule.app.data.ThemeMode
import com.classschedule.app.data.backup.BackupData
import com.classschedule.app.data.backup.Fixtures
import com.classschedule.app.data.backup.ImportCounts
import com.classschedule.app.data.backup.ImportPlanner
import com.classschedule.app.data.backup.ImportStrategy
import com.classschedule.app.data.backup.ParsedCourse
import com.classschedule.app.util.TermCalendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * ScheduleViewModel 的单元测试。
 *
 * 覆盖四类业务：
 *  1. 节次 / 课程跟随学期刷新（P0-2 flatMapLatest 修复的回归测试）；
 *  2. 课程保存校验（空名、周次格式、节次倒置）；
 *  3. 删除节次的级联清理、提示文案、撤销删除；
 *  4. 节次时间保存校验（非法输入不落库）。
 *
 * 数据层用内存假实现 [FakeCourseStore]，不启动 Room、不需要 Robolectric；
 * 调度器全部换成测试调度器，断言不依赖真实线程时序。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = UnconfinedTestDispatcher(scheduler)

    @Before
    fun setUp() {
        // viewModelScope 依赖 Dispatchers.Main；换成测试调度器后才能同步驱动
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun advance() = scheduler.advanceUntilIdle()

    private val termA = Term(id = 1, name = "2026 秋", startEpochDay = 20_454, totalWeeks = 20)
    private val termB = Term(id = 2, name = "2027 春", startEpochDay = 20_594, totalWeeks = 22)

    /** 开学日为周一（2026-09-07 当周周一）的学期，供 ICS 日期换算测试用 */
    private val mondayTerm: Term = run {
        var d = epochDayOf(2026, 9, 7)
        d -= (TermCalendar.dayOfWeek(d) - 1)
        Term(id = 3, name = "2026 秋", startEpochDay = d, totalWeeks = 20)
    }

    private fun epochDayOf(y: Int, m: Int, d: Int): Long {
        val cal = Calendar.getInstance().apply { clear(); set(y, m - 1, d) }
        val offset = cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET)
        return Math.floorDiv(cal.timeInMillis + offset, 86_400_000L)
    }

    /** epochDay → ICS 用的本地浮动日期时间 "YYYYMMDDTHHMMSS" */
    private fun icsDate(epochDay: Long, hhmmss: String): String {
        val cal = Calendar.getInstance().apply {
            clear()
            timeInMillis = epochDay * 86_400_000L
            val offset = get(Calendar.ZONE_OFFSET) + get(Calendar.DST_OFFSET)
            timeInMillis = epochDay * 86_400_000L - offset
        }
        return String.format(
            "%04d%02d%02dT%s",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH), hhmmss,
        )
    }

    /** 与 CourseRepository.defaultPeriods 一致的 8 小节默认作息 */
    private fun defaultPeriods(termId: Long): List<Period> = listOf(
        Period(id = 1, termId = termId, index = 1, startTime = "08:00", endTime = "08:40"),
        Period(id = 2, termId = termId, index = 2, startTime = "09:00", endTime = "09:40"),
        Period(id = 3, termId = termId, index = 3, startTime = "10:00", endTime = "10:40"),
        Period(id = 4, termId = termId, index = 4, startTime = "11:00", endTime = "11:40"),
        Period(id = 5, termId = termId, index = 5, startTime = "14:30", endTime = "15:10"),
        Period(id = 6, termId = termId, index = 6, startTime = "15:30", endTime = "16:10"),
        Period(id = 7, termId = termId, index = 7, startTime = "16:30", endTime = "17:10"),
        Period(id = 8, termId = termId, index = 8, startTime = "17:30", endTime = "18:10"),
    )

    /** 造一个已经装着指定学期的 store；term 传 null 模拟空数据库 */
    private fun store(term: Term? = termA): FakeCourseStore =
        FakeCourseStore().apply {
            currentTerm.value = term
            snapshot = term
            if (term != null) {
                terms += term
                setPeriods(term.id, defaultPeriods(term.id))
            }
        }

    // ── 1. 学期切换刷新（P0-2 回归） ────────────────────────

    @Test
    fun `首次加载后节次与课程来自当前学期`() {
        val s = store()
        val course = Course(id = 10, termId = 1, name = "高等数学", dayOfWeek = 1, startPeriodIndex = 1, periodCount = 2, weekRanges = "1-20")
        s.setCourses(1, listOf(course))
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        assertEquals(termA, vm.term.value)
        assertEquals(8, vm.periods.value.size)
        assertTrue(vm.periods.value.all { it.termId == termA.id })
        assertEquals(listOf(course), vm.courses.value)
    }

    @Test
    fun `切换学期后节次一起刷新`() {
        // 回归测试：旧实现在 currentTerm.collect 里嵌套 periodsOf(t.id).collect，
        // Room 的 Flow 永不完成，内层一直挂起，_periods 永远绑在第一个学期上。
        val s = store()
        s.setPeriods(termB.id, defaultPeriods(termB.id).take(4))
        val vm = ScheduleViewModel(s, dispatcher)
        advance()
        assertEquals(8, vm.periods.value.size)

        s.currentTerm.value = termB
        advance()

        assertEquals(termB, vm.term.value)
        assertEquals(4, vm.periods.value.size)
        assertTrue(vm.periods.value.all { it.termId == termB.id })
    }

    @Test
    fun `课程列表也跟随学期切换`() {
        // 曾经的隐患：课程流绑死在种子学期的 id 上，多学时会串数据。
        // 单学期下行为与旧实现一致，这条测试把正确性钉住。
        val s = store()
        val courseA = Course(id = 10, termId = 1, name = "高等数学", dayOfWeek = 1, startPeriodIndex = 1, periodCount = 2, weekRanges = "1-20")
        val courseB = Course(id = 20, termId = 2, name = "线性代数", dayOfWeek = 2, startPeriodIndex = 3, periodCount = 2, weekRanges = "1-22")
        s.setCourses(1, listOf(courseA))
        s.setCourses(2, listOf(courseB))
        val vm = ScheduleViewModel(s, dispatcher)
        advance()
        assertEquals(listOf(courseA), vm.courses.value)

        s.currentTerm.value = termB
        advance()

        assertEquals(listOf(courseB), vm.courses.value)
    }

    @Test
    fun `没有学期时状态为空且不崩`() {
        val s = store(term = null)
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        assertNull(vm.term.value)
        assertTrue(vm.periods.value.isEmpty())
        assertTrue(vm.courses.value.isEmpty())
        assertEquals(1, vm.currentWeek())
    }

    // ── 2. 课程保存校验 ────────────────────────────────────

    @Test
    fun `保存课程_名称为空时报错且不落库`() {
        val s = store()
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        vm.startAdd(day = 1, periodIndex = 1)
        vm.updateEdit { it.copy(name = "   ") }
        vm.saveEdit()
        advance()

        assertEquals("课程名不能为空", vm.edit.value.error)
        assertTrue(s.savedCourses.isEmpty())
    }

    @Test
    fun `保存课程_周次格式非法时报错且不落库`() {
        val s = store()
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        vm.startAdd(day = 1, periodIndex = 1)
        vm.updateEdit { it.copy(name = "高等数学", weekExpr = "abc") }
        vm.saveEdit()
        advance()

        assertEquals("周次格式不对，正确写法如：1-16 或 1-8,10-16", vm.edit.value.error)
        assertTrue(s.savedCourses.isEmpty())
    }

    @Test
    fun `保存课程_结束节次早于开始时报错且不落库`() {
        val s = store()
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        vm.startAdd(day = 1, periodIndex = 1)
        vm.updateEdit { it.copy(name = "高等数学", startPeriod = 5, endPeriod = 3) }
        vm.saveEdit()
        advance()

        assertEquals("结束节次不能早于开始节次", vm.edit.value.error)
        assertTrue(s.savedCourses.isEmpty())
    }

    @Test
    fun `保存课程_合法时落库_节次收敛到已有节数_周次裁剪到总周数`() {
        val s = store()
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        vm.startAdd(day = 1, periodIndex = 3)
        vm.updateEdit {
            it.copy(
                name = "高等数学",
                teacher = "张老师",
                room = "A101",
                startPeriod = 3,
                endPeriod = 4,
                weekExpr = "1-30",
            )
        }
        vm.saveEdit()
        advance()

        val saved = s.savedCourses.single()
        assertEquals("高等数学", saved.name)
        assertEquals("张老师", saved.teacher)
        assertEquals("A101", saved.room)
        assertEquals(1, saved.dayOfWeek)
        assertEquals(3, saved.startPeriodIndex)
        assertEquals(2, saved.periodCount)
        assertEquals(termA.id, saved.termId)
        assertEquals("1-20", saved.weekRanges) // 超出 20 周的部分被裁剪
        assertNull(vm.edit.value.error)
        assertFalse(vm.edit.value.visible) // 保存后弹窗关闭
        assertEquals("已添加「高等数学」（超出本学期的周次已裁剪到 20 周）", vm.message.value)
    }

    @Test
    fun `节次序号有断层_保存课程不挪位`() {
        // 删掉中间一节后 index 出现断层：{1,2,4,5,6,7,8}，size=7 而最大序号=8。
        // 旧实现按 size 收敛，一门在第 8 节的课"什么都不改点保存"会被挪到第 7 节。
        val s = store()
        s.setPeriods(
            1,
            listOf(1, 2, 4, 5, 6, 7, 8).map { idx ->
                Period(id = idx.toLong(), termId = 1, index = idx, startTime = "08:00", endTime = "08:40")
            },
        )
        val course = Course(
            id = 10, termId = 1, name = "高等数学", dayOfWeek = 1,
            startPeriodIndex = 8, periodCount = 1, weekRanges = "1-20",
        )
        s.setCourses(1, listOf(course))
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        vm.startEdit(course)
        vm.saveEdit() // 什么都不改，直接保存
        advance()

        val saved = s.savedCourses.single()
        assertEquals(8, saved.startPeriodIndex)
        assertEquals(1, saved.periodCount)
    }

    @Test
    fun `周次全部超出本学期_报错且不落库`() {
        val shortTerm = Term(id = 1, name = "短学期", startEpochDay = 20_454, totalWeeks = 4)
        val s = FakeCourseStore().apply {
            currentTerm.value = shortTerm
            snapshot = shortTerm
            terms += shortTerm
            setPeriods(shortTerm.id, defaultPeriods(shortTerm.id))
        }
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        vm.startAdd(day = 1, periodIndex = 1)
        vm.updateEdit { it.copy(name = "高等数学", weekExpr = "5-10") }
        vm.saveEdit()
        advance()

        assertTrue(vm.edit.value.error!!.contains("超出本学期"))
        assertTrue(s.savedCourses.isEmpty())
    }

    @Test
    fun `保存新课程后_撤销不再指向之前的删除`() {
        // 先删 A（产生撤销凭据），再保存 B：B 的提示条不该还能撤销早已完成的删除
        val s = store()
        val a = Course(
            id = 10, termId = 1, name = "A", dayOfWeek = 1,
            startPeriodIndex = 1, periodCount = 2, weekRanges = "1-20",
        )
        s.setCourses(1, listOf(a))
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        vm.startEdit(a)
        vm.deleteEditing()
        advance()
        assertTrue(vm.canUndo())

        vm.startAdd(day = 2, periodIndex = 1)
        vm.updateEdit { it.copy(name = "B") }
        vm.saveEdit()
        advance()
        assertFalse(vm.canUndo()) // 保存类消息不提供撤销

        vm.undoLast() // 无操作，不会把 A 复活
        advance()
        assertEquals(1, s.savedCourses.size) // 只有保存 B 那一次
        assertEquals("B", s.savedCourses.single().name)
    }

    @Test
    fun `课程级导入成功的提示条不再挂撤销`() {
        val s = store()
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        vm.importPasteText("高等数学 - - 周一 1-2")
        advance()
        vm.confirmCourseImport()
        advance()

        assertEquals("已导入 1 门课程", vm.message.value)
        assertFalse(vm.canUndo()) // 没有撤销凭据 → UI 不显示「撤销」
    }

    // ── 3. 删除节次：级联清理、文案、撤销 ──────────────────

    @Test
    fun `删除节次_同时清理占用该节的课程`() {
        val s = store()
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        val victim = Course(id = 10, termId = 1, name = "大学英语", dayOfWeek = 1, startPeriodIndex = 2, periodCount = 1, weekRanges = "1-20")
        val other = Course(id = 11, termId = 1, name = "体育", dayOfWeek = 3, startPeriodIndex = 5, periodCount = 2, weekRanges = "1-20")
        s.listCoursesResult = listOf(victim, other)

        vm.deletePeriod(vm.periods.value[1]) // 第 2 节
        advance()

        assertEquals(listOf(10L), s.deletedCourses.map { it.id })
        assertEquals(1, s.deletedPeriods.size)
        assertEquals(2, s.deletedPeriods.single().index)
        assertEquals("已删除第 2 节，同时清理了占用该节的 1 门课程", vm.message.value)
    }

    @Test
    fun `删除节次_没有课程占用时提示不带清理信息`() {
        val s = store()
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        vm.deletePeriod(vm.periods.value[1])
        advance()

        assertTrue(s.deletedCourses.isEmpty())
        assertEquals("已删除第 2 节", vm.message.value)
    }

    @Test
    fun `删除节次_只剩一节时拒绝删除`() {
        val s = store()
        s.setPeriods(1, listOf(Period(id = 1, termId = 1, index = 1, startTime = "08:00", endTime = "08:40")))
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        vm.deletePeriod(vm.periods.value.single())
        advance()

        assertTrue(s.deletedPeriods.isEmpty())
        assertTrue(s.deletedCourses.isEmpty())
        assertEquals("至少要保留一节", vm.message.value)
    }

    @Test
    fun `删除课程后可以撤销_撤销后以新 id 重新插入`() {
        val s = store()
        val course = Course(
            id = 10, termId = 1, name = "高等数学", teacher = "张老师", room = "A101",
            dayOfWeek = 1, startPeriodIndex = 1, periodCount = 2, weekRanges = "1-20",
        )
        s.setCourses(1, listOf(course))
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        vm.startEdit(course)
        vm.deleteEditing()
        advance()
        assertEquals(listOf(10L), s.deletedCourses.map { it.id })
        assertEquals("已删除「高等数学」", vm.message.value)

        vm.undoDelete()
        advance()
        val restored = s.savedCourses.single()
        assertEquals(0L, restored.id) // 重新插入，id 归零交给数据库分配
        assertEquals("高等数学", restored.name)
        assertEquals("张老师", restored.teacher)
        assertEquals("已恢复「高等数学」", vm.message.value)
    }

    @Test
    fun `没有删除过时撤销无操作`() {
        val s = store()
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        vm.undoDelete()
        advance()

        assertTrue(s.savedCourses.isEmpty())
        assertNull(vm.message.value)
    }

    // ── 4. 节次时间保存校验 ────────────────────────────────

    @Test
    fun `修改节次时间_非法输入一律不落库并提示`() {
        val s = store()
        val vm = ScheduleViewModel(s, dispatcher)
        advance()
        val p = vm.periods.value[0]

        vm.updatePeriodTime(p, "25:00", "26:00") // 小时越界（宽容解析会容成 23:00）
        advance()
        vm.updatePeriodTime(p, "08:70", "09:40") // 分钟越界（宽容解析会容成 08:59）
        advance()
        vm.updatePeriodTime(p, "09:40", "08:00") // 结束早于开始
        advance()
        vm.updatePeriodTime(p, "8:00", "9:40") // 未补零
        advance()
        vm.updatePeriodTime(p, "abc", "09:40") // 根本不是时间
        advance()

        assertTrue("任何非法输入都不该落库", s.updatedPeriods.isEmpty())
        assertEquals("时间格式不对，或结束早于开始（如 08:00 / 09:40），未保存", vm.message.value)
    }

    @Test
    fun `修改节次时间_合法输入静默落库`() {
        val s = store()
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        vm.updatePeriodTime(vm.periods.value[0], "08:30", "09:10")
        advance()

        val updated = s.updatedPeriods.single()
        assertEquals("08:30", updated.startTime)
        assertEquals("09:10", updated.endTime)
        assertNull(vm.message.value) // 与原行为一致：合法保存不弹提示
    }

    @Test
    fun `新增节次_接在最后一节之后`() {
        val s = store()
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        vm.addPeriod()
        advance()

        val inserted = s.insertedPeriods.single()
        assertEquals(9, inserted.index)
        assertEquals("18:30", inserted.startTime)
        assertEquals("19:30", inserted.endTime)
        assertEquals("已添加第 9 节（18:30-19:30），可继续修改时间", vm.message.value)
    }

    @Test
    fun `新增节次_末节拖到很晚时也不会绕回凌晨`() {
        // 末节 23:50 结束，+20 分钟休息会跨过午夜；旧实现取模后变成 00:10 起，
        // 存进去"结束早于开始"，自己都过不了校验
        val s = store()
        s.setPeriods(1, listOf(Period(id = 1, termId = 1, index = 1, startTime = "22:00", endTime = "23:50")))
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        vm.addPeriod()
        advance()

        val inserted = s.insertedPeriods.single()
        assertEquals(2, inserted.index)
        assertEquals("22:00", inserted.startTime) // 收敛到 22:00
        assertEquals("23:00", inserted.endTime)
    }

    // ── 5. 周次导航 ───────────────────────────────────────

    @Test
    fun `周次导航收敛到 1 到总周数`() {
        val s = store()
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        vm.goToWeek(999)
        assertEquals(20, vm.week.value)
        vm.goToWeek(-5)
        assertEquals(1, vm.week.value)
        vm.goToWeek(7)
        assertEquals(7, vm.week.value)
    }

    // ── 6. 数据导入（M4） ─────────────────────────────────

    @Test
    fun `粘贴文本导入_预览展示条数与坏行_确认后落库`() {
        val s = store()
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        vm.importPasteText("高等数学 张老师 A101 周一 1-2 1-16周\n这行没有星期\n")
        advance()

        val preview = vm.courseImport.value
        assertTrue(preview.visible)
        assertEquals("粘贴文本", preview.format)
        assertEquals(1, preview.courses.size)
        assertEquals(1, preview.issues.size)
        assertEquals(1, preview.plannedInsert)
        assertEquals(0, preview.plannedSkip)
        assertTrue(preview.canConfirm)

        vm.confirmCourseImport()
        advance()

        assertFalse(vm.courseImport.value.visible)
        assertEquals(1, s.importCalls.single().second.size)
        assertEquals(ImportStrategy.APPEND, s.importCalls.single().third)
        assertEquals(1, s.currentCourses(1).size)
        assertEquals("高等数学", s.currentCourses(1).single().name)
        assertEquals("已导入 1 门课程", vm.message.value)
    }

    @Test
    fun `合并策略_跳过已存在课程_预览条数随策略重算`() {
        val s = store()
        val existing = Course(
            id = 10, termId = 1, name = "高等数学", dayOfWeek = 1,
            startPeriodIndex = 1, periodCount = 2, weekRanges = "1-20",
        )
        s.setCourses(1, listOf(existing))
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        vm.importPasteText("高等数学 张老师 A101 周一 1-2\n大学英语 李老师 B203 周三 3-4")
        advance()
        assertEquals(2, vm.courseImport.value.plannedInsert)
        assertEquals(0, vm.courseImport.value.plannedSkip)

        vm.setImportStrategy(ImportStrategy.MERGE)
        advance()
        assertEquals(1, vm.courseImport.value.plannedInsert)
        assertEquals(1, vm.courseImport.value.plannedSkip)

        vm.confirmCourseImport()
        advance()
        assertEquals(2, s.currentCourses(1).size) // 1 门旧的 + 1 门新的
        assertEquals("已导入 1 门课程，跳过已存在的 1 门", vm.message.value)
    }

    @Test
    fun `覆盖策略_先删后插`() {
        val s = store()
        s.setCourses(
            1,
            listOf(
                Course(
                    id = 10, termId = 1, name = "旧课", dayOfWeek = 1,
                    startPeriodIndex = 1, periodCount = 2, weekRanges = "1-20",
                ),
            ),
        )
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        vm.importPasteText("新课 - - 周二 1-2")
        advance()
        vm.setImportStrategy(ImportStrategy.REPLACE_TERM)
        vm.confirmCourseImport()
        advance()

        val remaining = s.currentCourses(1)
        assertEquals(1, remaining.size)
        assertEquals("新课", remaining.single().name)
    }

    @Test
    fun `文件导入按内容嗅探格式_ICS对上学期的节次`() {
        val s = store(mondayTerm)
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        // 第 1 周周一 08:00-09:40 的课（第 1-2 小节）
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" +
            "BEGIN:VEVENT\r\nSUMMARY:高等数学\r\n" +
            "DTSTART:${icsDate(mondayTerm.startEpochDay, "080000")}\r\n" +
            "DTEND:${icsDate(mondayTerm.startEpochDay, "094000")}\r\n" +
            "RRULE:FREQ=WEEKLY;COUNT=16\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        vm.importFileText(ics)
        advance()

        assertEquals("ICS", vm.courseImport.value.format)
        assertEquals(1, vm.courseImport.value.courses.size)
        val c = vm.courseImport.value.courses.single()
        assertEquals(1, c.dayOfWeek)
        assertEquals(1, c.startPeriodIndex)
        assertEquals(2, c.periodCount)
        assertEquals("1-16", c.weekRanges)
    }

    @Test
    fun `文件导入按内容嗅探格式_JSON走整库恢复流程`() {
        val s = store()
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        vm.importFileText(sampleBackupJson())
        advance()

        // JSON 不进课程预览，而是打开整库确认弹窗
        assertFalse(vm.courseImport.value.visible)
        assertTrue(vm.jsonImport.value.visible)
        assertEquals(2, vm.jsonImport.value.terms)
        assertTrue(vm.jsonImport.value.canConfirm)
    }

    @Test
    fun `JSON导入_确认后整库替换并记住撤销凭据`() {
        val s = store()
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        vm.previewJsonImport(sampleBackupJson())
        advance()
        vm.confirmJsonImport()
        advance()

        assertFalse(vm.jsonImport.value.visible)
        assertEquals(1, s.replacedData.size)
        // 当前学期换成备份里 isCurrent = true 的那个
        assertEquals(2L, vm.term.value?.id)
        assertEquals(
            "已恢复备份：2 个学期 / 16 个节次 / 2 门课程（App 目前只显示当前学期，其余学期暂不可见）",
            vm.message.value,
        )

        // 消息栏点"撤销"：恢复导入前的快照
        vm.undoLast()
        advance()
        assertEquals(2, s.replacedData.size)
        assertEquals(1L, vm.term.value?.id)
        assertEquals("已恢复到导入前的数据", vm.message.value)
    }

    @Test
    fun `JSON结构错误_给原因且确认不写库`() {
        val s = store()
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        vm.previewJsonImport("这不是备份文件")
        advance()

        assertTrue(vm.jsonImport.value.visible)
        assertFalse(vm.jsonImport.value.canConfirm)
        assertTrue(vm.jsonImport.value.error?.isNotBlank() == true)

        vm.confirmJsonImport() // 没有可确认的文本，直接无害返回
        advance()
        assertTrue(s.replacedData.isEmpty())
    }

    @Test
    fun `导入成功后单课撤销上下文失效`() {
        val s = store()
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        vm.importPasteText("高等数学 - - 周一 1-2")
        advance()
        vm.confirmCourseImport()
        advance()
        assertEquals("已导入 1 门课程", vm.message.value)
        vm.consumeMessage()

        vm.undoLast() // 没有删除也没有导入快照：无操作
        advance()
        assertNull(vm.message.value)
        assertTrue(s.savedCourses.isEmpty()) // 不会误恢复任何东西
    }

    @Test
    fun `文件导入_xlsx字节走课程预览流程`() {
        val s = store(mondayTerm)
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        // zip 魔数 → XLSX：矩阵课表两门课
        val xlsx = Fixtures.buildXlsx(
            Fixtures.SheetSpec(
                "课表",
                listOf(
                    listOf("节次\\星期", "星期一", "星期二", "星期三"),
                    listOf("1-2", "高等数学\n张老师\nA101\n1-16周", "", ""),
                    listOf("3-4", "", "大学英语\n李老师\nB203", ""),
                ),
            ),
        )
        vm.importFileBytes(xlsx)
        advance()

        assertEquals("XLSX（课表）", vm.courseImport.value.format)
        assertTrue(vm.courseImport.value.visible)
        assertEquals(2, vm.courseImport.value.courses.size)
        assertEquals(2, vm.courseImport.value.plannedInsert)
    }

    @Test
    fun `旧xls格式_提示另存为xlsx`() {
        val s = store(mondayTerm)
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        // OLE 魔数（D0 CF 11 E0…）：不猜、不解析，直接给可操作提示
        vm.importFileBytes(
            byteArrayOf(
                0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(),
                0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte(), 0, 0,
            ),
        )
        advance()

        assertTrue(vm.message.value!!.contains("另存为"))
        assertFalse(vm.courseImport.value.visible)
        assertFalse(vm.jsonImport.value.visible)
    }

    @Test
    fun `空文件_提示无法读取`() {
        val s = store(mondayTerm)
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        vm.importFileBytes(ByteArray(0))
        advance()

        assertEquals("无法读取该文件（可能已损坏或是空文件）", vm.message.value)
    }

    // ── 7. 外观设置（v0.12.0） ──────────────────────────

    @Test
    fun `启动时读取已保存的外观配置`() {
        val s = store()
        s.appearance = Appearance(ThemeMode.DARK, 0xFFD9534FL)
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        assertEquals(ThemeMode.DARK, vm.appearance.value.themeMode)
        assertEquals(0xFFD9534FL, vm.appearance.value.primaryArgb)
    }

    @Test
    fun `未保存过外观时用默认值`() {
        val s = store() // FakeCourseStore 初始就是 Appearance.DEFAULT
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        assertEquals(Appearance.DEFAULT, vm.appearance.value)
    }

    @Test
    fun `切换主题模式_内存与库同时更新并提示`() {
        val s = store()
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        vm.setThemeMode(ThemeMode.DARK)
        advance()

        // 内存先变（界面即时生效），库里也跟着变（重启后还在）
        assertEquals(ThemeMode.DARK, vm.appearance.value.themeMode)
        assertEquals(ThemeMode.DARK, s.appearance.themeMode)
        assertEquals("已切换到深色", vm.message.value)
    }

    @Test
    fun `更换主色与恢复默认主色`() {
        val s = store()
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        vm.setPrimaryColor(0xFF2E9E5BL)
        advance()
        assertEquals(0xFF2E9E5BL, vm.appearance.value.primaryArgb)
        assertEquals("已更换主色", vm.message.value)

        vm.setPrimaryColor(null)
        advance()
        assertNull(vm.appearance.value.primaryArgb)
        assertNull(s.appearance.primaryArgb)
        assertEquals("已恢复默认主色", vm.message.value)
    }

    @Test
    fun `恢复默认外观回到出厂状态`() {
        val s = store()
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        vm.setThemeMode(ThemeMode.DARK)
        advance()
        vm.setPrimaryColor(0xFF2E9E5BL)
        advance()

        vm.resetAppearance()
        advance()

        assertEquals(Appearance.DEFAULT, vm.appearance.value)
        assertEquals(Appearance.DEFAULT, s.appearance)
        assertEquals("已恢复默认外观", vm.message.value)
    }

    @Test
    fun `外观改动后可以撤销_回到进入设置前的状态`() {
        val s = store()
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        vm.setThemeMode(ThemeMode.DARK)
        advance()
        vm.setPrimaryColor(0xFFD9534FL)
        advance()

        // 连改两下只算一个整体：撤销应回到两项都没改之前，
        // 而不是退回"只改了主题模式"的中间态
        vm.undoLast()
        advance()

        assertEquals(Appearance.DEFAULT, vm.appearance.value)
        assertEquals(Appearance.DEFAULT, s.appearance)
        assertEquals("已恢复之前的外观", vm.message.value)
    }

    @Test
    fun `撤销外观后快照被消费_再撤销不会二次回退`() {
        val s = store()
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        vm.setThemeMode(ThemeMode.LIGHT)
        advance()
        vm.undoLast()
        advance()
        assertEquals(Appearance.DEFAULT, vm.appearance.value)

        vm.consumeMessage()
        vm.undoLast() // 没有待撤销动作，应无害
        advance()

        assertEquals(Appearance.DEFAULT, vm.appearance.value)
    }

    @Test
    fun `重复选同一个值不产生提示也不覆盖已有撤销凭据`() {
        val s = store()
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        vm.setThemeMode(ThemeMode.DARK)
        advance()
        vm.consumeMessage()

        vm.setThemeMode(ThemeMode.DARK) // 无变化
        advance()

        assertNull("无变化不该弹提示", vm.message.value)
        assertTrue("第一次改动留下的撤销凭据不该被无变化的点击覆盖", vm.canUndo())
        // 且撤销仍然回到最初的样子，而不是被这次无变化的点击改写
        vm.undoLast()
        advance()
        assertEquals(Appearance.DEFAULT, vm.appearance.value)
    }

    @Test
    fun `课程删除后外观改动的撤销作用域互不干扰`() {
        // 与 0.11.2 修的"撤销作用域错乱"同类问题：外观撤销凭据不能
        // 被后续的删除操作消费掉，反之亦然
        val s = store()
        val course = Course(
            id = 10, termId = 1, name = "高等数学", dayOfWeek = 1,
            startPeriodIndex = 1, periodCount = 2, weekRanges = "1-20",
        )
        s.setCourses(1, listOf(course))
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        vm.startEdit(course)
        vm.deleteEditing()
        advance()
        assertTrue(vm.canUndo())

        // 外观改动会把撤销凭据换成外观的（每条提示只对应一个动作）
        vm.setThemeMode(ThemeMode.DARK)
        advance()

        vm.undoLast()
        advance()
        // 撤销的是外观，不是课程删除
        assertEquals(Appearance.DEFAULT, vm.appearance.value)
        assertTrue("课程删除不该被撤销掉", s.deletedCourses.contains(course))
    }

    // ── 8. 自定义背景图（v0.12.0） ────────────────────────

    @Test
    fun `导入背景图_落盘成功才写配置并可撤销`() {
        val s = store()
        val bg = FakeBackgroundStore(nextImport = BackgroundImageStore.ImportResult.Success("bg_1.jpg"))
        val vm = ScheduleViewModel(s, dispatcher, backgroundStore = bg)
        advance()

        vm.importBackground(fakeUri())
        advance()

        assertEquals("bg_1.jpg", vm.appearance.value.backgroundPath)
        assertEquals("bg_1.jpg", s.appearance.backgroundPath) // 也已落库
        assertEquals("已更换背景图", vm.message.value)
        assertTrue(vm.canUndo())

        vm.undoLast()
        advance()
        assertNull("撤销后应回到无背景图", vm.appearance.value.backgroundPath)
    }

    @Test
    fun `导入背景图_失败时配置不变也不挂撤销`() {
        val s = store()
        val bg = FakeBackgroundStore(
            nextImport = BackgroundImageStore.ImportResult.Failure("无法读取这张图片，换一张试试"),
        )
        val vm = ScheduleViewModel(s, dispatcher, backgroundStore = bg)
        advance()

        vm.importBackground(fakeUri())
        advance()

        // 关键：失败时不能把配置指向一个不存在的文件
        assertNull(vm.appearance.value.backgroundPath)
        assertNull(s.appearance.backgroundPath)
        assertEquals("无法读取这张图片，换一张试试", vm.message.value)
        assertFalse("失败不是一次可撤销的改动", vm.canUndo())
    }

    @Test
    fun `导入背景图_把当前背景作为旧图传给存储层`() {
        // "先落新图、成功后再删旧图"的顺序由 [BackgroundImageStore] 保证；
        // ViewModel 这一层要保证的是**把旧文件名正确传下去**，
        // 否则存储层无从知道该删哪个文件，旧图会一直堆在私有目录里。
        val s = store()
        s.appearance = Appearance(backgroundPath = "bg_old.jpg")
        val bg = FakeBackgroundStore(nextImport = BackgroundImageStore.ImportResult.Success("bg_new.jpg"))
        val vm = ScheduleViewModel(s, dispatcher, backgroundStore = bg)
        advance()

        vm.importBackground(fakeUri())
        advance()

        assertEquals(listOf("bg_old.jpg"), bg.importCalls.toList())
        assertEquals("bg_new.jpg", vm.appearance.value.backgroundPath)
    }

    @Test
    fun `清除背景图_清配置并删文件`() {
        val s = store()
        s.appearance = Appearance(backgroundPath = "bg_1.jpg")
        val bg = FakeBackgroundStore()
        val vm = ScheduleViewModel(s, dispatcher, backgroundStore = bg)
        advance()

        vm.clearBackground()
        advance()

        assertNull(vm.appearance.value.backgroundPath)
        assertEquals(listOf("bg_1.jpg"), bg.deletedFiles.toList())
        assertEquals("已恢复纯色背景", vm.message.value)
    }

    @Test
    fun `清除背景图_本来就没有图时不做事`() {
        val s = store()
        val bg = FakeBackgroundStore()
        val vm = ScheduleViewModel(s, dispatcher, backgroundStore = bg)
        advance()

        vm.clearBackground()
        advance()

        assertTrue("不该有删除动作", bg.deletedFiles.isEmpty())
        assertNull(vm.message.value)
    }

    @Test
    fun `恢复默认外观_连带删掉背景图文件`() {
        val s = store()
        s.appearance = Appearance(
            themeMode = ThemeMode.DARK,
            primaryArgb = 0xFF2F6FEDL,
            backgroundPath = "bg_1.jpg",
        )
        val bg = FakeBackgroundStore()
        val vm = ScheduleViewModel(s, dispatcher, backgroundStore = bg)
        advance()

        vm.resetAppearance()
        advance()

        assertEquals(Appearance.DEFAULT, vm.appearance.value)
        assertEquals("恢复默认应顺带删掉背景图文件", listOf("bg_1.jpg"), bg.deletedFiles.toList())
    }

    @Test
    fun `背景明暗调整收敛到合法区间且不入撤销`() {
        val s = store()
        s.appearance = Appearance(backgroundPath = "bg_1.jpg")
        val vm = ScheduleViewModel(s, dispatcher)
        advance()

        vm.setBackgroundDim(0.6f)
        advance()
        assertEquals(0.6f, vm.appearance.value.backgroundDim, 0.001f)
        assertEquals(0.6f, s.appearance.backgroundDim, 0.001f)
        // 连续微调不挂撤销：滑块每动一下都弹一次撤销提示会很吵
        assertFalse(vm.canUndo())

        vm.setBackgroundDim(5f) // 越界
        advance()
        assertEquals(1f, vm.appearance.value.backgroundDim, 0.001f)

        vm.setBackgroundDim(-1f)
        advance()
        assertEquals(0f, vm.appearance.value.backgroundDim, 0.001f)
    }

    @Test
    fun `背景位图读取失败时回落纯色而不是崩`() = runTest(dispatcher) {
        val s = store()
        s.appearance = Appearance(backgroundPath = "bg_1.jpg")
        val bg = FakeBackgroundStore() // load 返回 null（文件不在）
        val vm = ScheduleViewModel(s, dispatcher, backgroundStore = bg)
        advance()

        assertNull(vm.loadBackground())
    }

    @Test
    fun `没有注入背景图存储时给出明确提示而不是静默失败`() {
        val s = store()
        val vm = ScheduleViewModel(s, dispatcher, backgroundStore = null)
        advance()

        vm.importBackground(fakeUri())
        advance()
        assertEquals("无法导入背景图：初始化未完成", vm.message.value)

        // 清除路径同样要给出提示——即便当前根本没有背景图，
        // 否则这条分支会被"没有图就返回"盖掉，异常时无从察觉
        vm.consumeMessage()
        vm.clearBackground()
        advance()
        assertEquals("无法清除背景图：初始化未完成", vm.message.value)
    }

    /**
     * 背景图导入用的 Uri 占位。
     *
     * ViewModel 只把它透传给 [BackgroundImagePort]，从不读内容；
     * 真实现（BackgroundImageStore）才会去 `openInputStream`，那部分不在单测覆盖内。
     *
     * 纯 JVM 单测里 android.jar 是桩实现，`Uri.parse` / `Uri.EMPTY` 都不可用
     * （分别抛 not-mocked 与 null）。ViewModel 与存储层都把 Uri 当不透明句柄透传，
     * 所以这里传 null 同样能覆盖"落盘成功才写配置"等编排规则。
     */
    private fun fakeUri(): Uri? = null

    /**
     * 内存版 [BackgroundImagePort]：记录调用与结果，
     * 让 ViewModel 的编排逻辑（成功才写配置、失败不动配置、清图要删文件）可被验证。
     */
    private class FakeBackgroundStore(
        private val nextImport: BackgroundImageStore.ImportResult? =
            BackgroundImageStore.ImportResult.Success("bg_new.jpg"),
    ) : BackgroundImagePort {
        val deletedFiles = mutableListOf<String>()
        /** 每次 import 收到的"旧文件名"，用于验证 ViewModel 有把当前背景传下去 */
        val importCalls = mutableListOf<String?>()
        var importResult: BackgroundImageStore.ImportResult? = nextImport

        override suspend fun import(uri: Uri?, previousFileName: String?): BackgroundImageStore.ImportResult {
            importCalls += previousFileName
            return importResult ?: BackgroundImageStore.ImportResult.Failure("没有预置结果")
        }

        override suspend fun load(fileName: String?): ImageBitmap? = null

        override suspend fun delete(fileName: String?) {
            fileName?.let { deletedFiles += it }
        }
    }

    /**
     * 手工构造的整库备份 JSON（10.5：导出已移除，测试输入改为手工构造，
     * 避免"导出/导入对称 bug 互相抵消"的盲区）。
     * 2 个学期 / 16 个节次 / 2 门课程，id=2 的是当前学期；
     * 课程刻意缺省 weekParity / colorArgb / note，走解码器的缺省字段路径。
     */
    private val periodTimes = listOf(
        "08:00" to "08:40", "09:00" to "09:40", "10:00" to "10:40", "11:00" to "11:40",
        "14:30" to "15:10", "15:30" to "16:10", "16:30" to "17:10", "17:30" to "18:10",
    )

    private fun sampleBackupJson(): String {
        val periods = periodTimes.mapIndexed { i, (s, e) ->
            """{"id":${100 + i},"termId":1,"index":${i + 1},"startTime":"$s","endTime":"$e"},""" +
                """{"id":${200 + i},"termId":2,"index":${i + 1},"startTime":"$s","endTime":"$e"}"""
        }.joinToString(",")
        return """
            {"format":"class-schedule-backup","version":1,
             "terms":[
               {"id":1,"name":"2025 秋","startEpochDay":20000,"totalWeeks":20,"isCurrent":false},
               {"id":2,"name":"2026 春","startEpochDay":20454,"totalWeeks":22,"isCurrent":true}],
             "periods":[$periods],
             "courses":[
               {"id":10,"termId":2,"name":"高等数学","teacher":"张老师","room":"A101","dayOfWeek":1,"startPeriodIndex":1,"periodCount":2,"weekRanges":"1-22"},
               {"id":11,"termId":1,"name":"旧学期的课","dayOfWeek":2,"startPeriodIndex":1,"periodCount":2,"weekRanges":"1-20"}]}
        """.trimIndent()
    }

    /**
     * 内存版 [CourseStore]：Flow 全部由 MutableStateFlow 驱动，
     * 挂起方法直接记录调用，测试断言"有没有落库、落了什么"。
     * nextPeriod 委托给真实的 CourseRepository 逻辑，测的是真行为。
     */
    private class FakeCourseStore : CourseStore {
        override val currentTerm = MutableStateFlow<Term?>(null)
        private val courseFlows = mutableMapOf<Long, MutableStateFlow<List<Course>>>()
        private val periodFlows = mutableMapOf<Long, MutableStateFlow<List<Period>>>()

        /** 全量学期清单（快照导出用） */
        val terms = mutableListOf<Term>()

        var seedTermId = 1L
        var migrated = false
        var snapshot: Term? = null
        var listCoursesResult: List<Course> = emptyList()

        val savedCourses = mutableListOf<Course>()
        val deletedCourses = mutableListOf<Course>()
        val updatedTerms = mutableListOf<Term>()
        val updatedPeriods = mutableListOf<Period>()
        val insertedPeriods = mutableListOf<Period>()
        val deletedPeriods = mutableListOf<Period>()

        // ── 导入记录 ──
        val importCalls = mutableListOf<Triple<Long, List<ParsedCourse>, ImportStrategy>>()
        val replacedData = mutableListOf<BackupData>()

        override fun coursesOf(termId: Long): Flow<List<Course>> =
            courseFlows.getOrPut(termId) { MutableStateFlow(emptyList()) }

        override fun periodsOf(termId: Long): Flow<List<Period>> =
            periodFlows.getOrPut(termId) { MutableStateFlow(emptyList()) }

        fun setCourses(termId: Long, courses: List<Course>) {
            courseFlows.getOrPut(termId) { MutableStateFlow(emptyList()) }.value = courses
        }

        fun setPeriods(termId: Long, periods: List<Period>) {
            periodFlows.getOrPut(termId) { MutableStateFlow(emptyList()) }.value = periods
        }

        /** 直接读当前内存里的课程列表（coursesOf 返回的是 Flow 接口，看不到 value） */
        fun currentCourses(termId: Long): List<Course> = courseFlows[termId]?.value ?: emptyList()

        override suspend fun ensureSeedData(): Long = seedTermId

        override suspend fun migrateLegacyDefaultPeriods(termId: Long): Boolean = migrated

        override suspend fun currentTermSnapshot(): Term? = snapshot

        override suspend fun listCourses(termId: Long): List<Course> = listCoursesResult

        override suspend fun saveCourse(course: Course): Long {
            savedCourses += course
            return course.id
        }

        override suspend fun deleteCourse(course: Course) {
            deletedCourses += course
        }

        override suspend fun updateTerm(term: Term) {
            updatedTerms += term
        }

        override suspend fun updatePeriod(period: Period) {
            updatedPeriods += period
        }

        override suspend fun insertPeriod(period: Period) {
            insertedPeriods += period
        }

        override suspend fun deletePeriod(period: Period) {
            deletedPeriods += period
        }

        override fun nextPeriod(termId: Long, existing: List<Period>): Period =
            CourseRepository.nextPeriod(termId, existing)

        // ── 外观配置：内存里真实存取，Flow 不涉及（外观是一次性读 + 命令式写） ──

        var appearance: Appearance = Appearance.DEFAULT

        override suspend fun loadAppearance(): Appearance = appearance

        override suspend fun saveAppearance(appearance: Appearance) {
            this.appearance = appearance
        }

        // ── 导入：内存里真实应用，Flow 会重发，VM 的 flatMapLatest 能观察到 ──

        override suspend fun importCourses(
            termId: Long,
            courses: List<ParsedCourse>,
            strategy: ImportStrategy,
        ): ImportCounts {
            importCalls += Triple(termId, courses, strategy)
            val existing = courseFlows[termId]?.value ?: emptyList()
            return when (strategy) {
                ImportStrategy.REPLACE_TERM -> {
                    setCourses(termId, courses.map { it.toCourse(termId) })
                    ImportCounts(courses.size, 0)
                }
                ImportStrategy.APPEND -> {
                    setCourses(termId, existing + courses.map { it.toCourse(termId) })
                    ImportCounts(courses.size, 0)
                }
                ImportStrategy.MERGE -> {
                    val plan = ImportPlanner.plan(existing, courses, ImportStrategy.MERGE)
                    setCourses(termId, existing + plan.toInsert.map { it.toCourse(termId) })
                    ImportCounts(plan.toInsert.size, plan.skipped)
                }
            }
        }

        override suspend fun replaceAllData(data: BackupData) {
            replacedData += data
            courseFlows.clear()
            periodFlows.clear()
            terms.clear()
            terms += data.terms
            data.periods.groupBy { it.termId }.forEach { (tid, ps) -> setPeriods(tid, ps) }
            data.courses.groupBy { it.termId }.forEach { (tid, cs) -> setCourses(tid, cs) }
            // 当前学期换成备份里 isCurrent 的那个（与真实恢复的行为一致）
            val current = data.terms.firstOrNull { it.isCurrent } ?: data.terms.firstOrNull()
            snapshot = current
            currentTerm.value = current
        }

        override suspend fun snapshotAll(): BackupData = BackupData(
            terms = terms.toList(),
            periods = periodFlows.values.flatMap { it.value },
            courses = courseFlows.values.flatMap { it.value },
        )
    }
}
