package com.classschedule.app

import com.classschedule.app.data.Appearance
import com.classschedule.app.data.AppearanceCodec
import com.classschedule.app.data.ThemeMode
import com.classschedule.app.ui.resolveDark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 外观配置（v0.12.0）的单元测试。
 *
 * 重点是两件事：
 *  1. **编解码往返**：存进去再读出来必须一模一样；
 *  2. **宽容解码**：这份数据来自数据库，跨版本升级 / 手改文件都可能留脏值，
 *     一条脏值只该让"颜色没生效"，不该让整个 App 打不开。
 */
class AppearanceCodecTest {

    @Test
    fun `编解码往返保持一致`() {
        val cases = listOf(
            Appearance.DEFAULT,
            Appearance(themeMode = ThemeMode.DARK),
            Appearance(themeMode = ThemeMode.LIGHT, primaryArgb = 0xFF2F6FEDL),
            Appearance(themeMode = ThemeMode.SYSTEM, primaryArgb = 0xFFD9534FL),
            Appearance(themeMode = ThemeMode.DARK, primaryArgb = 0xFF000000L),
            Appearance(themeMode = ThemeMode.LIGHT, primaryArgb = 0xFFFFFFFFL),
        )
        for (original in cases) {
            assertEquals(original, AppearanceCodec.decode(AppearanceCodec.encode(original)))
        }
    }

    @Test
    fun `默认配置编解码后不变`() {
        assertEquals(Appearance.DEFAULT, AppearanceCodec.decode(AppearanceCodec.encode(Appearance.DEFAULT)))
        // 默认就是"跟随系统 + 无自定义主色"
        assertEquals(ThemeMode.SYSTEM, Appearance.DEFAULT.themeMode)
        assertNull(Appearance.DEFAULT.primaryArgb)
    }

    @Test
    fun `空值与空串回退到默认外观`() {
        // 新装 App、用户清过数据，库里就是没有这条记录
        assertEquals(Appearance.DEFAULT, AppearanceCodec.decode(null))
        assertEquals(Appearance.DEFAULT, AppearanceCodec.decode(""))
        assertEquals(Appearance.DEFAULT, AppearanceCodec.decode("   "))
    }

    @Test
    fun `未知主题模式回退到跟随系统但保留主色`() {
        // 降级安装（新版 → 旧版 → 新版）或手改库时会出现认不出的名字；
        // 只丢主题模式、不丢主色，用户至少还拿到一半个性化
        val decoded = AppearanceCodec.decode("NEON|4281298925")
        assertEquals(ThemeMode.SYSTEM, decoded.themeMode)
        assertEquals(0xFF2F6FEDL, decoded.primaryArgb)
    }

    @Test
    fun `非法主色被丢弃但主题模式保留`() {
        for (bad in listOf("LIGHT|abc", "DARK|-1", "DARK|99999999999999999999", "LIGHT|0xFF0000")) {
            val decoded = AppearanceCodec.decode(bad)
            assertNull("非法色值 $bad 应被丢弃", decoded.primaryArgb)
            assertTrue(
                "主题模式不该跟着色值一起丢（输入 $bad）",
                decoded.themeMode == ThemeMode.LIGHT || decoded.themeMode == ThemeMode.DARK,
            )
        }
    }

    @Test
    fun `只有模式没有色值等价于用默认主色`() {
        // "DARK|" 与 "DARK" 应当等价：前者是显式清空，后者是省略
        assertEquals(
            Appearance(themeMode = ThemeMode.DARK, primaryArgb = null),
            AppearanceCodec.decode("DARK|"),
        )
        assertEquals(
            Appearance(themeMode = ThemeMode.DARK, primaryArgb = null),
            AppearanceCodec.decode("DARK"),
        )
    }

    @Test
    fun `没有分隔符时整体当作模式名`() {
        assertEquals(ThemeMode.DARK, AppearanceCodec.decode("DARK").themeMode)
        assertEquals(ThemeMode.SYSTEM, AppearanceCodec.decode("随便什么").themeMode)
    }

