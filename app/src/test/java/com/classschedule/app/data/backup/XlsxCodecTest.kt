package com.classschedule.app.data.backup

import com.classschedule.app.data.WeekParity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * XLSX 导入测试（F-31 / F-38，规则见计划文档 10.2.5）。
 *
 * 全部用 ZipOutputStream 现场造包（见 [Fixtures.buildXlsx]），不提交二进制夹具；
 * 布局与内容都是手工构造——0.10.0 起导出已移除，不做往返测试。
 *
 * 覆盖（对照 10.5）：矩阵布局（多行块 / 单行块 / 单双周 / 无周次兜底 /
 * 一格两课 / 合并单元格 / 时间行头）、列表布局复用 CSV 行解析、
 * sheet 选取、共享字符串多 run、inlineStr、公式结果串、畸形输入。
 */
class XlsxCodecTest {

    private val term = Fixtures.term()
    private val periods = Fixtures.periods()

    // ── 矩阵布局（教务系统课表的主流形态） ──────────────────

    @Test
    fun `矩阵课表_多行块课程字段都对得上`() {
        // 经典教务格式：课程名 / 教师 / 地点 / 周次分行写
        val bytes = Fixtures.buildXlsx(
            Fixtures.SheetSpec(
                "课表",
                listOf(
                    listOf("节次\\星期", "星期一", "星期二", "星期三"),
                    listOf("1-2", "高等数学\n张, 老师\n教三楼301\n1-16周", "", ""),
                    listOf("3-4", "", "大学英语\n李老师\n外语楼201\n1-20周\n单周", ""),
                    listOf("5", "", "", "体育\n操场\n双周"),
                ),
            ),
        )

        val report = XlsxCodec.parse(bytes, term, periods)

        assertEquals(0, report.issues.size)
        assertEquals(3, report.courses.size)
        val math = report.courses[0]
        assertEquals("高等数学", math.name)
        assertEquals("张, 老师", math.teacher) // 含逗号的教师名不被当分隔符
        assertEquals("教三楼301", math.room)
        assertEquals(1, math.dayOfWeek)
        assertEquals(1, math.startPeriodIndex)
        assertEquals(2, math.periodCount)
        assertEquals("1-16", math.weekRanges)
        assertEquals(WeekParity.EVERY, math.weekParity)
        assertNull(math.note)

        val english = report.courses[1]
        assertEquals("大学英语", english.name)
        assertEquals("外语楼201", english.room)
        assertEquals(2, english.dayOfWeek)
        assertEquals(3, english.startPeriodIndex)
        assertEquals("1-20", english.weekRanges)
        assertEquals(WeekParity.ODD, english.weekParity)

        val pe = report.courses[2]
        assertEquals("体育", pe.name)
        assertEquals("操场", pe.room) // "操场"先按地点特征认，不当教师
        assertEquals(3, pe.dayOfWeek)
        assertEquals(5, pe.startPeriodIndex)
        assertEquals(1, pe.periodCount)
        assertEquals("1-20", pe.weekRanges) // 无周次行 → 兜底整个学期
        assertEquals(WeekParity.EVEN, pe.weekParity)
    }

    @Test
    fun `单行块_空格分隔也能拆出教师教室周次`() {
        val bytes = Fixtures.buildXlsx(
            Fixtures.SheetSpec(
                "课表",
                listOf(
                    listOf("节次\\星期", "星期一", "星期二", "星期三"),
                    listOf("1-2", "大学英语 李老师 外语楼201 1-8周", "", ""),
                ),
            ),
        )

        val c = XlsxCodec.parse(bytes, term, periods).courses.single()
        assertEquals("大学英语", c.name)
        assertEquals("李老师", c.teacher)
        assertEquals("外语楼201", c.room)
        assertEquals(1, c.dayOfWeek)
        assertEquals("1-8", c.weekRanges)
        assertNull(c.note)
    }

