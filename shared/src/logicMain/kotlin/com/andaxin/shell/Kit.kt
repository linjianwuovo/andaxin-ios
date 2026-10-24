@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.andaxin.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.andaxin.core.design.LocalInk
import com.andaxin.core.design.Motion
import com.andaxin.core.ui.clickHaptic
import kotlinx.coroutines.launch

/*
 * 平移注：这两件是从 app 的 `shell/Bits.kt` 里摘出来的 —— 登录这一批共享层界面（ScaffoldPage
 * 一族）要用它们。Bits.kt 里其余零件（Sheet/RowLine/Chip…）还牵着 app 的东西，留在原地；
 * 包名没变，全仓调用点一行不用改。触感那一下改走 `com.andaxin.core.ui.PlatformHooks`，
 * android 端行为与原来一字不差。
 */

/**
 * 能点的东西按下去得有个交代。
 *
 * 两种交代，分开用：
 * ① [shrink] = true —— 独立的胶囊、图标格、按钮、整张卡：按他 10-21 那份规范，
 *    缩到 **0.98**（原来 0.97，2% 就够，缩多了像抖），同时叠一层很淡的白光（"微亮高光"）；
 * ② [shrink] = false —— 卡在一张卡里的行：不能缩，一缩就把同卡别的行也带歪，
 *    所以改成压暗到 0.55，这是 HyperOS 设置列表那一下（这种行也不叠高光，白一下反而像闪）。
 *
 * 触觉：只在**真的点中**那一下响（`onClick` 里），不在手指刚落下时响 ——
 * 卡片铺满整屏，按在卡上起手一划也是"按下"，按下去就震会变成一路滑一路嗡嗡。
 * 震感走 `PlatformHooks.clickHaptic()`（android = 系统的 `CONTEXT_CLICK`，
 * 用户关了系统触感反馈就自动没有）。
 *
 * 涟漪（ripple）在这儿一律关掉：这一层已经给足了反馈，再叠一圈水波纹就是两种风格打架。
 * 松手比按下稍慢（[Motion.press] / [Motion.pressRelease]），手指抬起来的那一下能看见"回弹"，
 * 而且动画是可打断的 —— 没按满也会立刻往回走。
 */
@Composable
fun Modifier.pressClick(enabled: Boolean = true, shrink: Boolean = true, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val haptic = clickHaptic()
    val ink = LocalInk.current
    val amount = animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        // 按下去是一段贴手的 tween；松手走 spring 收尾。阻尼给满（1.0）——
        // 他 10-21 发的那张参考图的 MOTION 页明确写了"不做弹性过冲"：
        // 要的是自然的减速，不是弹一下再回来。
        animationSpec = if (pressed) {
            tween(Motion.press, easing = Motion.EaseOut)
        } else {
            spring(dampingRatio = 1f, stiffness = 900f)
        },
        label = "press",
    )
    /*
     * 高光的圆角按高度猜：胶囊/按钮的高都在 40dp 内，取 height/2 就是一颗药丸；
     * 卡片更高，封顶 16dp —— 正好是 `Radius.Card`。不另加参数，62 个调用点不用逐个改。
     */
    val glow = if (ink.dark) 0.10f else 0.16f
    return this
        .graphicsLayer {
            val t = amount.value
            if (shrink) {
                scaleX = 1f - t * 0.02f
                scaleY = 1f - t * 0.02f
            } else {
                alpha = 1f - t * 0.45f
            }
        }
        .then(
            if (shrink) Modifier.drawWithContent {
                drawContent()
                val t = amount.value
                if (t > 0.001f) {
                    drawRoundRect(
                        color = Color.White.copy(alpha = glow * t),
                        cornerRadius = CornerRadius(minOf(size.height / 2f, 16.dp.toPx())),
                    )
                }
            } else Modifier
        )
        .clickable(interactionSource = src, indication = null, enabled = enabled) {
            haptic()
            onClick()
        }
}

/**
 * 一屏内容的下拉刷新壳。[onRefresh] 给 null 就等于没套（详情页这种没什么可刷的）。
 *
 * 那颗回调是**挂起**的：拉完才收指示器。不等它就收，是个假动作 —— 用户看到转圈停了
 * 但数还没换，只会再拉一次。
 */
@Composable
fun Refreshable(
    onRefresh: (suspend () -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (onRefresh == null) {
        content()
        return
    }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            scope.launch {
                onRefresh()
                refreshing = false
            }
        },
        modifier = modifier,
        content = { content() },
    )
}
