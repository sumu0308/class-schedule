package com.classschedule.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.classschedule.app.data.Appearance
import com.classschedule.app.data.ThemeMode

/**
 * 课表主题（v0.12.0 起由 [Appearance] 驱动）。
 *
 * 色板不再写死两套常量，而是由用户选的**主色**经 [ThemeColors.schemeOf] 派生：
 * 深浅两套中性色（background / surface / outline）保持固定，
 * 只有 primary 这一族跟着主色走，error 也用固定的语义红
 * ——错误提示用主色系的颜色会削弱"这是错误"的语义。
 *
 * [ThemeColors.readableOn] 保证每个"X 在 X 上"的前景/背景组合
 * 对比度达标，因此任意主色都不会出现糊字。
 */
@Composable
fun ClassScheduleTheme(
    appearance: Appearance = Appearance.DEFAULT,
    darkTheme: Boolean = appearance.themeMode.resolveDark(isSystemInDarkTheme()),
    content: @Composable () -> Unit,
) {
    val scheme = ThemeColors.schemeOf(appearance.primaryArgb, darkTheme)
    MaterialTheme(
        colorScheme = if (darkTheme) {
            darkColorScheme(
                primary = scheme.primary,
                onPrimary = scheme.onPrimary,
                primaryContainer = scheme.primaryContainer,
                onPrimaryContainer = scheme.onPrimaryContainer,
                secondary = scheme.secondary,
                onSecondary = scheme.onSecondary,
                secondaryContainer = scheme.secondaryContainer,
                onSecondaryContainer = scheme.onSecondaryContainer,
                background = scheme.background,
                onBackground = scheme.onBackground,
                surface = scheme.surface,
                onSurface = scheme.onSurface,
                surfaceVariant = scheme.surfaceVariant,
                onSurfaceVariant = scheme.onSurfaceVariant,
                outline = scheme.outline,
                error = scheme.error,
                onError = scheme.onError,
            )
        } else {
            lightColorScheme(
                primary = scheme.primary,
                onPrimary = scheme.onPrimary,
                primaryContainer = scheme.primaryContainer,
                onPrimaryContainer = scheme.onPrimaryContainer,
                secondary = scheme.secondary,
                onSecondary = scheme.onSecondary,
                secondaryContainer = scheme.secondaryContainer,
                onSecondaryContainer = scheme.onSecondaryContainer,
                background = scheme.background,
                onBackground = scheme.onBackground,
                surface = scheme.surface,
                onSurface = scheme.onSurface,
                surfaceVariant = scheme.surfaceVariant,
                onSurfaceVariant = scheme.onSurfaceVariant,
                outline = scheme.outline,
                error = scheme.error,
                onError = scheme.onError,
            )
        },
        content = content,
    )
}

/**
 * 三态解析成实际的深/浅。
 *
 * 抽成顶层函数是为了能在纯 JVM 单测里验证（[isSystemInDarkTheme] 是
 * Composable 只能在真机/Compose 测试里调，而"跟随系统"这条映射规则本身值得钉住）。
 */
fun ThemeMode.resolveDark(systemInDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemInDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** 保留旧签名里的默认主色引用，避免 CourseColors 之类的地方重复写死色值 */
internal val DefaultPrimaryColor: Color = ThemeColors.DEFAULT_PRIMARY