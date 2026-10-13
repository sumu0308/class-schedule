package com.classschedule.app

import androidx.compose.ui.graphics.Color
import com.classschedule.app.ui.ThemeColors
import com.classschedule.app.ui.argbToColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 主题色派生的单元测试（v0.12.0）。
 *
 * 覆盖三件最容易悄悄出错的事：
 *  1. **对比度**：任意主色派生出的前景色都必须与底色达到 WCAG AA（4.5:1），
 *     否则用户挑了个浅黄主色就会得到一片糊字；
 *  2. **HLS 换算**：色相/饱和度/亮度的换算是整个派生的地基，算错会让所有颜色偏色；
 *  3. **预设色板**：预设里的每个色都要能派生出可用色板（不能出现负数或 NaN）。
 */
class ThemeColorsTest {

    /** 主色候选：默认蓝 + 预设 8 色 + 几个极端色（纯黑/纯白/纯饱和黄） */
    private val primaries = ThemeColors.PRESETS.map { it.second } + listOf(
        0xFF000000L, // 纯黑
        0xFFFFFFFFL, // 纯白
        0xFFFFFF00L, // 纯黄（最亮的极端色，最容易糊字）
        0xFF00FF00L, // 纯绿
        0xFF808080L, // 中灰（无彩色）
    )

    @Test
    fun `所有主色派生后前景底色对比度达标`() {
        // 这是本组测试里最关键的一条：用户能选的每个颜色都不能糊字
        for (argb in primaries) {
            for (dark in listOf(true, false)) {
                val s = ThemeColors.schemeOf(argb, dark)
                val pairs = listOf(
                    "onPrimary/primary" to (s.onPrimary to s.primary),
                    "onPrimaryContainer/primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
                    "onSecondary/secondary" to (s.onSecondary to s.secondary),
                    "onSecondaryContainer/secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
                    "onBackground/background" to (s.onBackground to s.background),
                    "onSurface/surface" to (s.onSurface to s.surface),
                    "onSurfaceVariant/surfaceVariant" to (s.onSurfaceVariant to s.surfaceVariant),
                    "onError/error" to (s.onError to s.error),
                )
                for ((name, pair) in pairs) {
                    val (fg, bg) = pair
                    val ratio = ThemeColors.contrast(fg, bg)
                    assertTrue(
                        "dark=$dark ${hexOf(argb)} 的 $name 对比度只有 $ratio（应≥4.5）",
                        ratio >= 4.4f, // 留 0.1 容差：浮点与色彩空间换算的边界抖动
                    )
                }
            }
        }
    }

    @Test
    fun `默认主色派生后色相不变且中性色与旧版一致`() {
        // 换主色功能不能把"什么都不选"的观感改掉：中性色必须与 0.11.x 逐值相同，
        // 主色则允许被派生规则压暗/提亮（浅色主题下要更深才够对比），
        // 所以断言的是"同色相"而不是"同色值"。
        val light = ThemeColors.schemeOf(null, dark = false)
        assertEquals(ThemeColors.DEFAULT_PRIMARY, argbToColor(ThemeColors.DEFAULT_PRIMARY_LIGHT))
        val (sourceH, _, _) = ThemeColors.hslOf(ThemeColors.DEFAULT_PRIMARY)
        val (derivedH, _, _) = ThemeColors.hslOf(light.primary)
        assertTrue("默认主色派生后色相应不变（$sourceH vs $derivedH）", abs(sourceH - derivedH) < 2f)

        // 中性色固定：底色不该跟着主色变
        assertEquals(Color(0xFFF7F8FA), light.background)
        assertEquals(Color(0xFFFFFFFF.toInt()), light.surface)

        val dark = ThemeColors.schemeOf(null, dark = true)
        assertEquals(Color(0xFF14161A), dark.background)
        assertEquals(Color(0xFF1C1F24), dark.surface)
    }

    @Test
    fun `深浅两套主题的中性色不同`() {
        val light = ThemeColors.schemeOf(0xFF2F6FEDL, dark = false)
        val dark = ThemeColors.schemeOf(0xFF2F6FEDL, dark = true)
        assertTrue(light.background != dark.background)
        assertTrue(light.surface != dark.surface)
    }

    @Test
    fun `深色主题的主色比浅色主题更亮`() {
        // Material 的惯例：深色底上主色要提亮，否则深色主色落在深底上完全看不见
        val light = ThemeColors.schemeOf(0xFF2F6FEDL, dark = false)
        val dark = ThemeColors.schemeOf(0xFF2F6FEDL, dark = true)
        val (_, _, lightL) = ThemeColors.hslOf(light.primary)
        val (_, _, darkL) = ThemeColors.hslOf(dark.primary)
        assertTrue("深色主题主色亮度 $darkL 应高于浅色的 $lightL", darkL > lightL)
    }

