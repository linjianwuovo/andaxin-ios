package com.andaxin.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UISelectionFeedbackGenerator

/*
 * 契约见 commonMain 的 `PlatformHooks.kt`。
 *
 * 10-25 夜：iOS 这路不再是空实现了 —— 点中那一下走系统的触感生成器，用户在系统里关了
 * 键盘触感就自动没有，语义对齐安卓那侧"跟着系统设置走"（`CONTEXT_CLICK`）的口径。
 *
 * 用的是 `UISelectionFeedbackGenerator`（那声"嗒"）。本来更贴的是
 * `UIImpactFeedbackGenerator(style = light)`，但 K/N 这边 `UIImpactFeedbackStyleLight`
 * 这个枚举常量按本地编译取不到名字（2.4.20 的 UIKit 库里不叫这个），而 `UIImpactFeedbackGenerator`
 * 的无参 init 在 UIKit 里是 NS_UNAVAILABLE —— 所以先用选择触感这一档，等枚举名落实再换。
 */
@Composable
actual fun clickHaptic(): () -> Unit {
    val gen = remember { UISelectionFeedbackGenerator() }
    return {
        gen.prepare()   // 提前把 taptic engine 唤醒，省掉第一次的延迟
        gen.selectionChanged()
    }
}

/** 顶栏交给 `UINavigationController` 画，契约见 commonMain 的 `PlatformHooks.kt`。 */
actual val hostDrawsTopBar: Boolean = true
