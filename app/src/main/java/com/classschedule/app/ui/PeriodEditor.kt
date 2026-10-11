package com.classschedule.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.classschedule.app.TermEditState
import com.classschedule.app.data.Period
import com.classschedule.app.util.GridClock
import com.classschedule.app.util.TermCalendar

/**
 * 学期设置弹窗。
 *
 * 设计要点（响应用户第一条反馈）：
 *   - 开学日期用系统日历控件精确到年月日，不再只能按 7 天增减
 *   - 只允许选择周一：周次是按周计算的，若开学日不是周一，"第几周"会整体错位
 *   - 「节次时间」入口，可逐节改时间、增删节次
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermEditorDialog(
    state: TermEditState,
    onUpdate: ((TermEditState) -> TermEditState) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onOpenPeriodEditor: () -> Unit,
) {
    if (!state.visible) return
    var weeksText by remember(state.visible, state.totalWeeks) {
        mutableStateOf(state.totalWeeks.toString())
    }
    var showDatePicker by remember { mutableStateOf(false) }

    val startDay = state.startEpochDay
    val isMonday = TermCalendar.dayOfWeek(startDay) == 1

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("学期设置", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = { v -> onUpdate { it.copy(name = v) } },
                    label = { Text("学期名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // ── 开学日期 ──
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "开学日期（第 1 周周一）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = TermCalendar.formatFullDate(startDay) +
                                "  " + TermCalendar.dayName(TermCalendar.dayOfWeek(startDay)),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showDatePicker = true }) {
                            Text("选择日期")
                        }
                        OutlinedButton(
                            onClick = {
                                val today = TermCalendar.todayEpochDay()
                                onUpdate { it.copy(startEpochDay = today - (TermCalendar.dayOfWeek(today) - 1)) }
                            },
                        ) { Text("本周一") }
                    }
                    if (!isMonday) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "⚠ 这天不是周一，建议改为当周周一",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(
                                onClick = {
                                    val fix = startDay - (TermCalendar.dayOfWeek(startDay) - 1)
                                    onUpdate { it.copy(startEpochDay = fix) }
                                },
                            ) { Text("改为周一") }
                        }
                    }
                }

                OutlinedTextField(
                    value = weeksText,
                    onValueChange = { v ->
                        weeksText = v.filter { it.isDigit() }.take(2)
                        weeksText.toIntOrNull()?.let { n -> onUpdate { it.copy(totalWeeks = n) } }
                    },
                    label = { Text("总周数（1-30）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

                // ── 节次时间入口 ──
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "节次时间",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "每天几点上课、每节多长。课表表格的行高按这里的时长成比例显示。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onOpenPeriodEditor) { Text("去设置节次时间") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = startDay * 86_400_000L,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val epochDay = Math.floorDiv(millis, 86_400_000L)
                        onUpdate { it.copy(startEpochDay = epochDay) }
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState, showModeToggle = false)
        }
    }
}

/**
 * 节次时间编辑。
 *
 * 每行是"第 N 节  开始 - 结束"，时间用可点击的输入框，输入 4 位数字自动补冒号
 * （输入 0800 → 08:00），比滑轮快，也不用记格式。
 */
@Composable
fun PeriodEditorDialog(
    visible: Boolean,
    periods: List<Period>,
    onDismiss: () -> Unit,
    onUpdate: (Period, String, String) -> Unit,
    onAdd: () -> Unit,
    onDelete: (Period) -> Unit,
) {
    if (!visible) return
    val scroll = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("节次时间", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "一行 = 一门课。改完立即生效，课表行高会按新时长重新排。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (periods.isEmpty()) {
                    Text(
                        text = "还没有节次，点下面「添加一节」。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                for (p in periods) {
                    var start by remember(p.id, p.startTime) { mutableStateOf(p.startTime) }
                    var end by remember(p.id, p.endTime) { mutableStateOf(p.endTime) }
                    val valid = GridClock.isValidRange(start, end)

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "${p.index}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 2.dp),
                        )
                        TimeField(
                            value = start,
                            label = "开始",
                            isError = !valid,
                            modifier = Modifier.weight(1f),
                        ) { v ->
                            start = v
                            if (GridClock.isValidRange(v, end)) onUpdate(p, v, end)
                        }
                        Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TimeField(
                            value = end,
                            label = "结束",
                            isError = !valid,
                            modifier = Modifier.weight(1f),
                        ) { v ->
                            end = v
                            if (GridClock.isValidRange(start, v)) onUpdate(p, start, v)
                        }
                        TextButton(onClick = { onDelete(p) }) {
                            Text("删", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Text(
                        text = if (valid) {
                            "   ${GridClock.durationText(start, end)}"
                        } else {
                            "   时间格式不对，或结束早于开始（如 08:00 / 09:40）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (valid) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onAdd) { Text("添加一节") }
                TextButton(onClick = onDismiss) { Text("完成") }
            }
        },
    )
}

/** 时间输入框：接受 0800 / 08:00 / 8:00 三种写法，失焦时不校验格式，由外层提示 */
@Composable
private fun TimeField(
    value: String,
    label: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }
            val normalized = when {
                raw.contains(':') || raw.contains('：') -> raw.replace('：', ':').take(5)
                digits.length == 4 -> digits.substring(0, 2) + ":" + digits.substring(2)
                digits.length == 3 -> "0" + digits.substring(0, 1) + ":" + digits.substring(1)
                digits.length <= 2 -> digits
                else -> raw.take(5)
            }
            onValueChange(normalized)
        },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        modifier = modifier,
    )
}