    @Test
    fun `分隔符两侧的空格被忽略`() {
        // 手改数据库时很容易在竖线两侧留空格，解码不该因此失灵
        val decoded = AppearanceCodec.decode(" LIGHT | 4281298925 ")
        assertEquals(ThemeMode.LIGHT, decoded.themeMode)
        assertEquals(0xFF2F6FEDL, decoded.primaryArgb)
    }

    @Test
    fun `主题模式解析对认不出的名字宽容`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromNameOrDefault("SYSTEM"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromNameOrDefault("LIGHT"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromNameOrDefault("DARK"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromNameOrDefault("dark")) // 大小写敏感
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromNameOrDefault(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromNameOrDefault(""))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromNameOrDefault("SEPIA"))
    }

    @Test
    fun `主题模式到深浅的映射正确`() {
        // 跟随系统：直接用系统的值
        assertEquals(true, ThemeMode.SYSTEM.resolveDark(systemInDark = true))
        assertEquals(false, ThemeMode.SYSTEM.resolveDark(systemInDark = false))
        // 常亮 / 常暗：忽略系统设置
        assertEquals(false, ThemeMode.LIGHT.resolveDark(systemInDark = true))
        assertEquals(true, ThemeMode.DARK.resolveDark(systemInDark = false))
    }

    @Test
    fun `色值支持边界值且不溢出`() {
        // 0 与 0xFFFFFFFF 是合法边界，不能被当成"非法"丢掉
        assertEquals(0L, AppearanceCodec.decode("LIGHT|0").primaryArgb)
        assertEquals(0xFFFFFFFFL, AppearanceCodec.decode("LIGHT|4294967295").primaryArgb)
        // 超出 32 位就该丢
        assertNull(AppearanceCodec.decode("LIGHT|4294967296").primaryArgb)
    }

    @Test
    fun `编码字符串的分隔符数量固定`() {
        // 编码格式固定为 4 段（3 个竖线），字段可为空但段数不能变——
        // 段数漂移会让旧配置被新解析器错位解读
        for (appearance in listOf(
            Appearance(ThemeMode.DARK, 0xFF00FF00L),
            Appearance(ThemeMode.LIGHT, null),
            Appearance(backgroundPath = "wall.jpg", backgroundDim = 0.5f),
            Appearance.DEFAULT,
        )) {
            val encoded = AppearanceCodec.encode(appearance)
            assertEquals(
                "编码段数应恒为 4（3 个分隔符）：$encoded",
                3,
                encoded.count { it == '|' },
            )
        }
    }

    // ── v0.12.0 背景图字段 ──────────────────────────────────

    @Test
    fun `背景图与蒙版浓度往返一致`() {
        val full = Appearance(
            themeMode = ThemeMode.DARK,
            primaryArgb = 0xFF2F6FEDL,
            backgroundPath = "bg_1736899200000.jpg",
            backgroundDim = 0.42f,
        )
        assertEquals(full, AppearanceCodec.decode(AppearanceCodec.encode(full)))
    }

    @Test
    fun `旧格式配置升级后不丢主题与主色`() {
        // 向后兼容：0.12.0 首发时只有两段，"DARK|4281298925" 必须仍能解出原样
        val decoded = AppearanceCodec.decode("DARK|4281298925")
        assertEquals(ThemeMode.DARK, decoded.themeMode)
        assertEquals(0xFF2F6FEDL, decoded.primaryArgb)
        assertNull("旧格式不该凭空长出背景图", decoded.backgroundPath)
        assertEquals(Appearance.DEFAULT_DIM, decoded.backgroundDim, 0.001f)
    }

    @Test
    fun `只有主题模式的旧格式也能解`() {
        val decoded = AppearanceCodec.decode("LIGHT")
        assertEquals(ThemeMode.LIGHT, decoded.themeMode)
        assertNull(decoded.primaryArgb)
        assertEquals(Appearance.DEFAULT_DIM, decoded.backgroundDim, 0.001f)
    }

