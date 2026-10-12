package com.classschedule.app.ui

import androidx.compose.ui.graphics.Color

/**
 * 课程配色：按课程名哈希自动分配，保证同一门课每次颜色一致；
 * 用户手动指定颜色时以用户选择为准。
 */
object CourseColors {

    /** 预设色板：低饱和淡色，冷静不花哨；亮度偏高故统一用深色文字 */
    val palette: List<Color> = listOf(
        Color(0xFFDCE9E3), // 灰绿
        Color(0xFFDCE6F0), // 灰蓝
        Color(0xFFEFE7D8), // 灰沙
        Color(0xFFE7E0EE), // 灰紫
        Color(0xFFD9E4EC), // 蓝灰
        Color(0xFFECE2D6), // 灰棕
        Color(0xFFDCEAEA), // 灰青
        Color(0xFFECDDE2), // 灰玫
        Color(0xFFE2E6DD), // 灰橄榄
        Color(0xFFDFE3EA), // 灰银蓝
    )

    /** 根据课程名取一个稳定的颜色 */
    fun forName(name: String): Color {
        if (name.isEmpty()) return palette.first()
        var hash = 7
        for (ch in name) hash = hash * 31 + ch.code
        return palette[Math.floorMod(hash, palette.size)]
    }
}
