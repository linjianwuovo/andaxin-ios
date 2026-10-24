package com.andaxin.core.ui

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView

/** 契约见 commonMain 的 `PlatformHooks.kt`。app 里那套老做法原样接上：CONTEXT_CLICK。 */
@Composable
actual fun clickHaptic(): () -> Unit {
    val view = LocalView.current
    return { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }
}