    @Test
    fun `一个格子两门课_空行分隔各成一门`() {
        val cell = "大学英语\n李四\nB203\n1-8周\n\n高等数学\n张三\nA301\n9-16周"
        val bytes = Fixtures.buildXlsx(
            Fixtures.SheetSpec(
                "课表",
                listOf(
                    listOf("节次\\星期", "星期一", "星期二", "星期三"),
                    listOf("1-2", cell, "", ""),
                ),
            ),
        )

        val courses = XlsxCodec.parse(bytes, term, periods).courses
        assertEquals(2, courses.size)
        assertEquals("大学英语", courses[0].name)
        assertEquals("1-8", courses[0].weekRanges)
        assertEquals("高等数学", courses[1].name)
        assertEquals("9-16", courses[1].weekRanges)
    }

    @Test
    fun `合并单元格_标题行广播后不破坏星期表头`() {
        // "上午"跨列合并：广播到 B2:D2，但这些格子既不是星期也不是节次词
        val bytes = Fixtures.buildXlsx(
            Fixtures.SheetSpec(
                "课表",
                listOf(
                    listOf("节次\\星期", "星期一", "星期二", "星期三"),
                    listOf("上午", "", "", ""),
                    listOf("1-2", "高等数学\n张老师\nA101", "", ""),
                    listOf("3-4", "", "大学英语\n李老师\nB203", ""),
                    listOf("5-6", "", "", "体育\n王老师\n操场"),
                ),
                merges = listOf("A2:D2"),
            ),
        )

        val report = XlsxCodec.parse(bytes, term, periods)
        assertEquals(0, report.issues.size)
        assertEquals(3, report.courses.size)
        assertEquals("高等数学", report.courses[0].name)
        assertEquals(2, report.courses[1].dayOfWeek)
        assertEquals(3, report.courses[2].dayOfWeek)
    }

    @Test
    fun `时间行头_按当前学期节次的墙上时间匹配`() {
        val bytes = Fixtures.buildXlsx(
            Fixtures.SheetSpec(
                "课表",
                listOf(
                    listOf("节次\\星期", "星期一", "星期二", "星期三"),
                    listOf("08:00-09:40", "高等数学\n张老师\nA101", "", ""),
                    listOf("10:00-11:40", "", "大学英语\n李老师\nB203", ""),
                ),
            ),
        )

        val report = XlsxCodec.parse(bytes, term, periods)
        assertEquals(0, report.issues.size)
        val math = report.courses.single { it.name == "高等数学" }
        assertEquals(1, math.startPeriodIndex) // 08:00 = 第 1 节
        assertEquals(2, math.periodCount) // 到 09:40 收尾 = 第 2 节（墙上时间，不放大）
        val english = report.courses.single { it.name == "大学英语" }
        assertEquals(3, english.startPeriodIndex) // 10:00 = 第 3 节
        assertEquals(2, english.periodCount)
    }

    @Test
    fun `周次超出学期总周数_裁剪到总周数`() {
        val bytes = Fixtures.buildXlsx(
            Fixtures.SheetSpec(
                "课表",
                listOf(
                    listOf("节次\\星期", "星期一", "星期二", "星期三"),
                    listOf("1-2", "高等数学\n张老师\nA101\n18-25周", "", ""),
                ),
            ),
        )

        val c = XlsxCodec.parse(bytes, term, periods).courses.single()
        assertEquals("18-20", c.weekRanges) // 学期共 20 周，25 裁掉
    }

    @Test
    fun `认不出的行_原样进note不丢信息`() {
        val cell = "高等数学\n张老师\nA101\n1-16周\n第5周起单周辅导（自愿）"
        val bytes = Fixtures.buildXlsx(
            Fixtures.SheetSpec(
                "课表",
                listOf(
                    listOf("节次\\星期", "星期一", "星期二", "星期三"),
                    listOf("1-2", cell, "", ""),
                ),
            ),
        )

        val c = XlsxCodec.parse(bytes, term, periods).courses.single()
        assertEquals("高等数学", c.name)
        assertEquals("1-16", c.weekRanges)
        assertEquals("第5周起单周辅导（自愿）", c.note)
    }

