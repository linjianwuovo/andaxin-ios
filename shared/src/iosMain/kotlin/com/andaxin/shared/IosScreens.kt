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
import platform.Foundation.NSBundle
import platform.UIKit.UINavigationController
import platform.UIKit.UIViewController
import platform.UIKit.navigationController
import platform.UIKit.navigationItem

/*
 * iOS 屏幕工厂：Swift 侧拿这些函数去挂 UITabBarController / 登录导航。
 *
 * 骨架版口径：Deps 里宿主槽（heroArt/头像/附图）全 null = 各屏自带占位兜底；
 * open 回调（路由跳转）iOS 导航还没搭全，先空实现——屏内点卡片不动，但屏本身渲染。
 * 登录成功后的切 tab 由 Swift 侧 onDone 闭包接管。
 *
 * 10-25 夜改：每一屏都包进一台 `UINavigationController`，标题交给 `navigationItem.title`。
 * 他要的是「返回键用 ios 官方的接口 ui」——共享层那层顶栏在 iOS 上已经不画了
 * （见 `ScaffoldPage` 里的 `hostDrawsTopBar`），所以系统那条栏就是唯一的一条：
 * 返回是原生那颗（跟着 App 语言出中文），右滑手势也才认得。
 */

/** 把一屏包进导航栈，标题走系统那条栏。 */
private fun navigated(child: UIViewController, title: String): UINavigationController {
    child.navigationItem.title = title
    val nav = UINavigationController(rootViewController = child)
    // 大标题会把栏撑高、也跟他截图那版居中小标题不一样，按默认那档来
    nav.navigationBar.prefersLargeTitles = false
    return nav
}

/** 本机这版是多少 —— 直接读包里的 Info.plist，别再手写一个会过期的数。 */
private fun bundleVersion(): String =
    NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "0.0.0"

/** 首页。 */
fun IosAppGraph.homeViewController(): UIViewController =
    navigated(
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
        },
        "首页",
    )

/** 课表。 */
fun IosAppGraph.timetableViewController(): UIViewController =
    navigated(
        ComposeUIViewController {
            TimetableScreen(deps = TimetableDeps(model = timetableModel))
        },
        "课表",
    )

/** 消息。 */
fun IosAppGraph.messageViewController(): UIViewController =
    navigated(
        ComposeUIViewController {
            MessageScreen(deps = MessageDeps(model = messageModel))
        },
        "消息",
    )

/** 公告。 */
fun IosAppGraph.noticeViewController(): UIViewController =
    navigated(
        ComposeUIViewController {
            NoticeScreen(deps = NoticeDeps(model = noticeModel), open = {})
        },
        "公告",
    )

/** 我的。 */
fun IosAppGraph.meViewController(): UIViewController =
    navigated(
        ComposeUIViewController {
            MeScreen(
                deps = MeDeps(
                    session = session,
                    who = WhoIs(),
                    loadProfile = { Result.failure(Exception("还没接")) },
                    loadIntern = { Result.failure(Exception("还没接")) },
                    avatar = null,
                    entryIcon = null,
                    versionName = bundleVersion(),
                ),
                open = {},
            )
        },
        "我的",
    )

/**
 * 登录页（Gate 判未登录时进）。
 *
 * `onBack` 现在是真的：这一屏在导航栈里往回退得动（从引导页推进来时），
 * 而它自己就是根的那条路（没登录直接进）没有可退的，系统也就不会画那颗返回 ——
 * 不再是原来那颗点了没反应的 "‹ 返回"。
 */
fun IosAppGraph.loginViewController(onDone: () -> Unit): UIViewController {
    var host: UIViewController? = null
    val child = ComposeUIViewController {
        LoginScreen(
            deps = LoginDeps(
                auth = auth,
                session = session,
                vault = vault,
                kicked = MutableStateFlow(null),
                consumeKicked = {},
            ),
            onDone = onDone,
            onBack = { host?.navigationController?.popViewControllerAnimated(true) },
        )
    }
    host = child
    return navigated(child, "登录")
}

/** 首次使用（免责声明那屏）。 */
fun IosAppGraph.onboardingViewController(onAgree: () -> Unit): UIViewController =
    navigated(
        ComposeUIViewController {
            OnboardingScreen(onAgree = onAgree)
        },
        "安大信",
    )