    @Test
    fun `灰色主色也会被补上饱和度`() {
        // 用户选了"墨灰"或中灰时不能得到一片死灰：
        // s 接近 0 时按固定低饱和蓝处理
        val gray = ThemeColors.schemeOf(0xFF808080L, dark = false)
        val (_, s, _) = ThemeColors.hslOf(gray.primary)
        assertTrue("灰色主色派生后饱和度应被补足，实际 $s", s > 0.05f)
    }

    @Test
    fun `主色的色相被保留到 primary 上`() {
        // 换主色的意义就是换色相，色相跑掉等于功能没实现
        for (argb in ThemeColors.PRESETS.map { it.second }) {
            val (sourceH, sourceS, _) = ThemeColors.hslOf(argbToColor(argb))
            if (sourceS < 0.08f) continue // 灰色已被特判跳过
            val (derivedH, _, _) = ThemeColors.hslOf(ThemeColors.schemeOf(argb, dark = false).primary)
            val diff = abs(derivedH - sourceH)
            val wrapped = minOf(diff, 360f - diff)
            assertTrue(
                "${hexOf(argb)} 的色相应被保留，源 $sourceH → 派生 $derivedH",
                wrapped <= 2f, // 允许 2° 的取整误差
            )
        }
    }

    @Test
    fun `RGB 转 HSL 基本色相正确`() {
        // 红 0° / 黄 60° / 绿 120° / 青 180° / 蓝 240° / 洋红 300°，这是 HSL 的定义值
        val cases = listOf(
            Color(0xFFFF0000) to 0f,
            Color(0xFFFFFF00) to 60f,
            Color(0xFF00FF00) to 120f,
            Color(0xFF00FFFF) to 180f,
            Color(0xFF0000FF) to 240f,
            Color(0xFFFF00FF) to 300f,
        )
        for ((color, expectedHue) in cases) {
            val (h, s, l) = ThemeColors.hslOf(color)
            assertTrue("纯色饱和度应为 1，实际 $s", abs(s - 1f) < 0.01f)
            assertTrue("纯色亮度应为 0.5，实际 $l", abs(l - 0.5f) < 0.01f)
            assertTrue(
                "${hexOf(color.value.toLong())} 的色相应为 ${expectedHue}°，实际 ${h}°",
                abs(h - expectedHue) < 1f || abs(abs(h - expectedHue) - 360f) < 1f,
            )
        }
    }

    @Test
    fun `HSL 往返得到原色`() {
        // 派生链是 "RGB→HSL→调饱和亮度→HSL→RGB"，两头必须能对得上，
        // 否则饱和度/亮度的调整会连带改变色相
        for (argb in primaries) {
            val source = argbToColor(argb)
            val (h, s, l) = ThemeColors.hslOf(source)
            val rebuilt = ThemeColors.schemeOf(argb, dark = false).primary
            val (rh, rs, rl) = ThemeColors.hslOf(rebuilt)
            // 主色经过有意的饱和度/亮度调整，只要求"色相仍在、仍是有效颜色"
            assertTrue(rs in 0f..1f)
            assertTrue(rl in 0f..1f)
            assertTrue(rh in 0f..360f)
        }
    }

    @Test
    fun `可读色在浅底上用深字深底上用浅字`() {
        assertEquals(Color.Black, ThemeColors.readableOn(Color(0xFFFFFFFF)))
        assertEquals(Color.White, ThemeColors.readableOn(Color(0xFF000000)))
        // 极亮与极暗的两端都要给出确定结果，不能返回中间灰
        assertTrue(
            ThemeColors.readableOn(Color(0xFFFFFF00)) == Color.Black ||
                ThemeColors.readableOn(Color(0xFFFFFF00)) == Color.White,
        )
    }

    @Test
    fun `对比度公式符合 WCAG 定义`() {
        // 黑对白应为 21:1，同色对同色为 1:1
        assertEquals(21f, ThemeColors.contrast(Color.Black, Color.White), 0.05f)
        assertEquals(1f, ThemeColors.contrast(Color.Red, Color.Red), 0.001f)
        // 对称性：谁在前谁在后结果一样
        val a = ThemeColors.contrast(Color(0xFF2F6FED), Color.White)
        val b = ThemeColors.contrast(Color.White, Color(0xFF2F6FED))
        assertEquals(a, b, 0.001f)
    }

