package com.classschedule.app.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CSV 导入测试（计划文档 10.2.2 / 10.5）。
 *
 * 导出功能 0.10.0 已移除，不再做往返测试；输入全部手工构造
 * （含引号转义、中文表头等真实文件形态），断言逐字段解析与坏行报告。
 */
class CsvCodecTest {

    private val header = "name,teacher,room,dayOfWeek,startPeriodIndex,periodCount,weekRanges,weekParity,colorArgb,note"

    // ── 合法输入 ────────────────────────────────────────────

    @Test
    fun `手工CSV_每个字段都对得上`() {
        val text = header + "\r\n" +
            "高等数学,张老师,A101,1,1,2,1-16,EVERY,,\r\n" +
            "大学英语,,B203,3,5,2,1-20,ODD,-65536,备注一\r\n"
        val report = CsvCodec.parse(text)

        assertTrue(report.issues.isEmpty())
        assertEquals(2, report.courses.size)

        val math = report.courses[0]
        assertEquals("高等数学", math.name)
        assertEquals("张老师", math.teacher)
        assertEquals("A101", math.room)
        assertEquals(1, math.dayOfWeek)
        assertEquals(1, math.startPeriodIndex)
        assertEquals(2, math.periodCount)
        assertEquals("1-16", math.weekRanges)
        assertEquals(com.classschedule.app.data.WeekParity.EVERY, math.weekParity)
        assertNull(math.colorArgb) // 空 = 自动取色
        assertNull(math.note)

        val english = report.courses[1]
        assertNull(english.teacher) // 空字段
        assertEquals(com.classschedule.app.data.WeekParity.ODD, english.weekParity)
        assertEquals(-65536L, english.colorArgb)
        assertEquals("备注一", english.note)
    }

    @Test
    fun `引号字段_内含逗号引号换行都能还原`() {
        val text = header + "\r\n" +
            "\"高数, 上册\",\"张\"\"老师\",\"A\n101\",1,1,2,1-16,EVERY,,\"备注, 带逗号\"\r\n"
        val report = CsvCodec.parse(text)

        assertTrue(report.issues.isEmpty())
        val c = report.courses.single()
        assertEquals("高数, 上册", c.name)
        assertEquals("张\"老师", c.teacher) // 双写引号还原为单引号
        assertEquals("A\n101", c.room) // 引号内的换行属于字段内容
        assertEquals("备注, 带逗号", c.note)
    }

    @Test
    fun `中文表头也能识别`() {
        val text = "课程名,教师,教室,星期,起始节次,节数,周次,单双周,颜色,备注\r\n" +
            "高等数学,张老师,A101,1,1,2,1-16,EVERY,,\r\n"
        val report = CsvCodec.parse(text)
        assertEquals(0, report.issues.size)
        assertEquals("高等数学", report.courses.single().name)
        assertEquals(2, report.courses.single().periodCount)
    }

    @Test
    fun `没有表头时第一行数据行照常解析`() {
        val report = CsvCodec.parse("高等数学,张老师,A101,1,1,2,1-16,EVERY,,\r\n")
        assertEquals(0, report.issues.size)
        assertEquals(1, report.courses.size)
    }

    @Test
    fun `仅表头时解析出零门课且不报错`() {
        val report = CsvCodec.parse(header + "\r\n")
        assertTrue(report.courses.isEmpty())
        assertTrue(report.issues.isEmpty())
    }

    @Test
    fun `空文件解析出零门课且不报错`() {
        val report = CsvCodec.parse("")
        assertTrue(report.courses.isEmpty())
        assertTrue(report.issues.isEmpty())
    }

    @Test
    fun `周次超出60周无法解析_进issues不猜`() {
        // MAX_WEEK=60 是防撑爆上限：超出的一律视为无法解析，不静默裁剪猜测
        val report = CsvCodec.parse(header + "\r\nX,,,1,1,2,1-99,EVERY,,\r\n")
        assertEquals(0, report.courses.size)
        assertEquals(1, report.issues.size)
        assertTrue(report.issues.single().reason.contains("无法解析"))
    }

