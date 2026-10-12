package com.classschedule.app.data.backup

import com.classschedule.app.data.Term
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ICS 导入测试（计划文档 10.2.3 / 10.5）。
 *
 * 导出功能 0.10.0 已移除，不再做往返测试——所有输入改为**手工构造的 ICS 文本**，
 * 避免"导出/导入对称 bug 互相抵消"的盲区（10.5）。
 *
 * 保真度按 10.2.3 的诚实声明验收：**显示集合一致**（同一批课、同一时间、同一批周次）；
 * 备注与颜色没有标准载体，导入时丢弃，不做断言。
 */
class IcsCodecTest {

    private val term: Term = Fixtures.term()
    private val periods = Fixtures.periods()

    /** 按"显示集合"对比：课名集合、星期、节次区间、显示周次集合 */
    private fun assertSameDisplay(original: List<com.classschedule.app.data.Course>, parsed: List<ParsedCourse>) {
        assertEquals(original.map { it.name }.sorted(), parsed.map { it.name }.sorted())
        original.forEach { c ->
            val p = parsed.single { it.name == c.name }
            assertEquals("${c.name} 星期", c.dayOfWeek, p.dayOfWeek)
            assertEquals("${c.name} 起始节次", c.startPeriodIndex, p.startPeriodIndex)
            assertEquals("${c.name} 占用节数", c.periodCount, p.periodCount)
            assertEquals("${c.name} 显示周次", Fixtures.displayWeeks(c), Fixtures.displayWeeks(p))
        }
    }

    // ── 手工 ICS：显示集合 ──────────────────────────────

    @Test
    fun `手工ICS_覆盖各类边界_显示集合与源课一致`() {
        // 手工构造一份覆盖夹具全部边界的 ICS：跨课间连排、单周、双周、
        // 不连续周次（两个 VEVENT）、带转义逗号的教师名
        val ics = buildString {
            append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//class-schedule//test//CN\r\n")
            // 高等数学：周一 1-2 节（08:00-09:40 跨课间），1-16 周
            append("BEGIN:VEVENT\r\nSUMMARY:高等数学\r\n")
            append("DTSTART:${dateOf(1, 1, "080000")}\r\nDTEND:${dateOf(1, 1, "094000")}\r\n")
            append("RRULE:FREQ=WEEKLY;COUNT=16\r\n")
            append("LOCATION:A101\r\nDESCRIPTION:张\\, 老师\r\nEND:VEVENT\r\n")
            // 大学英语：周三 5-6 节（14:30-16:10），单周 1-19
            append("BEGIN:VEVENT\r\nSUMMARY:大学英语\r\n")
            append("DTSTART:${dateOf(1, 3, "143000")}\r\nDTEND:${dateOf(1, 3, "161000")}\r\n")
            append("RRULE:FREQ=WEEKLY;INTERVAL=2;COUNT=10\r\n")
            append("LOCATION:B203\r\nDESCRIPTION:李老师\r\nEND:VEVENT\r\n")
            // 体育：周五第 3 节（10:00-10:40），双周 2-20
            append("BEGIN:VEVENT\r\nSUMMARY:体育\r\n")
            append("DTSTART:${dateOf(2, 5, "100000")}\r\nDTEND:${dateOf(2, 5, "104000")}\r\n")
            append("RRULE:FREQ=WEEKLY;INTERVAL=2;COUNT=10\r\n")
            append("LOCATION:操场\r\nEND:VEVENT\r\n")
            // 实验课：周二 7-8 节（16:30-18:10），不连续周次 1-4 + 7-10，两个事件合并
            append("BEGIN:VEVENT\r\nSUMMARY:实验课\r\n")
            append("DTSTART:${dateOf(1, 2, "163000")}\r\nDTEND:${dateOf(1, 2, "181000")}\r\n")
            append("RRULE:FREQ=WEEKLY;COUNT=4\r\n")
            append("DESCRIPTION:赵老师\r\nEND:VEVENT\r\n")
            append("BEGIN:VEVENT\r\nSUMMARY:实验课\r\n")
            append("DTSTART:${dateOf(7, 2, "163000")}\r\nDTEND:${dateOf(7, 2, "181000")}\r\n")
            append("RRULE:FREQ=WEEKLY;COUNT=4\r\n")
            append("DESCRIPTION:赵老师\r\nEND:VEVENT\r\n")
            append("END:VCALENDAR\r\n")
        }
        val report = IcsCodec.parse(ics, term, periods)
        assertTrue("不该有未识别事件：${report.issues}", report.issues.isEmpty())
        assertSameDisplay(Fixtures.courses(), report.courses)
    }

    @Test
    fun `文本字段_LOCATION与DESCRIPTION导入后保留_转义逗号还原`() {
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nSUMMARY:高等数学\r\n" +
            "DTSTART:${dateOf(1, 1, "080000")}\r\nDTEND:${dateOf(1, 1, "094000")}\r\n" +
            "RRULE:FREQ=WEEKLY;COUNT=16\r\n" +
            "LOCATION:A101\r\nDESCRIPTION:张\\, 老师\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val report = IcsCodec.parse(ics, term, periods)
        val c = report.courses.single()
        assertEquals("张, 老师", c.teacher) // 反斜杠转义的逗号还原
        assertEquals("A101", c.room)
    }

