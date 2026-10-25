import UIKit
import shared

/*
 * iOS 原生壳：UITabBarController 做底栏（系统毛玻璃和切联动效白送），五个 tab 各挂
 * 一个 ComposeUIViewController（内容区 = 共享层的 Compose 屏，与 Android 同一套代码）。
 * Gate 骨架口径：signedIn 是 suspend（DataStore 现查），启动时在全局协程里查完再定根——
 * 没登录给登录页，onDone 换五格底栏。真实 Gate（onboarded/到期/顶号）下一批接。
 */

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {

    var window: UIWindow?
    private var graph: IosAppGraph?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // 系统那条栏：实心、跟 App 底色一路（默认的半透明磨砂在长列表滚起来会糊成一片）。
        // 走 UINavigationBar.appearance 是因为五格各自有自己的导航栈，逐台配会漏。
        let bar = UINavigationBarAppearance()
        bar.configureWithOpaqueBackground()
        UINavigationBar.appearance().standardAppearance = bar
        UINavigationBar.appearance().scrollEdgeAppearance = bar

        // 拿不到 Documents 就退到 Caches、再退到临时目录：
        // 之前兜底成空串，路径会变成 "/andaxin.preferences_pb"（文件系统根），
        // 读有 runCatching 兜着看不出来，一写就炸。
        let docs = NSSearchPathForDirectoriesInDomains(.documentDirectory, .userDomainMask, true).first
            ?? NSSearchPathForDirectoriesInDomains(.cachesDirectory, .userDomainMask, true).first
            ?? NSTemporaryDirectory()
        let g = IosAppGraph(documentsPath: docs)
        graph = g

        window = UIWindow(frame: UIScreen.main.bounds)
        window?.rootViewController = splashHolder()
        window?.makeKeyAndVisible()

        // Gate 的挂起细节全在 Kotlin 侧消化，Swift 只拿同步回调。
        // 回调里的布尔过桥是盒子化的 KotlinBoolean（不是 Swift Bool），所以判真假要取 .boolValue ——
        // 直接 `if signed` 编不过：cannot convert value of type 'KotlinBoolean' to 'Bool'。
        g.resolveGate { [weak self] signed in
            if signed.boolValue {
                self?.window?.rootViewController = self?.makeTabs(g)
            } else {
                self?.window?.rootViewController = self?.makeLogin(g)
            }
        }
        return true
    }

    /// 启动那一瞬间的占位（查 signedIn 通常 <10ms，但也不能白屏闪）
    private func splashHolder() -> UIViewController {
        let vc = UIViewController()
        vc.view.backgroundColor = .systemBackground
        return vc
    }

    /// 登录页：成功 onDone 换五格底栏
    private func makeLogin(_ g: IosAppGraph) -> UIViewController {
        g.loginViewController { [weak self] in
            self?.window?.rootViewController = self?.makeTabs(g)
        }
    }

    /// 五格底栏：标题跟 Android 那五格一致；图标用系统 SF Symbols（不再等美术出图）。
    private func makeTabs(_ g: IosAppGraph) -> UITabBarController {
        let tabs = UITabBarController()

        // 每屏在 Kotlin 侧已经包进自己的 UINavigationController，这里只贴底栏那一格
        let items: [(UIViewController, String, String)] = [
            (g.homeViewController(), "首页", "house"),
            (g.timetableViewController(), "课表", "calendar"),
            (g.messageViewController(), "消息", "envelope"),
            (g.noticeViewController(), "公告", "megaphone"),
            (g.meViewController(), "我的", "person.crop.circle"),
        ]
        for (vc, title, symbol) in items {
            vc.tabBarItem = UITabBarItem(
                title: title,
                image: UIImage(systemName: symbol),
                selectedImage: UIImage(systemName: symbol)
            )
        }

        tabs.viewControllers = items.map { $0.0 }
        tabs.tabBar.isTranslucent = true
        return tabs
    }
}
