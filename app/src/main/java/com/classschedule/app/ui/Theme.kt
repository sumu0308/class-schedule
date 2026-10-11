package com.classschedule.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 主题色板：主色蓝，配一套克制的灰阶，保证课表颜色块是界面里最跳的元素 */
private val AppLightColors = lightColorScheme(
    primary = Color(0xFF2F6FED),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF11275C),
    secondary = Color(0xFF4E8C5A),
    onSecondary = Color.White,
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF1B1D21),
    surface = Color.White,
    onSurface = Color(0xFF1B1D21),
    surfaceVariant = Color(0xFFEDF0F4),
    onSurfaceVariant = Color(0xFF5A6169),
    outline = Color(0xFFC6CCD4),
    error = Color(0xFFD9534F),
)

private val AppDarkColors = darkColorScheme(
    primary = Color(0xFF8AB0FF),
    onPrimary = Color(0xFF0E1B36),
    primaryContainer = Color(0xFF23407A),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFF8CC79A),
    onSecondary = Color(0xFF10240F),
    background = Color(0xFF14161A),
    onBackground = Color(0xFFE6E8EB),
    surface = Color(0xFF1C1F24),
    onSurface = Color(0xFFE6E8EB),
    surfaceVariant = Color(0xFF2A2E34),
    onSurfaceVariant = Color(0xFFB4BAC2),
    outline = Color(0xFF454B53),
    error = Color(0xFFFF8A85),
)

@Composable
fun ClassScheduleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) AppDarkColors else AppLightColors,
        content = content,
    )
}