    @Test
    fun `周次在60周以内照常入库`() {
        val report = CsvCodec.parse(header + "\r\nX,,,1,1,2,1-60,EVERY,,\r\n")
        assertEquals(0, report.issues.size)
        assertEquals("1-60", report.courses.single().weekRanges)
    }

    // ── 坏行不拖死好行 ─────────────────────────────────────

    @Test
    fun `三行里两行坏_合法行照常入库_坏行各带行号和原因`() {
        val text = header + "\r\n" +
            "高等数学,张老师,A101,1,1,2,1-16,EVERY,,\r\n" +
            "大学英语,李老师,B203,9,3,2,1-16,EVERY,,\r\n" +
            "体育,周老师,操场,5,3,1,第几周,EVERY,,\r\n"
        val report = CsvCodec.parse(text)

        assertEquals(1, report.courses.size)
        assertEquals("高等数学", report.courses.single().name)
        assertEquals(2, report.issues.size)
        assertTrue(report.issues[0].location.contains("3")) // 第 3 行（表头是第 1 行）
        assertTrue(report.issues[0].reason.contains("星期"))
        assertTrue(report.issues[1].location.contains("4"))
        assertTrue(report.issues[1].reason.contains("周次"))
    }

    @Test
    fun `列数不足报行号`() {
        val report = CsvCodec.parse("高等数学,张老师,1,1\r\n")
        assertEquals(0, report.courses.size)
        assertEquals(1, report.issues.size)
        assertTrue(report.issues.single().reason.contains("列数不足"))
    }

    @Test
    fun `起始节次为0被拒`() {
        val report = CsvCodec.parse(header + "\r\nX,,,1,0,2,1-16,EVERY,,\r\n")
        assertTrue(report.courses.isEmpty())
        assertTrue(report.issues.single().reason.contains("起始节次"))
    }

    @Test
    fun `占用节数为0被拒`() {
        val report = CsvCodec.parse(header + "\r\nX,,,1,1,0,1-16,EVERY,,\r\n")
        assertTrue(report.courses.isEmpty())
        assertTrue(report.issues.single().reason.contains("占用节数"))
    }

    @Test
    fun `单双周取值非法被拒`() {
        val report = CsvCodec.parse(header + "\r\nX,,,1,1,2,1-16,WEEKLY,,\r\n")
        assertTrue(report.courses.isEmpty())
        assertTrue(report.issues.single().reason.contains("单双周"))
    }

    @Test
    fun `颜色不是数字被拒`() {
        val report = CsvCodec.parse(header + "\r\nX,,,1,1,2,1-16,EVERY,红色,\r\n")
        assertTrue(report.courses.isEmpty())
        assertTrue(report.issues.single().reason.contains("颜色"))
    }

    @Test
    fun `引号不闭合不崩_当作普通字符处理`() {
        val text = header + "\r\n\"高等数学,张老师,A101,1,1,2,1-16,EVERY,,\r\n"
        val report = CsvCodec.parse(text)
        // 未闭合引号把剩余内容都吞进一个字段：列数不足，报错但不崩
        assertTrue(report.courses.isEmpty())
        assertEquals(1, report.issues.size)
    }

    @Test
    fun `空行被跳过`() {
        val text = header + "\r\n\r\nX,,,1,1,2,1-16,EVERY,,\r\n\r\n"
        val report = CsvCodec.parse(text)
        assertEquals(1, report.courses.size)
        assertTrue(report.issues.isEmpty())
    }

    @Test
    fun `LF换行也能解析`() {
        val report = CsvCodec.parse(header + "\nX,,,1,1,2,1-16,EVERY,,\n")
        assertEquals(1, report.courses.size)
    }
}
