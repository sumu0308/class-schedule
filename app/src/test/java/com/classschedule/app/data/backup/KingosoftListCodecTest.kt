package com.classschedule.app.data.backup

import com.classschedule.app.data.WeekParity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 青果（Kingosoft）教务系统「列表」模式课表导入（F-38 扩展）。
 *
 * 覆盖重点：复合字段拆段（周次含逗号不能被当成时段分隔符）、
 * 一门课拆多条时间条目、单双周(双)、教师学号前缀剥离、
 * 教室容量剥离、节次越界、周次越界、实验分项表被跳过。
 *
 * 夹具全部用虚构课程名与教师名（真实教务样本含真名，不入库）。
 */
class KingosoftListCodecTest {

    private val term = Fixtures.term()
    private val periods = Fixtures.periods()

    /** 青果列表的 12 列表头（列序与真实导出一致） */
    private val header = listOf(
        "上课班级代码", "上课班级名称", "课程", "总学时", "学分", "修读性质",
        "任课教师", "选课状态", "外年级/专业选课", "教材", "上课时间地点", "备注",
    )

    /**
     * 组一份完整的青果列表 sheet：标题行 + 学年行 + 表头行 + 数据行。
     * 表头行是 detectHeader 的锚点，**必须包含**（真实文件里它前面还有标题/学年行）。
     */
    private fun rows(vararg body: List<String>): List<List<String>> =
        listOf(
            listOf("某某学院学生个人课表"),
            listOf("2026-2027学年第一学期"),
            header,
        ) + body.map { it as List<String> }

    private fun dataRow(
        name: String,
        teacher: String,
        timePlace: String,
        credits: String = "4.0",
    ): List<String> = listOf(
        "501570-002", "16", name, "64", credits, "初修",
        teacher, "选中", "否", "是", timePlace, "",
    )

    private fun parse(body: List<List<String>>) =
        XlsxCodec.parse(Fixtures.buildXlsx(Fixtures.SheetSpec("个人课表", body)), term, periods)

    // ── 核心：复合字段拆段 ────────────────────────────────────

    @Test
    fun `一门课含多时段_拆成多条时间条目`() {
        val r = parse(
            rows(
                dataRow(
                    "数据库原理与技术", "[200901019]王五",
                    "1-15,17-18周 一[3-4] 弘善楼B103(64),1-15,17-18周 五[3-4] 弘德楼A410(72)",
                ),
            ),
        )
        assertEquals(2, r.courses.size)
        with(r.courses[0]) {
            assertEquals("数据库原理与技术", name)
            assertEquals("王五", teacher) // 学号前缀已剥离
            assertEquals(1, dayOfWeek)
            assertEquals(3, startPeriodIndex)
            assertEquals(2, periodCount) // [3-4] → 占 2 节
            assertEquals("弘善楼B103", room) // 容量 (64) 已剥离
        }
        assertEquals(5, r.courses[1].dayOfWeek)
        assertEquals("弘德楼A410", r.courses[1].room)
    }

    @Test
    fun `周次自身含逗号_不被当成时段分隔符`() {
        // 1-15,17-18 里的逗号若被当分隔符，会把这段切成两半从而丢掉周次
        val r = parse(
            rows(dataRow("操作系统", "赵六", "1-15,17-18周 三[5-6] 实训楼301(48)")),
        )
        assertEquals(1, r.courses.size)
        assertEquals("1-15,17-18", r.courses[0].weekRanges)
        assertEquals(3, r.courses[0].dayOfWeek)
    }

    @Test
    fun `周次与时段都含逗号_仍正确拆成两条`() {
        val r = parse(
            rows(
                dataRow(
                    "计算机网络", "钱七",
                    "1-15,17-18周 三[5-6] 9#三(231),1-15,17-18周 一[1-2] 9#三(231)",
                ),
            ),
        )
        assertEquals(2, r.courses.size)
        r.courses.forEach {
            assertEquals("1-15,17-18", it.weekRanges)
            assertEquals("9#三", it.room)
        }
        assertEquals(listOf(3, 1), r.courses.map { it.dayOfWeek })
    }

    @Test
    fun `单双周标记双_识别为单双周且各段独立`() {
        val r = parse(
            rows(
                dataRow(
                    "单片机原理及应用", "孙八",
                    "1-15,17-18周 五[1-2] 0#508物联网实验室(64),1-15,17-18周(双) 四[3-4] 0#508物联网实验室(64)",
                ),
            ),
        )
        assertEquals(2, r.courses.size)
        // (双) 只出现在第二段之前 → 第一段是每周，第二段是双周
        assertEquals(WeekParity.EVERY, r.courses[0].weekParity)
        assertEquals(WeekParity.EVEN, r.courses[1].weekParity)
    }

    @Test
    fun `星期日与星期六_映射到7和6`() {
        val r = parse(
            rows(dataRow("体育", "周九", "1-16周 六[1-2] 操场,1-16周 日[3-4] 操场")),
        )
        assertEquals(2, r.courses.size)
        assertEquals(6, r.courses[0].dayOfWeek)
        assertEquals(7, r.courses[1].dayOfWeek)
    }

