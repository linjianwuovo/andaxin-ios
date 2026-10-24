package com.andaxin.core.design

import androidx.compose.animation.core.CubicBezierEasing

/*
 * 动效只有这几个数。
 *
 * 立这一份的理由和 [Type]、[Rhythm] 一样：以前能点的东西按下去没有任何反应，
 * 页面换的时候整块硬切 —— 单看每一屏都不算错，连起来用就像没做完。
 *
 * 三条规矩：
 * ① 进场用 [EaseOut]（一下冲到位再收），退场和来回摆动用 [EaseInOut]。
 *    进场绝不从 scale(0) 或屏幕外 100% 起步再匀速推进，那样看着像被扔出来的。
 * ② 超过 300ms 的动效在这台机器上都会被感知成"卡"，所以最长的一档就是 [pageEnter]。
 * ③ 动效要能被打断：按下走 [press]，松手回弹稍长一点 [pressRelease]，
 *    手指还没抬起来时它已经在往回走了，不用等一个完整周期。
 */
object Motion {
    /** 手指按下去那一下：要贴手，140ms（参考图 MOTION 04 给的 140ms）。 */
    const val press = 140

    /** 松手回弹：比按下去稍长，不然弹回来的那一下很硬。 */
    const val pressRelease = 220

    /** 数据块第一次出现。只淡入，不做逐块延迟 —— 他要的是"一次出"。 */
    const val enter = 200

    /** 往前的转场（推进）。 */
    const val pageEnter = 280

    /** 往回的转场（推出）。比往前快一格，返回这种动作用户已经知道结果。 */
    const val pageExit = 220

    /** ease-out：cubic-bezier(.23, 1, .32, 1)。进场、按下都归它。 */
    val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

    /** ease-in-out：cubic-bezier(.77, 0, .175, 1)。被盖住的那一页退场用它。 */
    val EaseInOut = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)
}
