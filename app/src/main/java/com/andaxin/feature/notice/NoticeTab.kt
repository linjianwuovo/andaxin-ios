package com.andaxin.feature.notice

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.andaxin.app.LocalAppGraph
import com.andaxin.core.design.Gap
import com.andaxin.core.net.RemoteImage
import com.andaxin.shell.adxViewModel
import com.andaxin.state.NoticeModel

/*
 * 平移注：公告的列表与详情都搬进共享层了（shared 的 `feature/notice/NoticeScreen.kt`）。
 * RootShell 还按老签名调这两个函数 —— 这一层薄壳只做"从 AppGraph 抓依赖、拼成 Deps
 * 递进去"（登录批的同款收尾）。等总装把 Route.NOTICE_DETAIL 和 ShelfHost 那两处改成
 * 分发 Deps，这一份就删。
 *
 * 注意文件名不是 NoticeScreen.kt：共享层那边已经有同名文件，同包同名文件的两个
 * facade 类（…NoticeScreenKt）会在 R8 撞车 —— 消息批撞过一回。
 */
@Composable
fun NoticeScreen(open: (String) -> Unit = {}, modifier: Modifier = Modifier) {
    val graph = LocalAppGraph.current
    val model: NoticeModel = adxViewModel { NoticeModel(it.notices) }
    NoticeScreen(NoticeDeps(model = model), open = open, modifier = modifier)
}

@Composable
fun NoticeDetailScreen(id: String, onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    NoticeDetailScreen(
        NoticeDetailDeps(
            feed = graph.notices,
            attachment = { url ->
                // 正文附图走我们自己的 OkHttp + 解码 —— Coil 那层没接网络引擎，
                // 远程图一直静默不显示（见 [[remote-image-loading-gap]]）。
                RemoteImage(
                    url = url,
                    contentDescription = "正文配图",
                    cacheDir = graph.context.cacheDir,
                    adaptAspect = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = Gap.md),
                )
            },
        ),
        id = id,
        onBack = onBack,
    )
}
