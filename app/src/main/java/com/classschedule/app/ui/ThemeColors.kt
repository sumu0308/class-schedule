package com.classschedule.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
     * ARGB 数值 → [Color]。
 *
 * **必须用 [Color] 的 Long 重载，不能写 `Color(v.toULong())`**：
 * 后者是 Compose 的内部 packed 表示（把色板 id 打包进高位），
 * 喂给它一个普通 ARGB 会算出完全错误的颜色，读 `colorSpace` 时甚至直接
 * 抛 ArrayIndexOutOfBoundsException。这点与 `Course.colorArgb` 不同——
 * 那个字段存的是 `Color.value.toLong()` 的 packed 值，靠往返自洽；
 * 本类的 [Appearance.primaryArgb] 存的是**语义上的 ARGB**，两者不可混用。
 */
fun argbToColor(argb: Long): Color = Color(argb)

/**
 * 从单个主色派生出一整套 Material 3 色板（v0.12.0）。
 *
 * 只让用户挑一个颜色，其余十几种颜色（onPrimary、primaryContainer、surface……）
 * 全部由它算出来。否则就得让用户逐个填色板，那不是"个性化"是"配色编辑器"。
 *
 * 派生规则（HSL 空间里做，亮度用固定步长而非比例，行为可预期）：
 *   - 深色主题：主色提亮到 l≈0.78，容器色再压暗一档
 *   - 浅色主题：主色压暗到 l≈0.46，容器色调淡
 *   - 前景色（文字）一律按**实际对比度**选黑或白，而不是按亮度猜
 *
 * 文字色的选取是这里唯一有技术含量的地方：如果主色偏亮（黄色系），
 * 白字就糊了；如果主色偏暗（深蓝），黑字也糊了。用 WCAG 相对亮度公式
 * 比较黑与白的对比度，取高者，并对结果做一次"够不够 4.5:1"的自检，
 * 达不到就继续朝反方向拉。这条规则同时被 ThemeColorsTest 钉住。
 */
object ThemeColors {

    /** WCAG 相对亮度阈值：高于它视为浅色 */
    private const val LIGHT_THRESHOLD = 0.5f

    /**
     * 正文与背景的最低对比度（WCAG AA 对正文的门槛）。
     * 达不到就把前景继续推向另一端，直到达标。
     */
    private const val MIN_CONTRAST = 4.5f

    /** 一整套派生出来的颜色。全部是最终值，UI 直接用，不再二次加工。 */
    data class Scheme(
        val primary: Color,
        val onPrimary: Color,
        val primaryContainer: Color,
        val onPrimaryContainer: Color,
        val secondary: Color,
        val onSecondary: Color,
        val secondaryContainer: Color,
        val onSecondaryContainer: Color,
        val background: Color,
        val onBackground: Color,
        val surface: Color,
        val onSurface: Color,
        val surfaceVariant: Color,
        val onSurfaceVariant: Color,
        val outline: Color,
        val error: Color,
        val onError: Color,
    )

    /**
     * 由主色派生色板。
     *
     * @param primaryArgb 用户选的主色；null 时用内置默认蓝
     * @param dark 是否深色主题
     */
    fun schemeOf(primaryArgb: Long?, dark: Boolean): Scheme {
        val base = primaryArgb?.let { argbToColor(it) } ?: DEFAULT_PRIMARY
        // 自己做 RGB→HSL，不用 android.graphics.Color.colorToHSL：
        // 后者是 Android API，纯 JVM 单元测试里只会抛 Stub 异常，
        // 那样整个派生逻辑就没法被测试覆盖（而它恰恰是最该测的部分）
        val (h, s, l) = hslOf(base)

        // 灰度主色（s≈0）单独处理：把饱和度按比例放大后仍是灰的，
        // 不如直接给一个固定的低饱和蓝，否则用户会得到一片没有颜色的界面
        val usableS = if (s < 0.08f) 0.45f else s

        val primary = if (dark) {
            hslColor(h, usableS * 0.82f, 0.78f)
        } else {
            hslColor(h, usableS * 0.86f, 0.46f)
        }
        val primaryContainer = if (dark) {
            hslColor(h, usableS * 0.55f, 0.30f)
        } else {
            hslColor(h, usableS * 0.55f, 0.88f)
        }

        // 次色取主色色相偏移 30°：视觉上同族但能区分，Material 的 secondary 本就如此
        val secondaryHue = (h + 30f) % 360f
        val secondary = if (dark) {
            hslColor(secondaryHue, usableS * 0.5f, 0.74f)
        } else {
            hslColor(secondaryHue, usableS * 0.5f, 0.42f)
        }
        val secondaryContainer = if (dark) {
            hslColor(secondaryHue, usableS * 0.4f, 0.28f)
        } else {
            hslColor(secondaryHue, usableS * 0.45f, 0.90f)
        }

        // 中性色阶固定，不跟主色走：界面的底色应该保持安静，
        // 一旦底色也被主色染色，大面积色块会晃眼、课表颜色块反而不够突出
        val background = if (dark) Color(0xFF14161A) else Color(0xFFF7F8FA)
        val surface = if (dark) Color(0xFF1C1F24) else Color(0xFFFFFFFF.toInt())
        val surfaceVariant = if (dark) Color(0xFF2A2E34) else Color(0xFFEDF0F4)
        val outline = if (dark) Color(0xFF454B53) else Color(0xFFC6CCD4)
        val error = if (dark) Color(0xFFFF8A85) else Color(0xFFD9534F)

        return Scheme(
            primary = primary,
            onPrimary = readableOn(primary),
            primaryContainer = primaryContainer,
            onPrimaryContainer = readableOn(primaryContainer),
            secondary = secondary,
            onSecondary = readableOn(secondary),
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = readableOn(secondaryContainer),
            background = background,
            onBackground = readableOn(background),
            surface = surface,
            onSurface = readableOn(surface),
            surfaceVariant = surfaceVariant,
            // surfaceVariant 是浅/深中性色，黑白都够用；仍走同一函数保证口径唯一
            onSurfaceVariant = readableOn(surfaceVariant),
            outline = outline,
            error = error,
            onError = readableOn(error),
        )
    }

