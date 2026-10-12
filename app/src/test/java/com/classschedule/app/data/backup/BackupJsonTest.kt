package com.classschedule.app.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSON 整库备份文件的解析测试（计划文档 10.2.1 / 10.5）。
 *
 * 导出功能 0.10.0 已移除，本文件不再做"导出→导入"往返（那需要序列化器，
 * 且对称 bug 会互相抵消）；改用**手工构造的 JSON 文本**验证解析——
 * 既覆盖真实世界可能拿到的文件形态，也没有测试盲区。
 */
class BackupJsonTest {

    /** 手工构造的完整合法备份：两个学期、节次 id 错开、课程含各种边界字段 */
    private val validJson = """
        {
          "format": "class-schedule-backup",
          "version": 1,
          "terms": [
            {"id": 1, "name": "2025 秋", "startEpochDay": 20000, "totalWeeks": 20, "isCurrent": false},
            {"id": 2, "name": "2026 春", "startEpochDay": 20454, "totalWeeks": 22, "isCurrent": true}
          ],
          "periods": [
            {"id": 100, "termId": 2, "index": 1, "startTime": "08:00", "endTime": "08:40"},
            {"id": 101, "termId": 2, "index": 2, "startTime": "09:00", "endTime": "09:40"},
            {"id": 102, "termId": 1, "index": 1, "startTime": "08:00", "endTime": "08:45"}
          ],
          "courses": [
            {"id": 10, "termId": 2, "name": "高等数学", "teacher": "张, 老师", "room": "A101",
             "dayOfWeek": 1, "startPeriodIndex": 1, "periodCount": 2, "weekParity": "EVERY",
             "weekRanges": "1-16", "colorArgb": -1, "note": "带\"引号\"和\\反斜杠", "source": "FILE"},
            {"id": 11, "termId": 2, "name": "大学英语", "dayOfWeek": 3, "startPeriodIndex": 1,
             "periodCount": 1, "weekParity": "ODD", "weekRanges": "1-22"}
          ]
        }
    """.trimIndent()

    // ── 合法输入 ────────────────────────────────────────────

    @Test
    fun `手工构造的合法备份逐字段解析正确`() {
        val data = BackupJson.decode(validJson)

        assertEquals(2, data.terms.size)
        val current = data.terms.single { it.isCurrent }
        assertEquals("2026 春", current.name)
        assertEquals(20454L, current.startEpochDay)
        assertEquals(22, current.totalWeeks)

        assertEquals(3, data.periods.size)
        assertEquals(101L, data.periods[1].id)
        assertEquals("09:00", data.periods[1].startTime)

        assertEquals(2, data.courses.size)
        val math = data.courses[0]
        assertEquals("高等数学", math.name)
        assertEquals("张, 老师", math.teacher) // 逗号在 JSON 里无需转义，原样保留
        assertEquals("A101", math.room)
        assertEquals(1, math.dayOfWeek)
        assertEquals(2, math.periodCount)
        assertEquals("1-16", math.weekRanges)
        assertEquals(-1L, math.colorArgb)
        assertEquals("带\"引号\"和\\反斜杠", math.note) // 转义序列被还原
        assertEquals("FILE", math.source)

        val english = data.courses[1]
        assertEquals(com.classschedule.app.data.WeekParity.ODD, english.weekParity)
        assertNull(english.teacher) // 缺省可选字段
    }

    @Test
    fun `inspect_只解码不写入_返回各表条数`() {
        val p = BackupJson.inspect(validJson)
        assertTrue(p.ok)
        assertEquals(2, p.terms)
        assertEquals(3, p.periods)
        assertEquals(2, p.courses)
        assertNull(p.error)
    }

    // ── isCurrent 不变量归一（回归：缺该字段不会让恢复后课表永久空白） ──

    @Test
    fun `缺isCurrent字段_解码后恰好一个当前学期_取开学最晚的`() {
        // 字段级 REST 语义下第三方工具可能省略 isCurrent；若不归一，
        // 恢复后会查不到当前学期 → 课表永久空白且 App 内无入口找回
        val text = """
            {"format":"class-schedule-backup","version":1,
             "terms":[
               {"id":1,"name":"2025 秋","startEpochDay":20000,"totalWeeks":20},
               {"id":2,"name":"2026 春","startEpochDay":20454,"totalWeeks":20}],
             "periods":[],"courses":[]}
        """.trimIndent()
        val data = BackupJson.decode(text)
        assertEquals(1, data.terms.count { it.isCurrent })
        assertEquals("2026 春", data.terms.single { it.isCurrent }.name)
    }

