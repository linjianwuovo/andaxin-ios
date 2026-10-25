package com.andaxin.core.ui

import androidx.compose.runtime.Composable

/*
 * 平台顺手小事的口子。UI 层搬到共享层之后，common 里摸不到的那几样平台 API 都从这儿过：
 * 契约放 commonMain，android/ios/jvm 各 actual 一份。新钩子加在这里，别在界面里直接摸平台。
 *
 * 第一批只收一个：点中那一下的系统触感。登录这一批 UI 里没有 Toast（提示都写在屏上）、
 * 没有 R.string（文案本来就是内联中文串）、没有图片加载，所以暂时只需要这一个口。
 */

/**
 * 点中那一下的系统触感，返回一个"点中了"的回调。
 *
 * android = `CONTEXT_CLICK`（用户在系统里关了触感反馈就自动没有）；
 * iOS/JVM 暂时给空实现 —— iOS 那边以后接 `UIImpactFeedbackGenerator` 时只动 actual，不动界面。
 * 只在**真的点中**那一下调，不在手指刚落下时调（理由见 `pressClick` 顶上那段）。
 */
@Composable
expect fun clickHaptic(): () -> Unit

/**
 * 这一端的宿主自己画不画顶部那条栏（标题 + 返回）。
 *
 * 安卓 = false：NavHost 里那些页是我们自己画的顶栏，`ScaffoldPage` 照旧出 "‹ 返回"。
 * iOS = true：屏进 `UINavigationController`，标题走 `navigationItem.title`、返回走系统那颗
 *            带中文 locale 的 `<` —— 他定的口径「搬屏不搬壳」，共享层再画一颗就是两条栏、
 *            两个返回，而且系统那个右滑返回手势才认得。
 * JVM（测试架）= false，跟安卓一致。
 */
expect val hostDrawsTopBar: Boolean
