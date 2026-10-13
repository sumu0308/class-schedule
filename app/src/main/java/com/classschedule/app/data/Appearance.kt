package com.classschedule.app.data

/**
 * 主题模式：跟随系统 / 常亮 / 常暗。
 *
 * 为什么不直接存 boolean `darkTheme`：三态里"跟随系统"不是任何一种固定取值，
 * 用两个布尔量（dark + followSystem）迟早会出现自相矛盾的组合。
 * 枚举让非法状态在类型层面就不存在。
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    companion object {
        /** 宽容解析：认不出的名字一律回退到 [SYSTEM]，不抛异常 */
        fun fromNameOrDefault(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/** 主题模式的中文名，供提示文案与设置页选项复用 */
fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
}

/**
 * 全局外观配置（v0.12.0）。
 *
 * 只放"整个 App 一份"的偏好，不按学期分——课表界面本身是全局的，
 * 按学期切主题只会让用户困惑（"为什么换了学期界面颜色就变了"）。
 *
 * @param themeMode 主题模式
 * @param primaryArgb 自定义主色（ARGB，存成 Long 避免 Int 符号位问题）；null 表示用内置默认蓝
 * @param backgroundPath 自定义背景图的**相对路径**（相对 `filesDir/appearance/`）；null 表示用纯色
 * @param backgroundDim 背景蒙版浓度 0..1：盖在背景图上的半透明层，用来保证课表文字可读。
 *   0 = 不加蒙版（只适合纯色底），1 = 完全盖住背景图
 */
data class Appearance(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val primaryArgb: Long? = null,
    val backgroundPath: String? = null,
    val backgroundDim: Float = DEFAULT_DIM,
) {
    /** 背景图配置是否可用：路径非空**且**文件确实还在（用户清过应用数据的情况） */
    fun hasBackground(): Boolean = !backgroundPath.isNullOrBlank()

    companion object {
        val DEFAULT = Appearance()

        /**
         * 默认蒙版浓度。
         *
         * 0.35 是权衡值：背景图太亮时课表格线与文字会糊掉，太暗则背景图等于没有。
         * 侧边栏「外观」里可调，用户自己那张图太亮或太暗都能救回来。
         */
        const val DEFAULT_DIM = 0.35f
    }
}

/**
 * [Appearance] 与字符串之间的编解码，存进已有的 `app_setting` 键值表。
 *
 * 为什么编码成一个字符串而不是拆成多个键：
 * 主题模式、主色、背景图是**一次选择、一起生效**的一组配置。拆成多个键会出现
 * "模式已切到深色、主色还没写进去"的中间态，读出来就是半新半旧的配色。
 * 单键整体替换没有中间态。
 *
 * 格式（竖线分隔，字段可缺省）：
 * ```
 * 主题模式|主色十进制|背景图相对路径|蒙版浓度‰
 * SYSTEM||                 | 350
 * DARK|4294901760|wall.jpg|400
 * ```
 * 刻意不用 JSON：字段都是标量，MiniJson 只有解析没有序列化，
 * 为几个字段手搓一个序列化器不值得；分隔符格式一眼能看懂，也能直接手工改库调试。
 *
 * **向后兼容**：0.12.0 首发时只有前两段，解码器按位取、缺的用默认值补，
 * 所以老用户升级后外观配置不会丢（计划文档 11.2）。
 *
 * 蒙版浓度用千分数（‰）存而不是浮点文本：避免 `0.35` / `0.35000000000000003`
 * 这类浮点字符串往返误差，也让库里存的值一眼能读。
 *
 * 解码必须宽容（见 [decode]）：这份数据来自数据库，跨版本升级、用户清数据、
 * 手改文件都可能留下脏值，一条脏配置不该让整个 App 打不开。
 */
object AppearanceCodec {

    private const val SEP = '|'

    /** 合法主色的取值范围：0x00000000..0xFFFFFFFF（ARGB，无符号） */
    private const val MAX_COLOR = 0xFFFFFFFFL

    fun encode(appearance: Appearance): String = listOf(
        appearance.themeMode.name,
        appearance.primaryArgb?.toString().orEmpty(),
        appearance.backgroundPath.orEmpty(),
        (appearance.backgroundDim.coerceIn(0f, 1f) * 1000).toInt().toString(),
    ).joinToString(SEP.toString())

    /**
     * 解码。任何异常输入都回退到默认值，而不是抛异常——
     * 外观配置坏了最多是"颜色没生效"，不该连带课表都打不开。
     *
     * 覆盖的脏值：null / 空串 / 无分隔符 / 未知模式名 / 非数字 /
     * 负数 / 超过 32 位 / 蒙版浓度越界 / 背景图路径含 `..`（目录穿越）。
     *
     * 背景图路径额外做**目录穿越校验**：它最终会被拼到 `filesDir` 下再读文件，
     * 一个从数据库读出来的 `../../databases/xxx` 就不该被照着去读。
     */
    fun decode(raw: String?): Appearance {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return Appearance.DEFAULT

        val parts = text.split(SEP)
        // 没有分隔符：整体当作模式名，认不出就是默认值
        if (parts.size == 1) {
            return Appearance(themeMode = ThemeMode.fromNameOrDefault(parts[0].trim()))
        }

        val mode = ThemeMode.fromNameOrDefault(parts[0].trim())

        // 空色 = 明确"用默认主色"，与 null 同义
        val color = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?.toLongOrNull()?.takeIf { it in 0..MAX_COLOR }

        val path = parts.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() && isSafePath(it) }

        // 缺失或非法都回落到默认浓度，不影响其它字段
        val dim = parts.getOrNull(3)?.trim()?.toIntOrNull()
            ?.let { (it / 1000f).coerceIn(0f, 1f) }
            ?: Appearance.DEFAULT_DIM

        // 逐字段独立兜底：色值非法只丢颜色、背景路径非法只丢路径，其余照常生效
        return Appearance(
            themeMode = mode,
            primaryArgb = color,
            backgroundPath = path,
            backgroundDim = dim,
        )
    }

    /**
     * 背景图相对路径的安全校验（纯函数，单测覆盖）。
     *
     * 只允许"文件名"或"文件名.jpg"这种形态：不含分隔符、不含 `..`、不是绝对路径。
     * 我们自己生成的文件名形如 `bg_1736899200000.jpg`，足够表达需求，
     * 不需要放开子目录。
     *
     * 字符白名单**显式限定 ASCII**：Kotlin 的 `Char.isLetterOrDigit()` 对中文
     * 也返回 true，直接用它会把"名字.jpg"放行——而文件名是本 App 自己生成的，
     * 不可能含中文，放行非 ASCII 只会让白名单形同虚设。
     */
    fun isSafePath(path: String): Boolean {
        if (path.isEmpty()) return false
        if (path.length > 128) return false // 文件名不该这么长，挡掉畸形输入
        if (path.contains("..")) return false
        if (path.contains('/') || path.contains('\\')) return false
        if (path.startsWith(".")) return false
        return path.all { c ->
            (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') ||
                c == '.' || c == '_' || c == '-'
        }
    }
}