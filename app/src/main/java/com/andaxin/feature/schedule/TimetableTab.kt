package com.andaxin.feature.schedule

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.andaxin.app.LocalAppGraph
import com.andaxin.shell.adxViewModel
import com.andaxin.state.TimetableModel

/*
 * 平移注：这一屏搬进共享层了（shared 的 `feature/schedule/TimetableScreen.kt`）。
 * RootShell 还按老签名 `TimetableScreen(modifier)` 调它 —— 这一层薄壳只做
 * "从 AppGraph 抓依赖、拼成 Deps 递进去"（登录批的同款收尾）。等总装把 ShelfHost
 * 那五处改成分发 Deps，这一份就删。
 *
 * 文件名不是 TimetableScreen.kt：共享层那边已有同名文件，同包同名文件的两个
 * facade 类会在 R8 撞车（消息批撞过一回）。
 */
@Composable
fun TimetableScreen(modifier: Modifier = Modifier) {
    val graph = LocalAppGraph.current
    val model: TimetableModel = adxViewModel { TimetableModel(it.timetable) }
    TimetableScreen(TimetableDeps(model = model), modifier = modifier)
}
