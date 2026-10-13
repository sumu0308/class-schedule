package com.classschedule.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.classschedule.app.data.Appearance
import com.classschedule.app.data.ThemeMode
import com.classschedule.app.data.label

/**
 * 外观设置弹窗（v0.12.0）。
 *
 * 设计取向：**点一下就生效，不设"应用"按钮**。颜色是那种"改完要退出去看"
 * 就失去意义的东西——弹窗本身是半透明的、背景就是当前主题，
 * 用户在弹窗里选色的过程中界面已经在变，所见即所得。
 * 落库是异步的（VM 里先改内存再写库），失败会由消息栏告知。
 *
 * 只放主题模式与主色两项。背景图、卡片透明度、字号密度等按需再加，
 * 一次性铺满会让这个弹窗变成"设置中心"，反而不像个性化。
 */
@Composable
fun AppearanceDialog(
    visible: Boolean,
    appearance: Appearance,
    onThemeModeChange: (ThemeMode) -> Unit,
    onPrimaryChange: (Long?) -> Unit,
    onPickBackground: () -> Unit,
    onClearBackground: () -> Unit,
    onDimChange: (Float) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("外观", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // ── 主题模式 ──
                SectionLabel("主题模式")
                ThemeMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = appearance.themeMode == mode,
                                onClick = { onThemeModeChange(mode) },
                            )
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = appearance.themeMode == mode,
                            onClick = { onThemeModeChange(mode) },
                        )
                        Text(
                            text = mode.label() + if (mode == ThemeMode.SYSTEM) "（与手机的深浅色设置一致）" else "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                SectionLabel("主色")
                Text(
                    text = "点一下立即生效，其他界面颜色会自动跟着协调。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // ── 主色：默认 + 预设色板 ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ColorDot(
                        color = ThemeColors.DEFAULT_PRIMARY,
                        selected = appearance.primaryArgb == null,
                        onClick = { onPrimaryChange(null) },
                        contentDescription = "默认蓝",
                    )
                    for ((name, argb) in ThemeColors.PRESETS) {
                        ColorDot(
                            color = argbToColor(argb),
                            selected = appearance.primaryArgb == argb,
                            onClick = { onPrimaryChange(argb) },
                            contentDescription = name,
                        )
                    }
                }

                // 选中色的名字回显，避免只靠颜色块判断"我现在选的是哪个"
                Text(
                    text = "当前：" + currentColorName(appearance),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // ── 背景图 ──
                Spacer(Modifier.height(4.dp))
                SectionLabel("背景图")
                Text(
                    text = if (appearance.hasBackground()) {
                        "已使用自定义背景。可重新选择，或恢复纯色。"
                    } else {
                        "从相册选一张图片作为课表背景。图片只存在本机，不会上传。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onPickBackground) {
                        Text(if (appearance.hasBackground()) "更换" else "选择图片")
                    }
                    if (appearance.hasBackground()) {
                        TextButton(onClick = onClearBackground) { Text("恢复纯色") }
                    }
                }

                // 蒙版浓度只在有背景图时才有意义（纯色底叠蒙版没有效果）
                if (appearance.hasBackground()) {
                    Text(
                        text = "背景明暗 ${(appearance.backgroundDim * 100).toInt()}%" +
                            "（背景太亮导致文字看不清时调高）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = appearance.backgroundDim.coerceIn(0f, 1f),
                        onValueChange = onDimChange,
                        valueRange = 0f..0.9f,
                    )
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    text = "提示：课表里课程卡片的颜色仍按课程名自动分配，" +
                        "在课程编辑里可以单独指定，不受主色影响。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = {
            TextButton(onClick = {
                onReset()
                onDismiss()
            }) { Text("恢复默认") }
        },
    )
}

/** 当前的主色名，用于回显 */
private fun currentColorName(appearance: Appearance): String {
    val argb = appearance.primaryArgb ?: return "默认蓝"
    return ThemeColors.PRESETS.firstOrNull { it.second == argb }?.first ?: "自定义"
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/**
 * 一个圆形色块。选中态用 2 dp 主色描边 + 略微放大表示，
 * 与 [CourseEditor] 里课程颜色的交互保持一致（那边也是描边 + 圆点）。
 */
@Composable
private fun ColorDot(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
) {
    Box(
        modifier = Modifier
            .size(if (selected) 30.dp else 26.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (selected) 3.dp else 0.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}