    @Test
    fun `课程名带教务系统前缀_原样保留不猜`() {
        val bytes = Fixtures.buildXlsx(
            Fixtures.SheetSpec(
                "课表",
                listOf(
                    listOf("节次\\星期", "星期一", "星期二", "星期三"),
                    listOf("1-2", "【必修】高等数学", "", ""),
                ),
            ),
        )

        val c = XlsxCodec.parse(bytes, term, periods).courses.single()
        assertEquals("【必修】高等数学", c.name)
    }

    // ── 列表布局（复用 CSV 行解析） ─────────────────────────

    @Test
    fun `列表布局_走CSV行解析且文本星期转数字`() {
        // 教务系统另一种导出：一行一门课，星期是"周一"文本
        val bytes = Fixtures.buildXlsx(
            Fixtures.SheetSpec(
                "名单",
                listOf(
                    listOf("课程名", "教师", "教室", "星期", "起始节次", "节数", "周次", "单双周", "颜色", "备注"),
                    listOf("高等数学", "张老师", "A101", "周一", "1", "2", "1-16", "EVERY", "", "无"),
                    listOf("大学英语", "李老师", "B203", "周三", "3", "2", "1-20", "ODD", "", "无"),
                ),
            ),
        )

        val report = XlsxCodec.parse(bytes, term, periods)
        assertEquals(0, report.issues.size)
        assertEquals(2, report.courses.size)
        assertEquals(1, report.courses[0].dayOfWeek) // "周一" → 1
        assertEquals(3, report.courses[1].dayOfWeek) // "周三" → 3
        assertEquals(WeekParity.ODD, report.courses[1].weekParity)
        assertEquals("无", report.courses[0].note)
    }

    @Test
    fun `列表布局_少于10列但关键列齐全也导入`() {
        // 7 列手工列表（缺单双周 / 颜色 / 备注）：旧实现因"列数不足 10"整份报废
        val bytes = Fixtures.buildXlsx(
            Fixtures.SheetSpec(
                "名单",
                listOf(
                    listOf("课程名", "教师", "教室", "星期", "起始节次", "节数", "周次"),
                    listOf("高等数学", "张老师", "A101", "周一", "1", "2", "1-16"),
                ),
            ),
        )

        val report = XlsxCodec.parse(bytes, term, periods)
        assertEquals(0, report.issues.size)
        val c = report.courses.single()
        assertEquals("高等数学", c.name)
        assertEquals("张老师", c.teacher)
        assertEquals(1, c.dayOfWeek) // "周一" → 1
        assertEquals(1, c.startPeriodIndex)
        assertEquals(2, c.periodCount)
        assertEquals("1-16", c.weekRanges)
    }

    // ── sheet 选取与底层 xml 路径 ───────────────────────────

    @Test
    fun `sheet选取_第一个是说明页时用第二个`() {
        val bytes = Fixtures.buildXlsx(
            Fixtures.SheetSpec("说明", listOf(listOf("本文件由教务系统生成"))),
            Fixtures.SheetSpec(
                "课表",
                listOf(
                    listOf("节次\\星期", "星期一", "星期二", "星期三"),
                    listOf("1-2", "高等数学\n张老师\nA101", "", ""),
                ),
            ),
        )

        val report = XlsxCodec.parse(bytes, term, periods)
        assertEquals(1, report.courses.size)
        assertTrue(report.format.contains("课表")) // 报告标明用了哪个 sheet
        assertTrue(report.issues.isEmpty()) // 说明页的失败不掺进来
    }