    @Test
    fun `没有LOCATION和DESCRIPTION_教师教室为null`() {
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nSUMMARY:体育\r\n" +
            "DTSTART:${dateOf(1, 5, "100000")}\r\nDTEND:${dateOf(1, 5, "104000")}\r\n" +
            "RRULE:FREQ=WEEKLY;COUNT=16\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val report = IcsCodec.parse(ics, term, periods)
        val c = report.courses.single()
        assertNull(c.teacher)
        assertNull(c.room)
    }

    @Test
    fun `不连续周次拆成多个VEVENT_导入后合并还原`() {
        // 真实场景：日历软件导出时把不连续周次拆成多个事件；
        // 导入侧按"同名+同星期+同节次"合并回一个课程（10.2.3）
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" +
            "BEGIN:VEVENT\r\nSUMMARY:实验课\r\n" +
            "DTSTART:${dateOf(1, 2, "163000")}\r\nDTEND:${dateOf(1, 2, "181000")}\r\n" +
            "RRULE:FREQ=WEEKLY;COUNT=4\r\nEND:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nSUMMARY:实验课\r\n" +
            "DTSTART:${dateOf(7, 2, "163000")}\r\nDTEND:${dateOf(7, 2, "181000")}\r\n" +
            "RRULE:FREQ=WEEKLY;COUNT=4\r\nEND:VEVENT\r\n" +
            "END:VCALENDAR\r\n"
        val report = IcsCodec.parse(ics, term, periods)
        assertEquals(1, report.courses.size)
        val c = report.courses.single()
        assertEquals("1-4,7-10", c.weekRanges)
        assertEquals(com.classschedule.app.data.WeekParity.EVERY, c.weekParity)
    }

    // ── 节次推算：跨课间休息的回归 ──────────────────────────

    @Test
    fun `DTEND跨课间休息_占用节数按墙上时间算不放大`() {
        // 08:00-09:40 是第 1-2 节（中间休息 20 分钟）。旧实现按"课时累加"推节数，
        // 会把 80 分钟课时 + 20 分钟休息错算成占 3 节以上。
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            SUMMARY:高等数学
            DTSTART:${dateOf(1, 1, "080000")}
            DTEND:${dateOf(1, 1, "094000")}
            RRULE:FREQ=WEEKLY;COUNT=16
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val report = IcsCodec.parse(ics, term, periods)
        val c = report.courses.single()
        assertEquals(2, c.periodCount)
        assertEquals(1, c.startPeriodIndex)
        assertEquals(1, c.dayOfWeek)
    }

