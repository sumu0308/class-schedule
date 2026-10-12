package com.classschedule.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import com.classschedule.app.CourseEditState
import com.classschedule.app.data.CourseRepository
import com.classschedule.app.data.Period
import com.classschedule.app.data.WeekParity
import com.classschedule.app.util.TermCalendar

/**
 * 课程新增 / 编辑弹窗。
 *
 * 交互取舍：所有字段一屏可见、不做分步向导 —— 手工录一门课的操作次数是核心体验指标
 * （计划文档 G3：≤ 30 秒、≤ 8 次点击）。因此常用项都给了快捷按钮。
 */
@Composable
fun CourseEditorDialog(
    state: CourseEditState,
    periods: List<Period>,
    totalWeeks: Int,
    onUpdate: ((CourseEditState) -> CourseEditState) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    if (!state.visible) return
    val scroll = rememberScrollState()
    // 范围上界取"节次的最大序号"而非条数：删掉中间一节后 index 会有断层
    // （如 {1,2,4,…,8}），按条数取会让显示值与落库值对不上。
    val maxPeriod = periods.maxOfOrNull { it.index } ?: 1

    // 大节分组：课程编辑里用"第 N 大节"做快捷选择
    val majorBlocks = remember(periods) {
        CourseRepository.groupIntoBlocks(periods)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (state.isNew) "添加课程" else "编辑课程",
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = { v -> onUpdate { it.copy(name = v) } },
                    label = { Text("课程名（必填）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.teacher,
                        onValueChange = { v -> onUpdate { it.copy(teacher = v) } },
                        label = { Text("教师") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = state.room,
                        onValueChange = { v -> onUpdate { it.copy(room = v) } },
                        label = { Text("教室") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                // ── 星期 ──
                SectionLabel("星期")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (d in 1..7) {
                        val selected = state.dayOfWeek == d
                        FilterChip(
                            selected = selected,
                            onClick = { onUpdate { it.copy(dayOfWeek = d) } },
                            label = { Text(TermCalendar.dayShort(d)) },
                        )
                    }
                }

                // ── 节次 ──
                SectionLabel("节次")
                // 常用选择：一门课通常正好是一个大节（两个小节）
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    for ((i, block) in majorBlocks.withIndex()) {
                        val from = block.first()
                        val to = block.last()
                        val selected = state.startPeriod == from && state.endPeriod == to
                        FilterChip(
                            selected = selected,
                            onClick = {
                                onUpdate { it.copy(startPeriod = from, endPeriod = to) }
                            },
                            label = { Text("第 ${i + 1} 大节") },
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NumberPicker(
                        label = "从",
                        value = state.startPeriod,
                        range = 1..maxPeriod,
                        onChange = { v ->
                            onUpdate {
                                it.copy(startPeriod = v, endPeriod = maxOf(v, it.endPeriod))
                            }
                        },
                    )
                    NumberPicker(
                        label = "到",
                        value = state.endPeriod,
                        range = state.startPeriod..maxPeriod,
                        onChange = { v -> onUpdate { it.copy(endPeriod = v) } },
                    )
                }
                Text(
                    text = "共占 ${state.endPeriod - state.startPeriod + 1} 小节" +
                        blockHint(state.startPeriod, state.endPeriod, majorBlocks),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // ── 单双周 ──
                SectionLabel("单双周")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ParityChip("每周", WeekParity.EVERY, state, onUpdate)
                    ParityChip("单周", WeekParity.ODD, state, onUpdate)
                    ParityChip("双周", WeekParity.EVEN, state, onUpdate)
                }

                // ── 周次 ──
                SectionLabel("周次")
                OutlinedTextField(
                    value = state.weekExpr,
                    onValueChange = { v -> onUpdate { it.copy(weekExpr = v) } },
                    label = { Text("周次范围") },
                    placeholder = { Text("如 1-16 或 1-8,10-16") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(
                        onClick = { onUpdate { it.copy(weekExpr = "1-$totalWeeks") } },
                        label = { Text("全学期") },
                    )
                    AssistChip(
                        onClick = { onUpdate { it.copy(weekExpr = "1-8") } },
                        label = { Text("1-8 周") } ,
                    )
                    AssistChip(
                        onClick = { onUpdate { it.copy(weekExpr = "9-$totalWeeks") } },
                        label = { Text("9 周起") },
                    )
                }

                // ── 颜色 ──
                SectionLabel("颜色（留空按课程名自动分配）")
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(state.colorArgb ?: CourseColors.forName(state.name).value.toLong()))
                            .border(
                                width = if (state.colorArgb == null) 2.dp else 0.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape,
                            )
                            .clickable { onUpdate { it.copy(colorArgb = null) } },
                    )
                    for (c in CourseColors.palette.take(6)) {
                        val argb = c.value.toLong()
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(
                                    width = if (state.colorArgb == argb) 2.dp else 0.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = CircleShape,
                                )
                                .clickable { onUpdate { it.copy(colorArgb = argb) } },
                        )
                    }
                }

                if (state.error != null) {
                    Text(
                        text = state.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (!state.isNew) {
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
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
 * 节次选择的人类可读提示：
 *   正好一个大节 → "（第 2 大节）"
 *   跨大节       → "（跨第 2-3 大节）"
 *   不属于任何完整大节 → 留空，避免给出误导性描述
 */
private fun blockHint(from: Int, to: Int, blocks: List<List<Int>>): String {
    if (blocks.isEmpty()) return ""
    val hit = blocks.withIndex().filter { (_, b) -> b.any { it in from..to } }
    if (hit.isEmpty()) return ""
    val first = hit.first().index + 1
    val last = hit.last().index + 1
    val exact = hit.size == 1 && hit.first().value.first() == from && hit.first().value.last() == to
    return when {
        exact -> "（第 $first 大节）"
        first == last -> "（第 $first 大节的一部分）"
        else -> "（跨第 $first-$last 大节）"
    }
}

@Composable
private fun ParityChip(
    label: String,
    parity: WeekParity,
    state: CourseEditState,
    onUpdate: ((CourseEditState) -> CourseEditState) -> Unit,
) {
    FilterChip(
        selected = state.parity == parity,
        onClick = { onUpdate { it.copy(parity = parity) } },
        label = { Text(label) },
    )
}

/** 简易数字选择器：左右加减 + 当前值，比下拉菜单少一次点击 */
@Composable
private fun NumberPicker(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(4.dp))
        TextButton(
            onClick = { if (value > range.first) onChange(value - 1) },
            enabled = value > range.first,
        ) { Text("−") }
        Text(
            text = "$value",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        TextButton(
            onClick = { if (value < range.last) onChange(value + 1) },
            enabled = value < range.last,
        ) { Text("+") }
    }
}
