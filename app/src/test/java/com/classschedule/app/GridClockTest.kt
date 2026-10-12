package com.classschedule.app

import com.classschedule.app.util.GridClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间解析的单元测试。
 *
 * 重点盯两种解析的分工：
 *  - minutesOf 宽容，负责把"已经存进去的数据"算成分钟数；
 *  - parseStrict / isValidRange 严格，负责把守"要不要存进去"这道闸。
 * 以前 isValidRange 用的是宽容解析，"08:70" 会被容错成 08:59 后放行，脏数据入库。
 */
class GridClockTest {

    @Test
    fun `严格解析接受合法的 HHMM`() {
        assertEquals(0, GridClock.parseStrict("00:00"))
        assertEquals(480, GridClock.parseStrict("08:00"))
        assertEquals(1439, GridClock.parseStrict("23:59"))
    }

    @Test
    fun `严格解析拒绝各种非法写法`() {
        assertNull(GridClock.parseStrict("24:00")) // 小时越界
        assertNull(GridClock.parseStrict("08:60")) // 分钟越界
        assertNull(GridClock.parseStrict("08:70")) // 宽容解析会容成 08:59，严格不行
        assertNull(GridClock.parseStrict("8:00")) // 未补零
        assertNull(GridClock.parseStrict("0800")) // 没有冒号
        assertNull(GridClock.parseStrict("08:0")) // 位数不够
        assertNull(GridClock.parseStrict("abc"))
        assertNull(GridClock.parseStrict(""))
    }

    @Test
    fun `isValidRange 要求格式合法且起早于止`() {
        assertTrue(GridClock.isValidRange("08:00", "09:40"))
        assertFalse(GridClock.isValidRange("09:40", "08:00"))
        assertFalse(GridClock.isValidRange("08:00", "08:00"))
        assertFalse(GridClock.isValidRange("08:70", "09:40")) // 分钟越界
        assertFalse(GridClock.isValidRange("25:00", "26:00")) // 小时越界
        assertFalse(GridClock.isValidRange("abc", "09:40"))
    }

    @Test
    fun `minutesOf 保持宽容_容错值收敛到当天范围内`() {
        assertEquals(480, GridClock.minutesOf("8:00"))
        assertEquals(1439, GridClock.minutesOf("24:99")) // → 23:59
        assertEquals(0, GridClock.minutesOf("abc")) // 解析失败按 0
    }

    @Test
    fun `formatMinutes  roundtrip 且跨午夜取模`() {
        assertEquals("08:00", GridClock.formatMinutes(480))
        assertEquals("23:50", GridClock.formatMinutes(1430))
        assertEquals("00:10", GridClock.formatMinutes(1450)) // 绕回凌晨
    }

    @Test
    fun `durationText 覆盖三种时长形态`() {
        assertEquals("40 分", GridClock.durationText("08:00", "08:40"))
        assertEquals("1 小时", GridClock.durationText("08:00", "09:00"))
        assertEquals("1 小时 40 分", GridClock.durationText("08:00", "09:40"))
        assertEquals("—", GridClock.durationText("09:40", "08:00"))
    }
}