    @Test
    fun `单节课_DTEND即本节结束`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            SUMMARY:体育
            DTSTART:${dateOf(3, 1, "100000")}
            DTEND:${dateOf(3, 1, "104000")}
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val report = IcsCodec.parse(ics, term, periods)
        val c = report.courses.single()
        assertEquals(1, c.periodCount)
        assertEquals(3, c.startPeriodIndex)
    }

    // ── 隔周与 UNTIL ──────────────────────────────────────

    @Test
    fun `两个隔周事件合并为单周课程`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            SUMMARY:物理实验
            DTSTART:${dateOf(1, 1, "143000")}
            DTEND:${dateOf(1, 1, "161000")}
            RRULE:FREQ=WEEKLY;INTERVAL=2;COUNT=3
            END:VEVENT
            BEGIN:VEVENT
            SUMMARY:物理实验
            DTSTART:${dateOf(7, 1, "143000")}
            DTEND:${dateOf(7, 1, "161000")}
            RRULE:FREQ=WEEKLY;INTERVAL=2;COUNT=3
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val report = IcsCodec.parse(ics, term, periods)
        val c = report.courses.single()
        assertEquals(setOf(1, 3, 5, 7, 9, 11), Fixtures.displayWeeks(c))
        assertEquals(com.classschedule.app.data.WeekParity.ODD, c.weekParity)
        assertEquals(5, c.startPeriodIndex)
    }

    @Test
    fun `UNTIL按日期推周`() {
        // 第 1 周开始、直到第 10 周的周三：应还原 1-10 周
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            SUMMARY:形势与政策
            DTSTART:${dateOf(1, 1, "080000")}
            DTEND:${dateOf(1, 1, "094000")}
            RRULE:FREQ=WEEKLY;UNTIL=${dateOf(10, 1, "235900")}
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val report = IcsCodec.parse(ics, term, periods)
        assertEquals(setOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), Fixtures.displayWeeks(report.courses.single()))
    }

    @Test
    fun `非WEEKLY的RRULE按单次处理并说明`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            SUMMARY:讲座
            DTSTART:${dateOf(1, 1, "080000")}
            DTEND:${dateOf(1, 1, "094000")}
            RRULE:FREQ=DAILY;COUNT=5
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val report = IcsCodec.parse(ics, term, periods)
        val c = report.courses.single()
        assertEquals(setOf(1), Fixtures.displayWeeks(c)) // 只有起始周
    }

    // ── 畸形输入 ──────────────────────────────────────────

    @Test
    fun `没有VEVENT给出说明`() {
        val report = IcsCodec.parse("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n", term, periods)
        assertTrue(report.courses.isEmpty())
        assertEquals(1, report.issues.size)
        assertTrue(report.issues.single().reason.contains("VEVENT"))
    }

    @Test
    fun `空文件给出说明`() {
        val report = IcsCodec.parse("", term, periods)
        assertTrue(report.courses.isEmpty())
        assertEquals(1, report.issues.size)
    }

    @Test
    fun `上课时间与节次对不上进issues不猜`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            SUMMARY:早读
            DTSTART:${dateOf(1, 1, "070000")}
            DTEND:${dateOf(1, 1, "074000")}
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val report = IcsCodec.parse(ics, term, periods)
        assertTrue(report.courses.isEmpty())
        assertTrue(report.issues.single().reason.contains("对不上"))
    }

    @Test
    fun `日期早于学期开始周进issues`() {
        // 开学前一周的周一
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            SUMMARY:复习
            DTSTART:${dateOf(0, 1, "080000")}
            DTEND:${dateOf(0, 1, "094000")}
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val report = IcsCodec.parse(ics, term, periods)
        assertTrue(report.courses.isEmpty())
        assertTrue(report.issues.single().reason.contains("早于学期"))
    }

    @Test
    fun `缺SUMMARY的事件进issues`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            DTSTART:${dateOf(1, 1, "080000")}
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val report = IcsCodec.parse(ics, term, periods)
        assertTrue(report.courses.isEmpty())
        assertTrue(report.issues.single().reason.contains("SUMMARY"))
    }

    @Test
    fun `DTSTART格式无法识别进issues`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            SUMMARY:X
            DTSTART:下周一
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val report = IcsCodec.parse(ics, term, periods)
        assertTrue(report.courses.isEmpty())
        assertTrue(report.issues.single().reason.contains("无法识别"))
    }

    // ── 行折叠 ────────────────────────────────────────────

    @Test
    fun `超长行按RFC5545折叠_导入时unfold还原`() {
        // 手工构造折行：续行以单个空格开头（RFC 5545 §3.1 内容行折叠）
        val longName = "超级长的课程名称".repeat(12)
        val folded = longName.chunked(15).joinToString("\r\n ")
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\n" +
            "SUMMARY:$folded\r\n" +
            "DTSTART:${dateOf(1, 1, "080000")}\r\nDTEND:${dateOf(1, 1, "094000")}\r\n" +
            "RRULE:FREQ=WEEKLY;COUNT=16\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        assertTrue("续行应当以空格开头", ics.contains("\r\n "))
        val report = IcsCodec.parse(ics, term, periods)
        assertEquals(longName, report.courses.single().name)
    }

    // ── 转义还原顺序（回归） ──────────────────────────────

    @Test
    fun `转义反斜杠后跟n_还原为反斜杠加n而非换行`() {
        // 单遍扫描：`\\`（转义的反斜杠）+ 字面 n 应还原成"反斜杠 + n"两个字符。
        // 旧的链式 replace 把 \\n 排在 \\\\ 之前，会把它误转成换行。
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nSUMMARY:高等数学\r\n" +
            "DTSTART:${dateOf(1, 1, "080000")}\r\nDTEND:${dateOf(1, 1, "094000")}\r\n" +
            "RRULE:FREQ=WEEKLY;COUNT=16\r\n" +
            "DESCRIPTION:A\\\\nB\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        assertEquals("A\\nB", IcsCodec.parse(ics, term, periods).courses.single().teacher)

        // 对照组：单个 \n 仍按换行还原
        val ics2 = ics.replace("A\\\\nB", "A\\nB")
        assertEquals("A\nB", IcsCodec.parse(ics2, term, periods).courses.single().teacher)
    }

    // ── 工具 ──────────────────────────────────────────────

    /** 第 week 周、星期 day(1=周一) 的本地浮动日期时间，如 20260907T080000 */
    private fun dateOf(week: Int, day: Int, hhmmss: String): String {
        val epochDay = com.classschedule.app.util.TermCalendar.epochDayOf(week, day, term.startEpochDay)
        val cal = java.util.Calendar.getInstance().apply {
            timeZone = java.util.TimeZone.getDefault()
            clear()
            timeInMillis = epochDay * 86_400_000L
            // 先按目标日期读出时区偏移，再把时间校正到当地 00:00（对任意时区都成立）
            val offset = get(java.util.Calendar.ZONE_OFFSET) + get(java.util.Calendar.DST_OFFSET)
            timeInMillis = epochDay * 86_400_000L - offset
        }
        val y = cal.get(java.util.Calendar.YEAR)
        val m = cal.get(java.util.Calendar.MONTH) + 1
        val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
        return String.format("%04d%02d%02dT%s", y, m, d, hhmmss)
    }
}
