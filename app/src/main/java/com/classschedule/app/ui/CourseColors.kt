package com.classschedule.app.ui

import androidx.compose.ui.graphics.Color

/**
 * 课程配色：按课程名哈希自动分配，保证同一门课每次颜色一致；
 * 用户手动指定颜色时以用户选择为准。
 */
object CourseColors {

    /** 预设色板：明度适中，保证白字与黑字都能看清 */
    val palette: List<Color> = listOf(
        Color(0xFF4C7DF0), // 蓝
        Color(0xFF2FA37C), // 绿
        Color(0xFFE0803A), // 橙
        Color(0xFFB563D6), // 紫
        Color(0xFFD9534F), // 红
        Color(0xFF3AA0C4), // 青
        Color(0xFF8A9A2B), // 橄榄
        Color(0xFFC2557E), // 玫红
        Color(0xFF6B7FD1), // 蓝紫
        Color(0xFF4E8C5A), // 深绿
    )

    /** 根据课程名取一个稳定的颜色 */
    fun forName(name: String): Color {
        if (name.isEmpty()) return palette.first()
        var hash = 7
        for (ch in name) hash = hash * 31 + ch.code
        return palette[Math.floorMod(hash, palette.size)]
    }
}
