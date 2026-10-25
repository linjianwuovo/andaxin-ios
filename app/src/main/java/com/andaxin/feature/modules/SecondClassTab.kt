package com.andaxin.feature.modules

import androidx.compose.runtime.Composable
import com.andaxin.app.LocalAppGraph

/**
 * RootShell 还按老签名 `SecondClassScreen(onBack = …)` 调它 —— 这一层薄壳只做一件事：
 * 把装配图里那台 `CaptureSecondClass` 包成 Deps 递进共享层。
 *
 * 文件名故意不叫 SecondClassScreen.kt：同包同名文件的两个 facade 类会在 R8 撞车（消息批撞过一回）。
 */
@Composable
fun SecondClassScreen(onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    SecondClassScreen(SecondClassDeps(graph.secondClass), onBack = onBack)
}
