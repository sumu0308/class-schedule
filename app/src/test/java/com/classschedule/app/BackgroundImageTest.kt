package com.classschedule.app

import com.classschedule.app.data.BackgroundImageStore
import com.classschedule.app.ui.centerCropScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 背景图相关的纯函数测试（v0.12.0）。
 *
 * 这两个函数是背景图能不能正常显示的关键：
 *  - [BackgroundImageStore.computeSampleSize] 算错 → 要么图被放大发虚，要么细节全丢；
 *  - [centerCropScale] 取 max 写成 min → 背景填不满、四周露白。
 *
 * 两者都是纯算术，抽出来专门为了能在纯 JVM 单测里钉死。
 */
class BackgroundImageTest {

    // ── 降采样倍数 ────────────────────────────────────────

    @Test
    fun `降采样让最长边不小于目标`() {
        val target = BackgroundImageStore.TARGET_LONG_EDGE

        // 4000×3000 的常见手机大图：/2=2000 ≥1080 可以降；再 /2=1000 <1080 就欠采样了，
        // 停。采样后是 2000×1500——比目标大一倍，但**绝不小于**目标，
        // 否则图会被放大发虚（宁可多存一点像素）
        assertEquals(2, BackgroundImageStore.computeSampleSize(4000, 3000, target))
        // 竖图同理按最长边算
        assertEquals(2, BackgroundImageStore.computeSampleSize(3000, 4000, target))
    }

    @Test
    fun `小于目标尺寸的图不降采样`() {
        // 已经是 800×600，再降就糊了
        assertEquals(1, BackgroundImageStore.computeSampleSize(800, 600, 1080))
        // 恰好等于目标也不降
        assertEquals(1, BackgroundImageStore.computeSampleSize(1080, 1080, 1080))
    }

    @Test
    fun `极端尺寸不崩且结果合理`() {
        // 8K 横图：7680/2=3840≥1080 → 2；/4=1920 ≥1080 → 4；/8=960 <1080 停 → 4
        // （1920 比目标大一圈，是"宁可过采样也不欠采样"的取舍）
        assertEquals(4, BackgroundImageStore.computeSampleSize(7680, 4320, 1080))
        // 4K 手机常见 2160×3840：3840/2=1920≥1080 → 2；/4=960<1080 停 → 2
        assertEquals(2, BackgroundImageStore.computeSampleSize(2160, 3840, 1080))
        // 1×1 的极小图（某些图标 PNG）
        assertEquals(1, BackgroundImageStore.computeSampleSize(1, 1, 1080))
        // 非法输入返回 1（= 不降采样），绝不返回 0 或负数
        assertEquals(1, BackgroundImageStore.computeSampleSize(0, 0, 1080))
        assertEquals(1, BackgroundImageStore.computeSampleSize(-100, 200, 1080))
        assertEquals(1, BackgroundImageStore.computeSampleSize(100, 100, 0))
    }

    @Test
    fun `降采样倍数总是二的幂`() {
        // BitmapFactory 只接受 2 的幂，返 3 会抛 IllegalArgumentException。
        // 判据：正整数且 n & (n-1) == 0
        for (w in listOf(500, 1000, 2000, 4000, 5000, 8000, 12000)) {
            val s = BackgroundImageStore.computeSampleSize(w, w, 1080)
            assertTrue("宽 $w 的采样倍数 $s 必须为正", s > 0)
            assertEquals("宽 $w 的采样倍数应为 2 的幂，实际 $s", 0, s and (s - 1))
        }
    }

    @Test
    fun `降采样后尺寸不小于目标边`() {
        // 算法要求"采样后最长边 ≥ 目标"，否则就是欠采样、图会被放大发虚
        val target = BackgroundImageStore.TARGET_LONG_EDGE
        for ((w, h) in listOf(4000 to 3000, 1080 to 1920, 2000 to 2000, 3000 to 500)) {
            val s = BackgroundImageStore.computeSampleSize(w, h, target)
            val longEdge = maxOf(w, h) / s
            assertTrue(
                "${w}×$h 降采样 $s 后最长边 $longEdge 小于目标 $target（欠采样）",
                longEdge >= target,
            )
        }
    }

