package com.andaxin.core.ui

import androidx.compose.runtime.Composable

/** 契约见 commonMain 的 `PlatformHooks.kt`。桌面测试架没有触感这回事，空实现。 */
@Composable
actual fun clickHaptic(): () -> Unit = {}
