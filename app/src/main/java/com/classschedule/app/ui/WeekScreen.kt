package com.classschedule.app.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.classschedule.app.ScheduleViewModel
import com.classschedule.app.data.Term
import com.classschedule.app.util.TermCalendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 侧边栏的功能项（计划文档 10.7）：设置类功能的唯一入口 */
private enum class SidebarItem(val label: String, val icon: ImageVector) {
    TERM("学期设置", Icons.Filled.DateRange),
    PERIODS("节次时间", Icons.AutoMirrored.Filled.List),
    IMPORT_FILE("从文件导入", Icons.Filled.Add),
    IMPORT_PASTE("粘贴文本导入", Icons.Filled.Edit),
}

/**
 * 导入选文件时登记的 MIME（0.11.0 起，I-11）：只放开通配——
 * 按具体类型过滤会让国产 ROM / 文件管理器里大量 CSV 选不中
 * （各 ROM 给 .csv 的 MIME 不统一），xlsx 的 MIME 也不在列表内。
 * 格式完全由内容嗅探决定（计划文档 10.2），选择器不承担过滤职责。
 */
private val IMPORT_MIMES = arrayOf("*/*")

/**
 * 主界面：一周课表。
 *
 * 导航（0.10.0 起，计划文档 10.7）：顶栏 ☰ 打开侧边栏，设置类功能
 * （学期设置 / 节次时间 / 两个导入入口）全部平铺在里面；顶栏只保留
 * 标题（学期名 + 第 N 周下拉）与「今天」。导出功能已按用户决策移除。
 *
 * 手势约定（计划文档 I-2）：
 *   点课程格 = 查看/编辑；长按空格 = 新增；右下角 + 号 = 新增
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
    val courseImport by vm.courseImport.collectAsStateWithLifecycle()
    val jsonImport by vm.jsonImport.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var pasteVisible by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(message = m, actionLabel = "撤销", withDismissAction = true)
        // undoLast：有导入快照就整库恢复，否则撤销单课删除
        if (result == SnackbarResult.ActionPerformed) vm.undoLast()
        vm.consumeMessage()
    }

    // ── SAF：导入选文件（只读，不申请存储权限；导出已移除） ──
    // 0.11.0 起按字节读入：zip 魔数走 XLSX，其余按 UTF-8 文本嗅探（见 VM importFileBytes）
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { readBytes(context, uri) }
            if (bytes == null || bytes.isEmpty()) {
                vm.showMessage("无法读取该文件（可能已损坏或是空文件）")
            } else {
                vm.importFileBytes(bytes)
            }
        }
    }

    val t = term
    val dayCount = 7
    val totalWeeks = t?.totalWeeks?.coerceAtLeast(1) ?: 20
    val currentWeek = vm.currentWeek()
    val onCurrentWeek = week == currentWeek
    // 周次下拉是否展开（第 N 周 点一下就能跳到任意一周）
    var weekMenuOpen by remember { mutableStateOf(false) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                SidebarContent(
                    termName = t?.name ?: "课表",
                    week = week,
                    totalWeeks = totalWeeks,
                    versionName = rememberVersionName(context),
                    onItemClick = { item ->
                        // 先关抽屉再开弹窗，避免两个浮层叠在一起
                        scope.launch { drawerState.close() }
                        when (item) {
                            SidebarItem.TERM -> vm.openTermEdit()
                            SidebarItem.PERIODS -> vm.openPeriodEditor()
                            SidebarItem.IMPORT_FILE -> importLauncher.launch(IMPORT_MIMES)
                            SidebarItem.IMPORT_PASTE -> pasteVisible = true
                        }
                    },
                )
            }
        },
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "打开菜单")
                        }
                    },
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
    )

    PeriodEditorDialog(
        visible = periodEditorVisible,
        periods = periods,
        onDismiss = { vm.closePeriodEditor() },
        onUpdate = { period, start, end -> vm.updatePeriodTime(period, start, end) },
        onAdd = { vm.addPeriod() },
        onDelete = { period -> vm.deletePeriod(period) },
    )

    PasteImportDialog(
        visible = pasteVisible,
        onDismiss = { pasteVisible = false },
        onSubmit = { text ->
            pasteVisible = false
            vm.importPasteText(text)
        },
    )

    CourseImportDialog(
        state = courseImport,
        onStrategyChange = { vm.setImportStrategy(it) },
        onConfirm = { vm.confirmCourseImport() },
        onDismiss = { vm.dismissCourseImport() },
    )

    JsonImportDialog(
        state = jsonImport,
        onConfirm = { vm.confirmJsonImport() },
        onDismiss = { vm.dismissJsonImport() },
    )
}

/**
 * 侧边栏内容（计划文档 10.7）：学期/周次头部 + 四个功能项 + 版本号底栏。
 * ModalDrawerSheet 的内容作用域就是 ColumnScope，Spacer 直接 weight 把版本号压到底部。
 */
@Composable
private fun SidebarContent(
    termName: String,
    week: Int,
    totalWeeks: Int,
    versionName: String,
    onItemClick: (SidebarItem) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(vertical = 12.dp)) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
            Text(
                text = "课表",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = termName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "第 $week 周 · 共 $totalWeeks 周",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        SidebarItem.entries.forEach { item ->
            NavigationDrawerItem(
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(item.label) },
                selected = false,
                onClick = { onItemClick(item) },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        if (versionName.isNotEmpty()) {
            Text(
                text = "版本 $versionName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
    }
}

/** 读已安装的本应用版本名（侧边栏底栏展示）；读不到返回空串 */
@Composable
private fun rememberVersionName(context: Context): String = remember {
    try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
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
                text = "长按课表空白格可以快速添加；也可以点右下角的 + 按钮。\n" +
                    "已有课表文件的话，点左上角 ☰ 从侧边栏导入（ICS / CSV / XLSX / JSON / 粘贴文本）。",
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

/** 读 SAF 选中的文件为字节数组；失败返回 null */
private fun readBytes(context: Context, uri: Uri): ByteArray? = try {
    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
} catch (e: Exception) {
    null
}