    /**
     * 选一个在 [background] 上读得清的前景色。
     *
     * 先比黑与白的对比度取高者；若高者仍不足 [MIN_CONTRAST]（极端色如纯正黄
     * 对白实际约 1.07:1，但对黑有 18:1，所以正常不会走到这里），
     * 就把背景朝反方向压/提，直到达标。留这道兜底是为了"任何主色都不会糊"。
     */
    fun readableOn(background: Color): Color {
        val onWhite = contrast(background, Color.White)
        val onBlack = contrast(background, Color.Black)
        val best = if (onWhite >= onBlack) Color.White else Color.Black
        if (maxOf(onWhite, onBlack) >= MIN_CONTRAST) return best

        // 兜底：把背景压到接近纯黑或纯白（极端饱和色才触发）
        val target = if (onWhite >= onBlack) Color.Black else Color.White
        var adjusted = background
        var guard = 0
        while (contrast(adjusted, target) < MIN_CONTRAST && guard < 16) {
            // 每轮向目标色靠近 20%，最多 16 轮足够走完全程
            adjusted = lerpColor(adjusted, target, 0.2f)
            guard++
        }
        return target
    }

    /** WCAG 对比度：(L1+0.05) / (L2+0.05)，结果范围 1..21 */
    fun contrast(a: Color, b: Color): Float {
        val l1 = a.luminance()
        val l2 = b.luminance()
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    /** 两个颜色按比例线性插值，t=0 取 a、t=1 取 b */
    fun lerpColor(a: Color, b: Color, t: Float): Color {
        val f = t.coerceIn(0f, 1f)
        return Color(
            red = a.red + (b.red - a.red) * f,
            green = a.green + (b.green - a.green) * f,
            blue = a.blue + (b.blue - a.blue) * f,
            alpha = a.alpha + (b.alpha - a.alpha) * f,
        )
    }

    /**
     * 相对亮度是否够亮（用于判断要不要切深色文字）。
     * 与 [contrast] 共用 WCAG 亮度，保证两处口径一致。
     */
    fun isLight(color: Color): Boolean = color.luminance() > LIGHT_THRESHOLD

    /**
     * RGB → HSL，返回 (色相 0..360, 饱和度 0..1, 亮度 0..1)。
     *
     * 纯算术实现，无 Android 依赖，可直接被单元测试覆盖。
     * 灰色（R=G=B）时饱和度为 0、色相取 0，调用方需自行处理"给无彩色补饱和度"。
     */
    fun hslOf(color: Color): Triple<Float, Float, Float> {
        val r = color.red
        val g = color.green
        val b = color.blue
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min

        val l = (max + min) / 2f
        if (delta == 0f) return Triple(0f, 0f, l)

        val s = if (l > 0.5f) delta / (2f - max - min) else delta / (max + min)
        val h = when (max) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        return Triple(if (h < 0f) h + 360f else h, s, l)
    }

    /** HSV/HSL 的 h 用 0..360、s/l 用 0..1，构造 Color */
    private fun hslColor(hue: Float, saturation: Float, lightness: Float): Color {
        val h = ((hue % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val l = lightness.coerceIn(0f, 1f)
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val hp = h / 60f
        val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
        val (r1, g1, b1) = when (hp.toInt().coerceIn(0, 5)) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = l - c / 2f
        return Color(
            red = (r1 + m).coerceIn(0f, 1f),
            green = (g1 + m).coerceIn(0f, 1f),
            blue = (b1 + m).coerceIn(0f, 1f),
            alpha = 1f,
        )
    }

    /** 内置默认主色：与 0.11.x 的 primary 同值，换算成 ARGB Long */
    val DEFAULT_PRIMARY: Color = Color(0xFF2F6FED)

    val DEFAULT_PRIMARY_LIGHT: Long = 0xFF2F6FEDL
    val DEFAULT_PRIMARY_DARK: Long = 0xFF8AB0FFL

    /** 设置页给用户挑的颜色：都是常见、好认、彼此差别大的色系 */
    val PRESETS: List<Pair<String, Long>> = listOf(
        "经典蓝" to DEFAULT_PRIMARY_LIGHT,
        "青碧" to 0xFF0F9D8FL,
        "松绿" to 0xFF2E9E5BL,
        "秋橙" to 0xFFE07A2FL,
        "砖红" to 0xFFD9534FL,
        "玫紫" to 0xFF9C5BC7L,
        "藕粉" to 0xFFD76A94L,
        "墨灰" to 0xFF4E5969L,
    )
}