    @Test
    fun `蒙版浓度非数字时回落到默认值且不影响其它字段`() {
        for (raw in listOf(
            "DARK|4281298925|wall.jpg|abc", // 非数字
            "DARK|4281298925|wall.jpg|",    // 空
            "DARK|4281298925|wall.jpg|null", // 字面量
        )) {
            val decoded = AppearanceCodec.decode(raw)
            assertEquals("主题模式不该受影响（$raw）", ThemeMode.DARK, decoded.themeMode)
            assertEquals("背景图不该受影响（$raw）", "wall.jpg", decoded.backgroundPath)
            assertEquals(
                "浓度应回落到默认（$raw）",
                Appearance.DEFAULT_DIM,
                decoded.backgroundDim,
                0.001f,
            )
        }
    }

    @Test
    fun `蒙版浓度越界时被夹到合法区间而非回落到默认`() {
        // 数值越界说明用户（或旧版本）确实表达过一个"很浓/很淡"的意图，
        // 夹到边界比丢掉这个意图更合理；只有"根本读不出数字"才回落默认
        assertEquals(1f, AppearanceCodec.decode("DARK|||1000").backgroundDim, 0.001f)
        assertEquals(1f, AppearanceCodec.decode("DARK|||99999").backgroundDim, 0.001f)
        assertEquals(0f, AppearanceCodec.decode("DARK|||-100").backgroundDim, 0.001f)
        assertEquals(0f, AppearanceCodec.decode("DARK|||0").backgroundDim, 0.001f)
    }

    @Test
    fun `背景图路径拒绝目录穿越`() {
        // 路径最终会被拼到 filesDir 下再去读文件，目录穿越必须被挡住
        for (bad in listOf(
            "../databases/class_schedule.db",
            "../../shared_prefs/x.xml",
            "sub/dir.jpg",
            "sub\\dir.jpg",
            "/etc/passwd",
            ".hidden.jpg",
            "",
        )) {
            val decoded = AppearanceCodec.decode("DARK||$bad|350")
            assertNull("危险路径应被丢弃：$bad", decoded.backgroundPath)
        }
    }

    @Test
    fun `背景图路径安全校验接受正常文件名`() {
        for (good in listOf("bg_1736899200000.jpg", "wall.png", "a-b_c.jpg", "123.jpg")) {
            assertTrue("正常文件名应通过：$good", AppearanceCodec.isSafePath(good))
        }
    }

    @Test
    fun `路径安全校验拒绝各类畸形输入`() {
        for (bad in listOf(
            "", "..", "../x", "a/b", "a\\b", ".hidden", "a b.jpg", "a;b.jpg",
            "x".repeat(200), "名字.jpg", // 非 ASCII 字母数字一律拒（我们只生成 ASCII 文件名）
        )) {
            assertFalse("畸形路径应被拒绝：$bad", AppearanceCodec.isSafePath(bad))
        }
    }

    @Test
    fun `hasBackground 只看路径是否非空`() {
        assertFalse(Appearance.DEFAULT.hasBackground())
        assertFalse(Appearance(backgroundPath = "").hasBackground())
        assertFalse(Appearance(backgroundPath = "   ").hasBackground())
        assertTrue(Appearance(backgroundPath = "wall.jpg").hasBackground())
    }

    @Test
    fun `蒙版浓度千分制往返误差在一以内`() {
        // 存的是千分数而非浮点文本，往返必然有量化误差，钉住它不超过 1/1000
        for (dim in listOf(0f, 0.001f, 0.35f, 0.42f, 0.5f, 0.75f, 1f)) {
            val decoded = AppearanceCodec.decode(AppearanceCodec.encode(Appearance(backgroundDim = dim)))
            assertTrue(
                "浓度 $dim 往返后应为 $dim，实际 ${decoded.backgroundDim}",
                abs(decoded.backgroundDim - dim) <= 0.001f,
            )
        }
    }
}