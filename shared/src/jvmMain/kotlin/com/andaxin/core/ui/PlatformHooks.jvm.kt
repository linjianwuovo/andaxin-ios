package com.andaxin.core.ui

import androidx.compose.runtime.Composable

/** 契约见 commonMain 的 `PlatformHooks.kt`。桌面测试架没有触感这回事，空实现。 */
@Composable
actual fun clickHaptic(): () -> Unit = {}

/** 测试架跟安卓一路：自己画顶栏。契约见 commonMain 的 `PlatformHooks.kt`。 */
actual val hostDrawsTopBar: Boolean = false