    @Test
    fun `单节课_占用节数为1`() {
        // `[5]` 只写一个数字：不写起-止。此时起止同值 → 起始第 5 节、占 1 节。
        val r = parse(rows(dataRow("军事训练", "吴十", "1-8周 一[5] 操场")))
        assertEquals(1, r.courses.size)
        assertEquals(5, r.courses[0].startPeriodIndex) // 起始节次是 5，不是 1
        assertEquals(1, r.courses[0].periodCount) // 占用 1 节
    }

    @Test
    fun `无教师_教师字段为null而非报错`() {
        val r = parse(rows(dataRow("毕业设计", "", "1-16周 五[7-8] 实训楼")))
        assertEquals(1, r.courses.size)
        assertNull(r.courses[0].teacher)
    }

    // ── 边界与报错 ───────────────────────────────────────────

    @Test
    fun `节次超出当前学期_逐行报错不静默丢弃`() {
        // 默认 8 小节，第 9-10 节越界
        val r = parse(rows(dataRow("大学物理", "郑十一", "1-16周 二[9-10] 实验楼")))
        assertEquals(0, r.courses.size)
        assertEquals(1, r.issues.size)
        assertTrue(r.issues[0].reason.contains("超出"))
    }

    @Test
    fun `周次超出学期总周数_裁剪而非报错`() {
        // 学期 20 周，导出写 1-24 → 裁到 1-20
        val r = parse(rows(dataRow("马克思主义原理", "王十二", "1-24周 一[1-2] 文楼")))
        assertEquals(1, r.courses.size)
        assertEquals("1-20", r.courses[0].weekRanges)
    }

    @Test
    fun `周次全部超出学期_报错不产出课程`() {
        val r = parse(rows(dataRow("全公选", "李十三", "21-30周 三[1-2] 文楼")))
        assertEquals(0, r.courses.size)
        assertEquals(1, r.issues.size)
    }

    @Test
    fun `时间地点认不出星期节次_报明确原因`() {
        val r = parse(rows(dataRow("待定课程", "王十四", "待定")))
        assertEquals(0, r.courses.size)
        assertTrue(r.issues[0].reason.contains("认不出"))
    }

    @Test
    fun `课程序号前缀与方括号代码_从课程名剥离`() {
        val r = parse(rows(dataRow("[2115Z0108]编译原理", "[200801015]王十五", "1-16周 五[5-6] 8#一(231)")))
        assertEquals("编译原理", r.courses[0].name)
        assertEquals("王十五", r.courses[0].teacher)
    }

    // ── 实验分项表被跳过 ─────────────────────────────────────

    @Test
    fun `同sheet下半部分的实验分项表_不导入`() {
        val body = listOf(
            listOf("某某学院学生个人课表"),
            listOf("2026-2027学年第一学期"),
            header,
            dataRow("编译原理", "王十五", "1-16周 五[5-6] 8#一(231)"),
            dataRow("计算机网络", "钱七", "1-16周 三[5-6] 9#三(231)"),
            listOf("实验项目数：12", "", "", "", "", ""),
            listOf("课程", "", "", "", "上课", "实验项目", "实验班级", "指导老师", "节次", "实验室"),
            listOf(
                "[2115Z0108]编译原理", "", "", "", "",
                "[2115Z0108001]词法分析程序", "002", "[200801015]王十五", "日[5-8节](12周)", "0#502",
            ),
        )
        val r = parse(body)
        assertEquals(2, r.courses.size)
        // 实验项目名不该混进课程
        assertTrue(r.courses.none { it.name.contains("词法分析") })
    }

    @Test
    fun `实验汇总行出现后停止处理_不会把实验当课程`() {
        val body = listOf(
            header,
            dataRow("高等数学", "王五", "1-16周 一[1-2] 理楼"),
            listOf("实验项目数：12"),
            listOf("课程", "", "", "上课", "实验项目"),
        )
        val r = parse(body)
        assertEquals(1, r.courses.size)
    }

    // ── 与既有布局互不干扰 ───────────────────────────────────

    @Test
    fun `二维表布局_不走青果解析分支`() {
        // 青果二维表没有「上课时间地点」列，应仍走矩阵识别。
        // 夹具必须给足 3 个以上星期列 —— detectMatrix 要求表头行≥3 个星期词才认矩阵布局。
        val body = listOf(
            listOf("节次", "星期一", "星期二", "星期三"),
            listOf("1-2", "高等数学(王五)", "", ""),
            listOf("3-4", "", "大学英语(钱七)", ""),
            listOf("5-6", "", "", "数据结构(钱八)"),
        )
        val r = XlsxCodec.parse(Fixtures.buildXlsx(Fixtures.SheetSpec("教学安排表", body)), term, periods)
        assertTrue(r.courses.isNotEmpty())
        assertTrue(r.courses.any { it.name.contains("高等数学") })
    }

    @Test
    fun `没有上课时间地点列_不触发青果解析`() {
        val body = listOf(
            listOf("课程名", "教师", "教室", "星期", "起始节次", "节数", "周次"),
            listOf("高等数学", "王五", "A101", "1", "1", "2", "1-16"),
        )
        val r = parse(body)
        // 走通用列表布局：列位置对得上（星期/起始节次/节数/周次）
        assertEquals(1, r.courses.size)
        assertEquals("高等数学", r.courses[0].name)
        assertEquals(1, r.courses[0].dayOfWeek)
    }
}