    // ── centerCrop 缩放系数 ───────────────────────────────

    @Test
    fun `centerCrop 取较大的缩放以盖满目标区`() {
        // 横图放进竖区：按高铺满（高度决定系数），宽度溢出被裁掉
        // 1000×500 的图放进 400×800：400/1000=0.4、800/500=1.6 → 取 1.6
        assertEquals(1.6f, centerCropScale(1000, 500, 400f, 800f), 0.001f)

        // 竖图放进同比例区域：两个方向比值相同，等比缩放
        // 500×1000 放进 400×800：两者都是 0.8 → 取 0.8
        assertEquals(0.8f, centerCropScale(500, 1000, 400f, 800f), 0.001f)

        // 竖图放进窄横区：按宽铺满
        // 500×1000 放进 300×200：300/500=0.6、200/1000=0.2 → 取 0.6
        assertEquals(0.6f, centerCropScale(500, 1000, 300f, 200f), 0.001f)
    }

    @Test
    fun `同比例图缩放系数为 1 或恰好等比`() {
        // 正方形进正方形：等比缩小到目标（centerCrop 取 max 后就是同比例的缩放）
        assertEquals(0.5f, centerCropScale(1000, 1000, 500f, 500f), 0.001f)
        // 同比例放大
        assertEquals(2f, centerCropScale(500, 500, 1000f, 1000f), 0.001f)
        // 常见手机竖屏比例 1080×1920 → 411×731，两方向比值相同 → 0.3805
        assertEquals(411f / 1080f, centerCropScale(1080, 1920, 411f, 731f), 0.001f)
    }

    @Test
    fun `非法尺寸回落到 1 不崩`() {
        assertEquals(1f, centerCropScale(0, 100, 400f, 800f), 0.001f)
        assertEquals(1f, centerCropScale(100, 0, 400f, 800f), 0.001f)
        assertEquals(1f, centerCropScale(100, 100, 0f, 800f), 0.001f)
        assertEquals(1f, centerCropScale(100, 100, 400f, 0f), 0.001f)
    }

    @Test
    fun `缩放后必定盖满目标区的两个方向`() {
        // 这是centerCrop 的定义：宽和高都不小于目标区，否则会露白边
        val cases = listOf(
            CropCase(1000, 500, 400f, 800f),
            CropCase(500, 1000, 400f, 800f),
            CropCase(2000, 2000, 360f, 640f),
            CropCase(1920, 1080, 411f, 731f),
        )
        for (c in cases) {
            val scale = centerCropScale(c.srcW, c.srcH, c.dstW, c.dstH)
            assertTrue(
                "${c.srcW}×${c.srcH} 缩放 $scale 后宽 ${c.srcW * scale} 未盖满 ${c.dstW}",
                c.srcW * scale >= c.dstW,
            )
            assertTrue(
                "${c.srcW}×${c.srcH} 缩放 $scale 后高 ${c.srcH * scale} 未盖满 ${c.dstH}",
                c.srcH * scale >= c.dstH,
            )
        }
    }

    @Test
    fun `居中偏移算出来是正负对称的`() {
        // 缩放比只保证"盖满"，不保证不裁得太过；这里钉住裁切量对称，
        // 保证背景图在屏幕上是居中的而不是偏在一角。
        // 渲染时 left = (dstW - srcW*scale)/2，为负值表示左侧被裁掉多少。
        val c = CropCase(1000, 500, 400f, 800f)
        val scale = centerCropScale(c.srcW, c.srcH, c.dstW, c.dstH)
        val left = (c.dstW - c.srcW * scale) / 2f
        val top = (c.dstH - c.srcH * scale) / 2f
        // 本例：宽按 1.6 倍后 1600 > 400 → 左右各裁 600；高正好 800 → 无纵向裁切
        assertEquals(-600f, left, 0.001f)
        assertEquals(0f, top, 0.001f)
    }

    /** centerCrop 用例：源图尺寸 → 目标区尺寸 */
    private data class CropCase(
        val srcW: Int,
        val srcH: Int,
        val dstW: Float,
        val dstH: Float,
    )
}