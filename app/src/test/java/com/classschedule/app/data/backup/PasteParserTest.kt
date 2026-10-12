package com.classschedule.app.data.backup

import com.classschedule.app.data.WeekParity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 粘贴课表文本解析器测试（计划文档 10.2.4 / 10.5）。
 */
class PasteParserTest {

    private val totalWeeks = 20
    private val maxPeriod = 8

    @Test
    fun `基本语法_课程名教师教室星期节次周次`() {
        val report = PasteParser.parse(
            "高等数学 张老师 A101 周一 1-2 1-16周\n大学英语 - B203 周三 3-4",
            totalWeeks, maxPeriod,
        )
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
        assertEquals(WeekParity.EVERY, math.weekParity)

        val english = report.courses[1]
        assertNull(english.teacher) // - 占位
        assertEquals("B203", english.room)
        assertEquals("1-20", english.weekRanges) // 缺省全学期
    }

    @Test
    fun `课名可以含空格`() {
        val report = PasteParser.parse("线性 代数 王老师 C305 周二 5-6", totalWeeks, maxPeriod)
        assertTrue(report.issues.isEmpty())
        assertEquals("线性 代数", report.courses.single().name)
    }

    @Test
    fun `制表符分隔也行`() {
        val report = PasteParser.parse("高等数学\t张老师\tA101\t周一\t1-2", totalWeeks, maxPeriod)
        assertTrue(report.issues.isEmpty())
        assertEquals("高等数学", report.courses.single().name)
    }

    @Test
    fun `星期支持周一星期一一礼拜一等写法`() {
        val report = PasteParser.parse(
            "甲 一 1 1\n乙 星期一 1 1\n丙 礼拜一 1 1",
            totalWeeks, maxPeriod,
        )
        assertTrue(report.issues.isEmpty())
        assertTrue(report.courses.all { it.dayOfWeek == 1 })
    }

    @Test
    fun `节次支持第3-4节和单节写法`() {
        val report = PasteParser.parse(
            "甲 - - 周一 第3-4节\n乙 - - 周二 7",
            totalWeeks, maxPeriod,
        )
        assertTrue(report.issues.isEmpty())
        assertEquals(3, report.courses[0].startPeriodIndex)
        assertEquals(2, report.courses[0].periodCount)
        assertEquals(7, report.courses[1].startPeriodIndex)
        assertEquals(1, report.courses[1].periodCount)
    }

    @Test
    fun `单双周可选单双每周`() {
        val report = PasteParser.parse(
            "甲 - - 周一 1-2 1-16周 单\n乙 - - 周二 1-2 1-16周 双\n丙 - - 周三 1-2 每周",
            totalWeeks, maxPeriod,
        )
        assertTrue(report.issues.isEmpty())
        assertEquals(WeekParity.ODD, report.courses[0].weekParity)
        assertEquals(WeekParity.EVEN, report.courses[1].weekParity)
        assertEquals(WeekParity.EVERY, report.courses[2].weekParity)
    }

    @Test
    fun `单双周写在周次位时顺手识别_周次缺省全学期`() {
        // 用户想写"每周上课"但省略了周次：这几个词作为周次本来也解析不了，
        // 顺势当成单双周处理，比报错更符合直觉
        val report = PasteParser.parse("甲 - - 周一 1-2 每周", totalWeeks, maxPeriod)
        assertTrue(report.issues.isEmpty())
        assertEquals("1-20", report.courses.single().weekRanges)
        assertEquals(WeekParity.EVERY, report.courses.single().weekParity)

        val report2 = PasteParser.parse("乙 - - 周二 1-2 单", totalWeeks, maxPeriod)
        assertTrue(report2.issues.isEmpty())
        assertEquals("1-20", report2.courses.single().weekRanges)
        assertEquals(WeekParity.ODD, report2.courses.single().weekParity)
    }

    @Test
    fun `注释行与空行忽略`() {
        val report = PasteParser.parse(
            "# 这是注释\n\n高等数学 张老师 A101 周一 1-2\n   \n# 另一条注释",
            totalWeeks, maxPeriod,
        )
        assertTrue(report.issues.isEmpty())
        assertEquals(1, report.courses.size)
    }

    @Test
    fun `缺星期报行号和原因`() {
        val report = PasteParser.parse("高等数学 张老师 A101 1-2", totalWeeks, maxPeriod)
        assertTrue(report.courses.isEmpty())
        assertEquals(1, report.issues.size)
        assertTrue(report.issues.single().location.contains("1"))
        assertTrue(report.issues.single().reason.contains("星期"))
    }

