package com.classschedule.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
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
    val showWeekend by vm.showWeekend.collectAsStateWithLifecycle()
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
    val dayCount = if (showWeekend) 7 else 5
    val periodCount = periods.size.coerceAtLeast(1)

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
                        )
                        Text(
                            text = weekSubtitle(t, week),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (!vm.isViewingCurrentWeek()) {
                        TextButton(onClick = { vm.goToCurrentWeek() }) {
                            Text("本周")
                        }
                    }
                    TextButton(onClick = { vm.toggleWeekend() }) {
                        Text(if (showWeekend) "5 天" else "7 天")
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
            WeekSwitcher(
                week = week,
                totalWeeks = t?.totalWeeks ?: 20,
                isCurrent = vm.isViewingCurrentWeek(),
                onPrev = { vm.stepWeek(-1) },
                onNext = { vm.stepWeek(1) },
                onToday = { vm.goToCurrentWeek() },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

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

/** 顶部周次切换条 */
@Composable
private fun WeekSwitcher(
    week: Int,
    totalWeeks: Int,
    isCurrent: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev, enabled = week > 1) {
            ChevronIcon(pointsLeft = true)
        }
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "第 $week 周",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (isCurrent) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "本周",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        IconButton(onClick = onNext, enabled = week < totalWeeks) {
            ChevronIcon(pointsLeft = false)
        }
    }
}

/**
 * 自绘的左右箭头图标。
 * 不用 material-icons-extended 是为了少一个依赖，也少一份下载失败的风险。
 */
@Composable
private fun ChevronIcon(pointsLeft: Boolean) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(modifier = Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            if (pointsLeft) {
                moveTo(w * 0.65f, h * 0.18f)
                lineTo(w * 0.32f, h * 0.5f)
                lineTo(w * 0.65f, h * 0.82f)
            } else {
                moveTo(w * 0.35f, h * 0.18f)
                lineTo(w * 0.68f, h * 0.5f)
                lineTo(w * 0.35f, h * 0.82f)
            }
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = w * 0.11f, cap = StrokeCap.Round),
        )
    }
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

/** 判断当前是不是深色配色 */
private fun androidx.compose.ui.graphics.Color.luminanceOf(): Float =
    0.299f * red + 0.587f * green + 0.114f * blue