    @Test
    fun `多个isCurrent_归一为开学最晚的一个`() {
        val text = """
            {"format":"class-schedule-backup","version":1,
             "terms":[
               {"id":1,"name":"A","startEpochDay":20000,"totalWeeks":20,"isCurrent":true},
               {"id":2,"name":"B","startEpochDay":20454,"totalWeeks":20,"isCurrent":true}],
             "periods":[],"courses":[]}
        """.trimIndent()
        val data = BackupJson.decode(text)
        assertEquals(1, data.terms.count { it.isCurrent })
        assertEquals("B", data.terms.single { it.isCurrent }.name)
    }

    @Test
    fun `原本就只有一个当前学期时不被改动`() {
        val data = BackupJson.decode(validJson)
        assertEquals(1, data.terms.count { it.isCurrent })
        assertEquals(2L, data.terms.single { it.isCurrent }.id)
    }

    // ── 畸形输入：有原因、不写库 ────────────────────────────

    private fun assertRejected(text: String, hint: String) {
        val p = BackupJson.inspect(text)
        assertFalse("应当被拒绝：$hint", p.ok)
        assertTrue("错误原因应可读：${p.error}", p.error?.isNotBlank() == true)
    }

    @Test
    fun `空文件被拒绝`() = assertRejected("", "空文件")

    @Test
    fun `根本不是JSON被拒绝`() = assertRejected("这不是备份文件", "纯文本")

    @Test
    fun `format 不是本App备份被拒绝`() =
        assertRejected(
            """{"format":"some-other-app","version":1,"terms":[],"periods":[],"courses":[]}""",
            "别的 App 的备份",
        )

    @Test
    fun `缺少 format 字段被拒绝`() =
        assertRejected("""{"version":1,"terms":[],"periods":[],"courses":[]}""", "缺 format")

    @Test
    fun `version 比App还新被拒绝`() =
        assertRejected(
            """{"format":"class-schedule-backup","version":99,"terms":[],"periods":[],"courses":[]}""",
            "来自未来的备份",
        )

    @Test
    fun `根节点不是对象被拒绝`() =
        assertRejected("""[1,2,3]""", "JSON 数组")

    @Test
    fun `缺 terms 数组被拒绝`() =
        assertRejected(
            """{"format":"class-schedule-backup","version":1,"periods":[],"courses":[]}""",
            "缺 terms",
        )

    @Test
    fun `节次引用不存在的学期被拒绝`() {
        val text = """
            {"format":"class-schedule-backup","version":1,
             "terms":[],
             "periods":[{"id":1,"termId":42,"index":1,"startTime":"08:00","endTime":"08:40"}],
             "courses":[]}
        """.trimIndent()
        assertRejected(text, "悬空 termId")
    }

    @Test
    fun `课程 dayOfWeek 越界被拒绝`() {
        val text = """
            {"format":"class-schedule-backup","version":1,
             "terms":[{"id":1,"name":"T","startEpochDay":20454,"totalWeeks":20,"isCurrent":true}],
             "periods":[],
             "courses":[{"id":1,"termId":1,"name":"X","dayOfWeek":9,"startPeriodIndex":1,"periodCount":1,"weekRanges":"1-20"}]}
        """.trimIndent()
        assertRejected(text, "dayOfWeek=9")
    }

    @Test
    fun `课程名为空被拒绝`() {
        val text = """
            {"format":"class-schedule-backup","version":1,
             "terms":[{"id":1,"name":"T","startEpochDay":20454,"totalWeeks":20,"isCurrent":true}],
             "periods":[],
             "courses":[{"id":1,"termId":1,"name":"  ","dayOfWeek":1,"startPeriodIndex":1,"periodCount":1,"weekRanges":"1-20"}]}
        """.trimIndent()
        assertRejected(text, "空白课程名")
    }

    @Test
    fun `截断的JSON被拒绝`() {
        assertRejected(validJson.substring(0, validJson.length / 2), "写一半的备份")
    }

    @Test
    fun `字段类型不对被拒绝`() {
        // totalWeeks 是字符串而不是数字
        val text = """
            {"format":"class-schedule-backup","version":1,
             "terms":[{"id":1,"name":"T","startEpochDay":20454,"totalWeeks":"20","isCurrent":true}],
             "periods":[],"courses":[]}
        """.trimIndent()
        assertRejected(text, "totalWeeks 类型错")
    }
}