    @Test
    fun `星期在行首报没有课程名`() {
        val report = PasteParser.parse("周一 1-2", totalWeeks, maxPeriod)
        assertTrue(report.courses.isEmpty())
        assertTrue(report.issues.single().reason.contains("课程名"))
    }

    @Test
    fun `节次不是数字报原因`() {
        val report = PasteParser.parse("高等数学 - - 周一 第几节", totalWeeks, maxPeriod)
        assertTrue(report.courses.isEmpty())
        assertTrue(report.issues.single().reason.contains("节次"))
    }

    @Test
    fun `节次超出本学期范围报原因`() {
        val report = PasteParser.parse("高等数学 - - 周一 9-10", totalWeeks, maxPeriod)
        assertTrue(report.courses.isEmpty())
        assertTrue(report.issues.single().reason.contains("超出"))
    }

    @Test
    fun `周次无法解析报原因`() {
        val report = PasteParser.parse("高等数学 - - 周一 1-2 第几周", totalWeeks, maxPeriod)
        assertTrue(report.courses.isEmpty())
        assertTrue(report.issues.single().reason.contains("周次"))
    }

    @Test
    fun `单双周取值非法报原因`() {
        val report = PasteParser.parse("高等数学 - - 周一 1-2 1-16周 隔", totalWeeks, maxPeriod)
        assertTrue(report.courses.isEmpty())
        assertTrue(report.issues.single().reason.contains("单双周"))
    }

    @Test
    fun `好行坏行混排_坏行不拖死好行`() {
        val report = PasteParser.parse(
            "高等数学 张老师 A101 周一 1-2\n" +
                "大学英语 李老师 B203 周八 3-4\n" +
                "体育 周老师 操场 周三 3-4",
            totalWeeks, maxPeriod,
        )
        assertEquals(2, report.courses.size)
        assertEquals(1, report.issues.size)
        assertTrue(report.issues.single().location.contains("2")) // 第 2 行
    }

    @Test
    fun `多行周次用逗号分隔`() {
        val report = PasteParser.parse("甲 - - 周一 1-2 1-8,10-16周", totalWeeks, maxPeriod)
        assertTrue(report.issues.isEmpty())
        assertEquals("1-8,10-16", report.courses.single().weekRanges)
    }

    // ── 教师 / 教室的歧义分流（回归：省略教室时教师不被塞进教室） ──

    @Test
    fun `省略教室_只写教师时教师不被塞进教室`() {
        // 提示语顺序是「课程名 教师 教室」，用户省略教室只写教师；
        // 旧实现把唯一的那个 token 当教室，导致教师丢失、教室位显示人名
        val report = PasteParser.parse("高等数学 张老师 周一 1-2", totalWeeks, maxPeriod)
        assertTrue(report.issues.isEmpty())
        val c = report.courses.single()
        assertEquals("高等数学", c.name)
        assertEquals("张老师", c.teacher)
        assertNull(c.room)
    }

    @Test
    fun `只写教室_单个token像教室时判为教室`() {
        val report = PasteParser.parse("高等数学 A101 周一 1-2", totalWeeks, maxPeriod)
        assertTrue(report.issues.isEmpty())
        val c = report.courses.single()
        assertEquals("A101", c.room)
        assertNull(c.teacher)
    }

    @Test
    fun `带楼字的教室按教室识别`() {
        val report = PasteParser.parse("大学英语 李老师 外语楼201 周三 3-4", totalWeeks, maxPeriod)
        val c = report.courses.single()
        assertEquals("李老师", c.teacher)
        assertEquals("外语楼201", c.room)
    }

    @Test
    fun `en dash 与全角波浪的节次也能解析`() {
        // 从网页 / 文档复制来的 1–2（U+2013 en dash）过去会被判为非法
        val report = PasteParser.parse("高等数学 - - 周一 1–2", totalWeeks, maxPeriod)
        assertTrue(report.issues.isEmpty())
        assertEquals(2, report.courses.single().periodCount)
        val report2 = PasteParser.parse("大学英语 - - 周二 3～4", totalWeeks, maxPeriod)
        assertTrue(report2.issues.isEmpty())
        assertEquals(3, report2.courses.single().startPeriodIndex)
    }
}
