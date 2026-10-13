package com.classschedule.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.classschedule.app.ui.ClassScheduleTheme
import com.classschedule.app.ui.WeekScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: ScheduleViewModel = viewModel(
                factory = ScheduleViewModel.factory(application),
            )
            // 外观配置要从 ViewModel 拿，且必须在 ClassScheduleTheme **外面** collect：
            // 放进主题内部的话，主题本身不会因为配置变化而重组，
            // 改主色要退回主界面才看得见效果（v0.12.0 踩过这个坑）。
            val appearance by vm.appearance.collectAsStateWithLifecycle()
            ClassScheduleTheme(appearance = appearance) {
                WeekScreen(vm)
            }
        }
    }
}