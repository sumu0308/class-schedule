package com.classschedule.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.classschedule.app.ScheduleViewModel
import com.classschedule.app.data.Term
import com.classschedule.app.util.TermCalendar

/**
 * 主界面：一周课表。
 *
 * 手势约定（计划文档 I-2）：
 *   点课程格 = 查看/编辑；长按空格 = 新增；长按课程格 = 暂无动作（避免误删）
 * 删除入口放在编辑弹窗里，破坏性操作都需要二次确认。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeekScreen(vm: ScheduleViewModel) {
    val term by vm.term.collectAsStateWithLifecycle()
    val courses by vm.courses.collectAsStateWithLifecycle()
    val periods by vm.periods.collectAsStateWithLifecycle()
    val week by vm.week.collectAsStateWithLifecycle()
    val edit by vm.edit.collectAsStateWithLifecycle()
    val termEdit by vm.termEdit.collectAsStateWithLifecycle()
    val periodEditorVisible by vm.periodEditorVisible.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(message = m, actionLabel = "撤销", withDismissAction = true)
        if (result == SnackbarResult.ActionPerformed) vm.undoDelete()
        vm.consumeMessage()
    }

    val t = term
    val dayCount = 7
    val totalWeeks = t?.totalWeeks?.coerceAtLeast(1) ?: 20
    val currentWeek = vm.currentWeek()
    val onCurrentWeek = week == currentWeek
    // 周次下拉是否展开（第 N 周 点一下就能跳到任意一周）
    var weekMenuOpen by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                title = {
                    Column {
                        Text(
                            text = t?.name ?: "课表",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 「第 N 周」本身是个下拉框：点开就是整学期的周次列表，
                            // 比"左右箭头 + 整行周次条"省下一整行高度留给课表。
                            Box {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { weekMenuOpen = true }
                                        .padding(end = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "第 $week 周",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Icon(
                                        imageVector = Icons.Filled.KeyboardArrowDown,
                                        contentDescription = "选择周次",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                DropdownMenu(
                                    expanded = weekMenuOpen,
                                    onDismissRequest = { weekMenuOpen = false },
                                ) {
                                    for (w in 1..totalWeeks) {
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = "第 $w 周",
                                                        fontWeight = if (w == week) {
                                                            FontWeight.Bold
                                                        } else {
                                                            FontWeight.Normal
                                                        },
                                                        color = if (w == week) {
                                                            MaterialTheme.colorScheme.primary
                                                        } else {
                                                            MaterialTheme.colorScheme.onSurface
                                                        },
                                                    )
                                                    Spacer(Modifier.width(10.dp))
                                                    Text(
                                                        text = weekRangeText(t, w),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            },
                                            trailingIcon = {
                                                if (w == currentWeek) {
                                                    Text(
                                                        text = "本周",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.primary,
                                                    )
                                                }
                                            },
                                            onClick = {
                                                weekMenuOpen = false
                                                vm.goToWeek(w)
                                            },
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = weekSubtitle(t, week),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    TextButton(onClick = { vm.goToCurrentWeek() }) {
                        Text(
                            text = "今天",
                            fontWeight = if (onCurrentWeek) FontWeight.Normal else FontWeight.SemiBold,
                            color = if (onCurrentWeek) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                    TextButton(onClick = { vm.openTermEdit() }) {
                        Text("学期")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                val today = TermCalendar.dayOfWeek(TermCalendar.todayEpochDay())
                vm.startAdd(today.coerceAtMost(dayCount), 1)
            }) {
                Icon(Icons.Filled.Add, contentDescription = "添加课程")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (courses.isEmpty()) {
                EmptyHint(
                    onAdd = {
                        val today = TermCalendar.dayOfWeek(TermCalendar.todayEpochDay())
                        vm.startAdd(today.coerceAtMost(dayCount), 1)
                    },
                )
            } else {
                ScheduleGrid(
                    courses = courses,
                    week = week,
                    dayCount = dayCount,
                    periods = periods,
                    isDark = MaterialTheme.colorScheme.background.luminanceOf() < 0.5f,
                    term = t,
                    onCourseTap = { course -> vm.startEdit(course) },
                    onEmptyLongPress = { day, period -> vm.startAdd(day, period) },
                )
            }
        }
    }

    CourseEditorDialog(
        state = edit,
        periods = periods,
        totalWeeks = t?.totalWeeks ?: 20,
        onUpdate = { transform -> vm.updateEdit(transform) },
        onDismiss = { vm.dismissEdit() },
        onSave = { vm.saveEdit() },
        onDelete = { vm.deleteEditing() },
    )

    TermEditorDialog(
        state = termEdit,
        onUpdate = { transform -> vm.updateTermEdit(transform) },
        onDismiss = { vm.dismissTermEdit() },
        onSave = { vm.saveTermEdit() },
        onOpenPeriodEditor = {
            vm.dismissTermEdit()
            vm.openPeriodEditor()
        },
    )

    PeriodEditorDialog(
        visible = periodEditorVisible,
        periods = periods,
        onDismiss = { vm.closePeriodEditor() },
        onUpdate = { period, start, end -> vm.updatePeriodTime(period, start, end) },
        onAdd = { vm.addPeriod() },
        onDelete = { period -> vm.deletePeriod(period) },
    )
}

/** 空状态：明确告诉用户下一步能做什么，不留死胡同 */
@Composable
private fun EmptyHint(onAdd: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                text = "这个学期还没有课程",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "长按课表空白格可以快速添加；也可以点右下角的 + 按钮。\n教务系统导入功能在后续版本加入。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(4.dp))
            TextButton(onClick = onAdd) { Text("现在添加一门课") }
        }
    }
}

/** 顶栏副标题：第 N 周 对应的日期范围 */
private fun weekSubtitle(term: Term?, week: Int): String {
    if (term == null) return "正在准备数据…"
    val monday = TermCalendar.mondayOfWeek(week, term.startEpochDay)
    val sunday = monday + 6
    return "${TermCalendar.formatMonthDay(monday)} - ${TermCalendar.formatMonthDay(sunday)} · 共 ${term.totalWeeks} 周"
}

/** 周次下拉里每一周对应的日期范围，如 "10/12 - 10/18" */
private fun weekRangeText(term: Term?, week: Int): String {
    if (term == null) return ""
    val monday = TermCalendar.mondayOfWeek(week, term.startEpochDay)
    return "${TermCalendar.formatShort(monday)} - ${TermCalendar.formatShort(monday + 6)}"
}

/** 判断当前是不是深色配色 */
private fun androidx.compose.ui.graphics.Color.luminanceOf(): Float =
    0.299f * red + 0.587f * green + 0.114f * blue
