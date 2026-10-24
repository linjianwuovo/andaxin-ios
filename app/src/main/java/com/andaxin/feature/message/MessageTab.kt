package com.andaxin.feature.message

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.andaxin.app.LocalAppGraph
import com.andaxin.shell.adxViewModel
import com.andaxin.state.MessageModel

/*
 * 平移注：这一屏搬进共享层了（shared 的 `feature/message/MessageScreen.kt`）。
 * RootShell 还按老签名 `MessageScreen(modifier)` 调它 —— 这一层薄壳只做
 * "从 AppGraph 抓依赖、拼成 Deps 递进去"（登录批的同款收尾）。等总装把 ShelfHost
 * 那五处改成分发 Deps，这一份就删。
 */
@Composable
fun MessageScreen(modifier: Modifier = Modifier) {
    val graph = LocalAppGraph.current
    val model: MessageModel = adxViewModel { MessageModel(it.messages) }
    MessageScreen(
        MessageDeps(
            model = model,
            lastRunAt = graph.poller.lastRunAt,
            busy = graph.poller.busy,
        ),
        modifier = modifier,
    )
}
