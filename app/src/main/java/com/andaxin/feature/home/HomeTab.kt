package com.andaxin.feature.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.andaxin.R
import com.andaxin.app.LocalAppGraph
import com.andaxin.core.settings.HeroStyle
import com.andaxin.shell.adxViewModel
import com.andaxin.state.HomeModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/*
 * 平移注：首页这一屏搬进共享层了（shared 的 `feature/home/HomeScreen.kt`）。
 * RootShell 还按老签名 `HomeScreen(open, modifier)` 调它 —— 这一层薄壳只做
 * "从 AppGraph 抓依赖、拼成 Deps 递进去"（登录批的同款收尾）。等总装把 ShelfHost
 * 那五处改成分发 Deps，这一份就删。
 *
 * 文件名不是 HomeScreen.kt：共享层那边已有同名文件，同包同名文件的两个 facade 类
 * 会在 R8 撞车（消息批撞过一回）。
 */

/** app 侧主题设置的档位 → 共享层那枚同口径的 HeroLook。 */
private fun HeroStyle.toLook(): HeroLook = when (this) {
    HeroStyle.Auto -> HeroLook.Auto
    HeroStyle.Dawn -> HeroLook.Dawn
    HeroStyle.Dusk -> HeroLook.Dusk
    HeroStyle.Night -> HeroLook.Night
}

@Composable
fun HomeScreen(open: (String) -> Unit = {}, modifier: Modifier = Modifier) {
    val graph = LocalAppGraph.current
    val model: HomeModel = adxViewModel { HomeModel(it.dashboard, it.timetable) }
    val scope = rememberCoroutineScope()
    val heroStyle = remember {
        graph.appearance.state
            .map { it.hero.toLook() }
            .stateIn(scope, SharingStarted.Eagerly, HeroLook.Auto)
    }

    HomeScreen(
        HomeDeps(
            model = model,
            session = graph.session,
            heroStyle = heroStyle,
            heroArt = { art ->
                val picture = when (art) {
                    HeroArt.Dawn -> R.drawable.hero_dawn
                    HeroArt.Dusk -> R.drawable.hero_dusk
                    HeroArt.Night -> R.drawable.hero_night
                }
                // 打包进 res/drawable-nodpi 的那三张：没有网络也能出，画满整格由外面裁圆角
                Image(
                    painter = painterResource(picture),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            },
            loadExtras = { graph.home.load() },
            todoFeeds = listOf(graph.affairList, graph.toReadList, graph.myAffairList, graph.handledList),
        ),
        open = open,
        modifier = modifier,
    )
}