    @Test
    fun `共享字符串多个run_拼接后课程名完整`() {
        val sharedXml =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                "<si><r><t>高等</t></r><r><t>数学</t></r></si>" +
                "<si><t xml:space=\"preserve\">节次\\星期</t></si>" +
                "<si><t>星期一</t></si><si><t>星期二</t></si><si><t>星期三</t></si>" +
                "<si><t>1-2</t></si>" +
                "</sst>"
        val sheetXml =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>" +
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>1</v></c><c r=\"B1\" t=\"s\"><v>2</v></c>" +
                "<c r=\"C1\" t=\"s\"><v>3</v></c><c r=\"D1\" t=\"s\"><v>4</v></c></row>" +
                "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>5</v></c><c r=\"B2\" t=\"s\"><v>0</v></c></row>" +
                "</sheetData></worksheet>"

        val report = XlsxCodec.parse(
            Fixtures.buildXlsxRaw(sheetXml, sharedStringsXml = sharedXml),
            term,
            periods,
        )
        assertEquals(0, report.issues.size)
        assertEquals("高等数学", report.courses.single().name) // 两个 run 拼成
    }

    @Test
    fun `inlineStr与公式结果串_都取字面值`() {
        val sheetXml =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>" +
                "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>节次\\星期</t></is></c>" +
                "<c r=\"B1\" t=\"inlineStr\"><is><t>星期一</t></is></c>" +
                "<c r=\"C1\" t=\"inlineStr\"><is><t>星期二</t></is></c>" +
                "<c r=\"D1\" t=\"inlineStr\"><is><t>星期三</t></is></c></row>" +
                "<row r=\"2\"><c r=\"A2\"><v>1</v></c>" + // 数字单元格（无 t）当行头
                "<c r=\"B2\" t=\"str\"><v>高等数学</v></c>" + // 公式缓存值
                "<c r=\"C2\" t=\"str\"><v>大学英语</v></c></row>" +
                "</sheetData></worksheet>"

        val report = XlsxCodec.parse(Fixtures.buildXlsxRaw(sheetXml), term, periods)
        assertEquals(0, report.issues.size)
        assertEquals(2, report.courses.size)
        assertEquals("高等数学", report.courses[0].name)
        assertEquals(1, report.courses[0].startPeriodIndex) // "1" → 第 1 节
        assertEquals("大学英语", report.courses[1].name)
    }

    // ── 真实样本回归（2026-10-12 用户提供的教务系统导出） ──────

    @Test
    fun `真实教务系统回归_合体节次行头与x000D残留与教室代码`() {
        // 结构复刻自真实样本 download.xlsx（未提交二进制，特征在此固化）：
        // 前两行标题；表头星期列带空列间隔；节次行头是"节次+时间"合体写法；
        // 课程格子四行（名称/周次/教室代码/教师）且每行带 _x000D_ 残留；
        // 多教师用逗号分隔；周次有区间也有显式列表
        val bytes = Fixtures.buildXlsx(
            Fixtures.SheetSpec(
                "Sheet1",
                listOf(
                    listOf(""),
                    listOf("2026-2027-1学期教学安排"),
                    listOf("学院：计算机学院/软件学院  班级：计算机科学与技术 B240403  人数：30"),
                    listOf(
                        "节次", "", "星期一", "", "", "星期二", "", "", "星期三", "", "",
                        "星期四", "", "", "星期五", "", "", "星期六", "", "", "", "星期日",
                    ),
                    listOf(
                        "1-2节08:00:00-09:40:00", "", "", "", "",
                        "单片机原理与应用_x000D_\n1-14周_x000D_\nXC205_x000D_\n赵小峰,钱思琪",
                        "", "",
                        "计算机网络_x000D_\n1-14周_x000D_\nXC507_x000D_\n李莹莹",
                    ),
                    listOf(
                        "3-4节10:00:00-11:40:00", "",
                        "形势与政策_x000D_\n1,3,5,7周_x000D_\nXD1_x000D_\n常振楠",
                    ),
                    listOf(
                        "5-6节14:30:00-16:10:00", "", "", "", "",
                        "习近平新时代中国特色社会主义思想概论_x000D_\n1-16周_x000D_\nXA3_x000D_\n曾洁",
                    ),
                ),
            ),
        )

        val report = XlsxCodec.parse(bytes, term, periods)

        assertEquals(0, report.issues.size)
        assertEquals(4, report.courses.size)
        assertTrue(report.courses.none { it.name.contains("_x000D_") })

        val mcu = report.courses.first()
        assertEquals("单片机原理与应用", mcu.name) // _x000D_ 后缀被清掉
        assertEquals(2, mcu.dayOfWeek)
        assertEquals(1, mcu.startPeriodIndex) // 合体行头 "1-2节08:00:00-09:40:00"
        assertEquals(2, mcu.periodCount)
        assertEquals("1-14", mcu.weekRanges)
        assertEquals("XC205", mcu.room) // 教室代码：字母 + 3 位数字
        assertEquals("赵小峰,钱思琪", mcu.teacher) // 多教师逗号分隔
        assertNull(mcu.note)

        val xingshi = report.courses.first { it.name == "形势与政策" }
        assertEquals(1, xingshi.dayOfWeek)
        assertEquals(3, xingshi.startPeriodIndex)
        assertEquals("1,3,5,7", xingshi.weekRanges) // 显式周次列表
        assertEquals("XD1", xingshi.room) // 教室代码：字母 + 1 位数字
        assertEquals("常振楠", xingshi.teacher)
        assertNull(xingshi.note)
    }

