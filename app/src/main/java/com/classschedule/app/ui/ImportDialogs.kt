package com.classschedule.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.classschedule.app.CourseImportState
import com.classschedule.app.JsonImportState
import com.classschedule.app.data.backup.ImportStrategy

/**
 * 导入相关的三个弹窗（计划文档第十章；导出功能 0.10.0 已移除）。
 *
 * 入口在主页侧边栏（10.7）：「从文件导入」走 SAF 选文件，
 * 「粘贴文本导入」走 [PasteImportDialog]；解析结果进
 * [CourseImportDialog]（ICS / CSV / 粘贴）或 [JsonImportDialog]（JSON 整库恢复）。
 */

/**
 * 粘贴文本导入（计划文档 10.2.4）。
 * 语法：课程名 [教师] [教室] 星期 节次 [周次] [单双周]，教师教室可用 - 占位。
 */
@Composable
fun PasteImportDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    if (!visible) return
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("粘贴课表文本", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "每行一门课：课程名 教师 教室 星期 节次 周次 单双周\n" +
                        "教师、教室可用 - 占位，# 开头的行会被忽略。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("课表文本") },
                    placeholder = {
                        Text("高等数学 张老师 A101 周一 1-2 1-16周\n大学英语 - B203 周三 3-4")
                    },
                    minLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSubmit(text)
                    text = ""
                },
                enabled = text.isNotBlank(),
            ) { Text("解析并预览") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 课程级导入预览（计划文档 10.3）。
 *
 * 确认前展示：将新增 / 将跳过条数（随策略实时重算）、未识别行清单（逐行原因）。
 * 坏行不拖死好行——issues 只提示，合法行照常导入。
 */
@Composable
fun CourseImportDialog(
    state: CourseImportState,
    onStrategyChange: (ImportStrategy) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!state.visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入${state.format}", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "将新增 ${state.plannedInsert} 门课程" +
                        if (state.plannedSkip > 0) "，跳过已存在的 ${state.plannedSkip} 门" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                // ── 写入策略 ──
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    StrategyOption(
                        selected = state.strategy == ImportStrategy.APPEND,
                        title = "追加到本学期",
                        desc = "解析出的课程全部作为新课程加入",
                        onSelect = { onStrategyChange(ImportStrategy.APPEND) },
                    )
                    StrategyOption(
                        selected = state.strategy == ImportStrategy.REPLACE_TERM,
                        title = "覆盖本学期",
                        desc = "先删除本学期全部课程再导入（单事务，失败自动回滚）",
                        onSelect = { onStrategyChange(ImportStrategy.REPLACE_TERM) },
                    )
                    StrategyOption(
                        selected = state.strategy == ImportStrategy.MERGE,
                        title = "合并去重",
                        desc = "按「课程名 + 星期 + 起始节次」跳过已存在的，其余导入",
                        onSelect = { onStrategyChange(ImportStrategy.MERGE) },
                    )
                }

                // ── 未识别行 ──
                if (state.issues.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    Text(
                        text = "未识别行 ${state.issues.size} 条（不会被导入）：",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Column(
                        modifier = Modifier
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        state.issues.take(20).forEach { issue ->
                            Text(
                                text = "${issue.location}：${issue.reason}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.issues.size > 20) {
                            Text(
                                text = "……其余 ${state.issues.size - 20} 条从略",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = state.canConfirm) { Text("确认导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun StrategyOption(
    selected: Boolean,
    title: String,
    desc: String,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * JSON 整库恢复的二次确认（计划文档 10.2.1）。
 * 导入前会先快照当前数据，成功后消息栏显示期间可一键撤销。
 */
@Composable
fun JsonImportDialog(
    state: JsonImportState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!state.visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("恢复 JSON 备份", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.error != null) {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = "请确认选的是本 App 的备份文件（class-schedule-backup-*.json）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "备份内容：${state.terms} 个学期 / ${state.periods} 个节次 / " +
                            "${state.courses} 门课程",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "恢复将**整库覆盖**当前所有数据（当前数据会先自动留一份撤销快照，" +
                            "导入成功后可在消息栏点「撤销」恢复）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = state.canConfirm) { Text("确认恢复") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
