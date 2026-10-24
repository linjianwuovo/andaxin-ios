package com.andaxin.shared

import androidx.compose.ui.window.ComposeUIViewController
import com.andaxin.core.store.WhoIs
import com.andaxin.feature.home.HeroLook
import com.andaxin.feature.home.HomeDeps
import com.andaxin.feature.home.HomeScreen
import com.andaxin.feature.me.MeDeps
import com.andaxin.feature.me.MeScreen
import com.andaxin.feature.message.MessageDeps
import com.andaxin.feature.message.MessageScreen
import com.andaxin.feature.modules.LoginDeps
import com.andaxin.feature.modules.LoginScreen
import com.andaxin.feature.modules.OnboardingScreen
import com.andaxin.feature.notice.NoticeDeps
import com.andaxin.feature.notice.NoticeScreen
import com.andaxin.feature.schedule.TimetableDeps
import com.andaxin.feature.schedule.TimetableScreen
import kotlinx.coroutines.flow.MutableStateFlow
import platform.UIKit.UIViewController

/*
 * iOS 屏幕工厂：Swift 侧拿这些函数去挂 UITabBarController / 登录导航。
 *
 * 骨架版口径：Deps 里宿主槽（heroArt/头像/附图）全 null = 各屏自带占位兜底；
 * open 回调（路由跳转）iOS 导航还没搭，先空实现——屏内点卡片不动，但屏本身渲染。
 * 登录成功后的切 tab 由 Swift 侧 onDone 闭包接管。
 */

/** 首页。 */
fun IosAppGraph.homeViewController(): UIViewController =
    ComposeUIViewController {
        HomeScreen(
            deps = HomeDeps(
                model = homeModel,
                session = session,
                heroStyle = MutableStateFlow(HeroLook.Auto),
                heroArt = null,
                loadExtras = { Result.failure(Exception("校历那一路 iOS 还没接")) },
                todoFeeds = emptyList(),
            ),
            open = {},
        )
    }

/** 课表。 */
fun IosAppGraph.timetableViewController(): UIViewController =
    ComposeUIViewController {
        TimetableScreen(deps = TimetableDeps(model = timetableModel))
    }

/** 消息。 */
fun IosAppGraph.messageViewController(): UIViewController =
    ComposeUIViewController {
        MessageScreen(deps = MessageDeps(model = messageModel))
    }

/** 公告。 */
fun IosAppGraph.noticeViewController(): UIViewController =
    ComposeUIViewController {
        NoticeScreen(deps = NoticeDeps(model = noticeModel), open = {})
    }

/** 我的。 */
fun IosAppGraph.meViewController(): UIViewController =
    ComposeUIViewController {
        MeScreen(
            deps = MeDeps(
                session = session,
                who = WhoIs(),
                loadProfile = { Result.failure(Exception("还没接")) },
                loadIntern = { Result.failure(Exception("还没接")) },
                avatar = null,
                entryIcon = null,
                versionName = "0.8.28",
            ),
            open = {},
        )
    }

/** 登录页（Gate 判未登录时进）。 */
fun IosAppGraph.loginViewController(onDone: () -> Unit): UIViewController =
    ComposeUIViewController {
        LoginScreen(
            deps = LoginDeps(
                auth = auth,
                session = session,
                vault = vault,
                kicked = MutableStateFlow(null),
                consumeKicked = {},
            ),
            onDone = onDone,
            onBack = {},
        )
    }

/** 首次使用（免责声明那屏）。 */
fun IosAppGraph.onboardingViewController(onAgree: () -> Unit): UIViewController =
    ComposeUIViewController {
        OnboardingScreen(onAgree = onAgree)
    }
