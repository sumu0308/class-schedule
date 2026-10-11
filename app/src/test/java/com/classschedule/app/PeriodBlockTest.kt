package com.classschedule.app

import com.classschedule.app.data.CourseRepository
import com.classschedule.app.data.Period
import com.classschedule.app.util.GridClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 大节分组的单元测试。
 *
 * 背景：作息是「一天 4 个大节，每个大节含 2 个小节，小节之间休息 20 分钟」。
 * 用户在界面上想的是"第 N 大节"，而数据层存的是小节下标，两者靠这个分组对应起来。
 */
class PeriodBlockTest {

    /** 与默认模板一致的 8 小节作息 */
    private fun defaultPeriods(): List<Period> = listOf(
        Period(id = 1, termId = 1, index = 1, startTime = "08:00", endTime = "08:40"),
        Period(id = 2, termId = 1, index = 2, startTime = "09:00", endTime = "09:40"),
        Period(id = 3, termId = 1, index = 3, startTime = "10:00", endTime = "10:40"),
        Period(id = 4, termId = 1, index = 4, startTime = "11:00", endTime = "11:40"),
        Period(id = 5, termId = 1, index = 5, startTime = "14:30", endTime = "15:10"),
        Period(id = 6, termId = 1, index = 6, startTime = "15:30", endTime = "16:10"),
        Period(id = 7, termId = 1, index = 7, startTime = "16:30", endTime = "17:10"),
        Period(id = 8, termId = 1, index = 8, startTime = "17:30", endTime = "18:10"),
    )

    @Test
    fun `默认作息分成 4 个大节`() {
        val blocks = CourseRepository.groupIntoBlocks(defaultPeriods())
        assertEquals(4, blocks.size)
    }

    @Test
    fun `每个大节含两个小节且下标正确`() {
        val blocks = CourseRepository.groupIntoBlocks(defaultPeriods())
        assertEquals(listOf(1, 2), blocks[0])
        assertEquals(listOf(3, 4), blocks[1])
        assertEquals(listOf(5, 6), blocks[2])
        assertEquals(listOf(7, 8), blocks[3])
    }

    @Test
    fun `小节之间的 20 分钟休息不会被当成跨大节`() {
        val blocks = CourseRepository.groupIntoBlocks(defaultPeriods())
        assertTrue("第 1、2 小节必须同属第一大节", blocks[0].containsAll(listOf(1, 2)))
        assertEquals("默认每大节 2 小节时应该正好 4 个大节", 4, blocks.size)
    }

    @Test
    fun `小节休息实测数据：七个小节间隔全是 20 分钟，另有午休 170 分钟`() {
        // 这条测试把"为什么按结构配对而不是按时间间隔切分大节"钉住。
        // 实测：8:40→9:00 / 9:40→10:00 / 10:40→11:00 / 15:10→15:30 / 16:10→16:30 / 17:10→17:30 都是 20 分钟，
        // 唯独 11:40→14:30（午休）是 170 分钟。
        // 也就是说 9:40→10:00 这个"看起来像跨大节"的边界，间隔和别处完全一样，无法靠时间识别。
        // 若哪天作息改成跨大节有明显更长间隔，这条会失败，提醒我们重新评估分组策略。
        val ps = defaultPeriods()
        val gaps = (1 until ps.size).map { i ->
            GridClock.minutesOf(ps[i].startTime) - GridClock.minutesOf(ps[i - 1].endTime)
        }
        assertEquals(listOf(20, 20, 20, 170, 20, 20, 20), gaps)

        val withinBlockGaps = gaps.filter { it < 100 }
        assertEquals("除午休外，其余间隔必须完全一致", 1, withinBlockGaps.distinct().size)
        assertEquals(20, withinBlockGaps.distinct().first())
    }

    @Test
    fun `每大节小节数可配置`() {
        // 若将来学校改成"三个小节一个大节"，只需要改这个参数
        assertEquals(3, CourseRepository.groupIntoBlocks(defaultPeriods(), periodsPerBlock = 3).size)
        assertEquals(1, CourseRepository.groupIntoBlocks(defaultPeriods(), periodsPerBlock = 8).size)
        // 非法值不应崩溃
        assertEquals(8, CourseRepository.groupIntoBlocks(defaultPeriods(), periodsPerBlock = 0).size)
    }

    @Test
    fun `午休把上下午分开，但不影响大节个数`() {
        val blocks = CourseRepository.groupIntoBlocks(defaultPeriods())
        // 上午最后一节（4）与下午第一节（5）确实不在同一大节，但原因是结构配对而非午休
        assertTrue(blocks.none { it.contains(4) && it.contains(5) })
        assertEquals(listOf(1, 2), blocks[0])
        assertEquals(listOf(3, 4), blocks[1])
        assertEquals(listOf(5, 6), blocks[2])
        assertEquals(listOf(7, 8), blocks[3])
    }

    @Test
    fun `连续节次（无间隔）算同一大节`() {
        val contiguous = listOf(
            Period(id = 1, termId = 1, index = 1, startTime = "08:00", endTime = "09:40"),
            Period(id = 2, termId = 1, index = 2, startTime = "09:40", endTime = "11:20"),
        )
        val blocks = CourseRepository.groupIntoBlocks(contiguous)
        assertEquals(1, blocks.size)
        assertEquals(listOf(1, 2), blocks[0])
    }

    @Test
    fun `空节次表返回空分组`() {
        assertTrue(CourseRepository.groupIntoBlocks(emptyList()).isEmpty())
    }

    @Test
    fun `乱序输入的节次也能正确分组`() {
        val shuffled = defaultPeriods().shuffled()
        val blocks = CourseRepository.groupIntoBlocks(shuffled)
        assertEquals(4, blocks.size)
        assertEquals(listOf(1, 2), blocks[0])
        assertEquals(listOf(7, 8), blocks[3])
    }

    @Test
    fun `只有一节时是单独一个大节`() {
        val one = listOf(Period(id = 1, termId = 1, index = 1, startTime = "08:00", endTime = "08:40"))
        val blocks = CourseRepository.groupIntoBlocks(one)
        assertEquals(1, blocks.size)
        assertEquals(listOf(1), blocks[0])
    }
}
