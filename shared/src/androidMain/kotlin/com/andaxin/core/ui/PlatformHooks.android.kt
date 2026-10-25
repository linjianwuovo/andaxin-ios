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

/** 安卓自己画顶栏（"‹ 返回"那三个字一直是这仓的说法），契约见 commonMain 的 `PlatformHooks.kt`。 */
actual val hostDrawsTopBar: Boolean = false