    @Test
    fun `多教师逗号分隔_整行作为教师`() {
        val cell = "单片机原理与应用_x000D_\n1-14周_x000D_\nXC205_x000D_\n赵小峰,钱思琪,王五"
        val bytes = Fixtures.buildXlsx(
            Fixtures.SheetSpec(
                "课表",
                listOf(
                    listOf("节次\\星期", "星期一", "星期二", "星期三"),
                    listOf("1-2节08:00:00-09:40:00", cell, "", ""),
                ),
            ),
        )

        val c = XlsxCodec.parse(bytes, term, periods).courses.single()
        assertEquals("赵小峰,钱思琪,王五", c.teacher)
        assertEquals("XC205", c.room)
        assertNull(c.note)
    }

    // ── 畸形输入 ────────────────────────────────────────────

    @Test
    fun `不是zip_报告无法读取`() {
        val report = XlsxCodec.parse("这不是表格，是纯文本".toByteArray(), term, periods)
        assertEquals(0, report.courses.size)
        assertEquals(1, report.issues.size)
        assertTrue(report.issues[0].reason.contains("压缩包"))
    }

    @Test
    fun `zip里没有worksheet_报告找不到工作表`() {
        // 有 workbook 但 sheets 为空：走不到任何 worksheet
        val empty = zipOfRaw(
            "xl/workbook.xml" to
                ("<?xml version=\"1.0\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                    "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets/></workbook>"),
        )
        val report = XlsxCodec.parse(empty, term, periods)
        assertEquals(0, report.courses.size)
        assertEquals(1, report.issues.size)
        assertTrue(report.issues[0].reason.contains("找不到工作表"))
    }

    @Test
    fun `sheet内容损坏_该sheet记问题`() {
        // 标签不闭合的 worksheet xml：DOM 解析抛错 → 记"内容损坏无法解析"
        val broken = "<?xml version=\"1.0\"?><worksheet><sheetData><row>"
        val bytes = Fixtures.buildXlsxRaw(broken)
        val report = XlsxCodec.parse(bytes, term, periods)
        assertEquals(0, report.courses.size)
        assertTrue(report.issues.any { it.reason.contains("损坏") })
    }

    @Test
    fun `空sheet被跳过_全部sheet无课程时报告`() {
        val bytes = Fixtures.buildXlsx(
            Fixtures.SheetSpec("空表", listOf(listOf("", ""), listOf("", ""))),
        )
        val report = XlsxCodec.parse(bytes, term, periods)
        assertEquals(0, report.courses.size)
        assertTrue(report.issues.isNotEmpty())
    }
}

/** 测试内辅助：只放指定条目的 zip（buildXlsx 之外的极端场景） */
private fun zipOfRaw(vararg entries: Pair<String, String>): ByteArray {
    val baos = java.io.ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(baos).use { zip ->
        entries.forEach { (name, content) ->
            zip.putNextEntry(java.util.zip.ZipEntry(name))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
    return baos.toByteArray()
}
