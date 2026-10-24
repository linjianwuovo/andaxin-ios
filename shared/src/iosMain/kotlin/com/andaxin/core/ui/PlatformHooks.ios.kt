package com.andaxin.core.ui

import androidx.compose.runtime.Composable

/** 契约见 commonMain 的 `PlatformHooks.kt`。iOS 先空着，接 `UIImpactFeedbackGenerator` 时只动这一份。 */
@Composable
actual fun clickHaptic(): () -> Unit = {}
