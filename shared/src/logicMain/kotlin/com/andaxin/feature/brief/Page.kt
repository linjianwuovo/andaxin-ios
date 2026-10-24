package com.andaxin.feature.brief

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.andaxin.core.design.Gap
import com.andaxin.core.design.LocalInk
import com.andaxin.core.design.Type
import com.andaxin.shell.Refreshable
import com.andaxin.shell.pressClick
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 平移注：这一层壳原来长在 app 的 `feature/brief/BriefKit.kt` 里。登录这一批共享层界面
 * （`feature/modules/Login.kt`）要用它，所以先摘出来住进共享层；BriefKit 里列表/详情/表单
 * 那一批还牵着 app 的 LocalAppGraph，留在原地。包名没变，全仓调用点一行不用改。
 */

/**
 * 三种页共用的那一层壳。
 *
 * 换成 Miuix 的 `Scaffold` + `SmallTopAppBar`：HyperOS 那一档标题栏自带状态栏与
 * 刘海的留白，以前是我们手搓一个 `Spacer(Gap.xl)` 顶出来的，换个机型就得重调一次。
 * 返回那颗键仍然写"‹ 返回"三个字 —— 这仓的返回入口一直是这个说法，不换图标。
 */
@Composable
fun ScaffoldPage(
    title: String,
    onBack: () -> Unit,
    /** 给了这一颗，这一屏就能下拉刷新；它是挂起的 —— 拉完才收指示器，不等就是假动作。 */
    onRefresh: (suspend () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = title,
                navigationIcon = {
                    Text(
                        "‹ 返回",
                        fontSize = Type.body,
                        color = LocalInk.current.muted,
                        modifier = Modifier.pressClick(shrink = false, onClick = onBack).padding(horizontal = Gap.md, vertical = Gap.sm),
                    )
                },
            )
        },
        containerColor = topBarBackdrop(),
    ) { bars ->
        Refreshable(onRefresh, Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(bars)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Gap.screen),
            ) {
                Spacer(Modifier.height(Gap.md))
                content()
                Spacer(Modifier.height(Gap.xxl))
            }
        }
    }
}

@Composable
private fun topBarBackdrop() = MiuixTheme.colorScheme.background
