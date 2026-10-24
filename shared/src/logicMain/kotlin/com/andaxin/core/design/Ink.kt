package com.andaxin.core.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * 平移注：这一段原来长在 app 的 `AndaXinTheme.kt` 里。共享层的界面（登录这一批先搬）
 * 只认 [InkSet]/[LocalInk]，认不得那边的主题装配（它还牵着 android.graphics 和 res/font），
 * 所以把这两样先摘出来住进共享层，app 那边照旧装配 —— 包名没变，全仓 import 一行不用改。
 */

/**
 * 文字层级单独抽一份出来。
 * Material3 那套 onBackground/onSurface/onSurfaceVariant 只有三档，我们界面上实际用到五档，
 * 以前只能各处手写 Color(0xFF…) —— 抽出来后改一档全改。
 */
@Immutable
data class InkSet(
    val strong: Color,
    val body: Color,
    val muted: Color,
    val faint: Color,
    val ghost: Color,
    val hairline: Color,
    /**
     * 控件"关着 / 没选中"时的那层灰底。
     *
     * 单独立一档是因为以前这些地方全用 `hairline`（6% 黑），在浅色主题下几乎看不见：
     * 没选中的胶囊看着像没画完，选中的那颗（surfaceVariant）反而才是灰的 —— 状态是反的。
     * 他 10-20 深夜就一句话：按钮关闭时用灰底。
     */
    val offFill: Color = Palette.Sand,
    /**
     * 这一套是不是夜间。
     *
     * 立这么一个标志，是为了别再靠"读文字亮度猜明暗"：`TimetableScreen` 原来写的是
     * `strong.luminance() < 0.5f` 才算夜间，而夜间那套 `strong` 是浅灰白（亮度 0.9）——
     * 判断整个是反的，夜间下拿到的还是浅色那五档淡底。
     */
    val dark: Boolean = false,
)

val LocalInk: ProvidableCompositionLocal<InkSet> = staticCompositionLocalOf { InkSet(
    strong = Palette.Ink,
    body = Palette.InkSoft,
    muted = Palette.InkMuted,
    faint = Palette.InkFaint,
    ghost = Palette.InkGhost,
    hairline = Palette.Hairline,
    offFill = Palette.Sand,
) }