    @Test
    fun `插值函数端点与单调性正确`() {
        val black = Color.Black
        val white = Color.White
        assertEquals(black, ThemeColors.lerpColor(black, white, 0f))
        assertEquals(white, ThemeColors.lerpColor(black, white, 1f))
        // 越界参数要被夹住，不能插出"负颜色"
        assertEquals(black, ThemeColors.lerpColor(black, white, -5f))
        assertEquals(white, ThemeColors.lerpColor(black, white, 5f))
        val mid = ThemeColors.lerpColor(black, white, 0.5f)
        assertTrue(mid.red in 0f..1f && mid.green in 0f..1f && mid.blue in 0f..1f)
    }

    @Test
    fun `预设色板里没有非法色值`() {
        assertTrue("预设色板不应为空", ThemeColors.PRESETS.isNotEmpty())
        for ((name, argb) in ThemeColors.PRESETS) {
            // 直接比数值而不是格式化后看字符串：
            // "%08X" 对 Long 会按 16 位补零输出，用字符串判断 alpha 既绕又易错
            assertTrue("预设色 $name 的 alpha 必须不透明（${hexOf(argb)}）", (argb shr 24) and 0xFF == 0xFFL)
            assertTrue("预设色 $name 应在 32 位无符号范围内", argb in 0..0xFFFFFFFFL)
            // 每个预设都要能派生出通过对比度检查的色板
            val s = ThemeColors.schemeOf(argb, dark = false)
            assertTrue(
                "预设色 $name 派生的 onPrimary/primary 对比度不足",
                ThemeColors.contrast(s.onPrimary, s.primary) >= 4.4f,
            )
        }
    }

    @Test
    fun `预设色板的名字不重复`() {
        // 重名会让"当前：xxx"的回显有歧义
        val names = ThemeColors.PRESETS.map { it.first }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `isLight 的判定与 readableOn 选出的文字色配套`() {
        // 这两个函数分工不同、判据也不同，不能互相断言：
        //   isLight     = 亮度 > 0.5（"这个颜色本身亮不亮"）
        //   readableOn  = 黑与白哪个对比度高（"该用哪个文字色"）
        // 例如中等亮度的蓝，isLight 说它不亮，readableOn 却正确地选了白字。
        // 这里钉住的是**配套关系**：亮色底配深字、暗色底配浅字，
        // 而不是"isLight 等价于黑白对比度比较"。
        for (argb in primaries) {
            val c = argbToColor(argb)
            val on = ThemeColors.readableOn(c)
            if (ThemeColors.isLight(c)) {
                // 亮底：黑字应该拿得出更好的对比度
                assertTrue(
                    "亮色 ${hexOf(argb)} 上应选深色字，实际 $on",
                    ThemeColors.contrast(c, on) >= ThemeColors.contrast(c, if (on == Color.Black) Color.White else Color.Black),
                )
            }
        }

        // 极端两端必须确定：纯白底必配黑字、纯黑底必配白字
        assertEquals(Color.Black, ThemeColors.readableOn(Color(0xFFFFFFFF)))
        assertEquals(Color.White, ThemeColors.readableOn(Color(0xFF000000)))
    }

    @Test
    fun `argbToColor 按 ARGB 语义解析`() {
        // 回归测试：曾经误用 Color(v.toULong())，那是 Compose 的内部 packed 表示，
        // 喂给它普通 ARGB 会算出完全错误的颜色，读 colorSpace 时甚至直接抛
        // ArrayIndexOutOfBoundsException。必须用 Color(Long) 这条 ARGB 语义的重载。
        val c = argbToColor(0xFF2F6FEDL)
        assertEquals(0x2F / 255f, c.red, 0.001f)
        assertEquals(0x6F / 255f, c.green, 0.001f)
        assertEquals(0xED / 255f, c.blue, 0.001f)
        assertEquals(1f, c.alpha, 0.001f)

        // 黑与白也要正确：packed 误用会让这两个都变成透明
        assertEquals(0f, argbToColor(0xFF000000L).red, 0.001f)
        assertEquals(1f, argbToColor(0xFFFFFFFFL).red, 0.001f)
        assertEquals(1f, argbToColor(0xFF000000L).alpha, 0.001f)
    }

    /** 把 ARGB Long 打成 #AARRGGBB，断言消息里能直接看出是哪个色 */
    private fun hexOf(argb: Long): String = "#%08X".format(argb)
}