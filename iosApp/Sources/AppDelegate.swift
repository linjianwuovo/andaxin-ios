import UIKit
import shared

/*
 * iOS 壳的入口。骨架阶段它只做一件事：把 shared.framework 链起来、
 * 在屏上摆一行字（platformName() 那发来自 NSProcessInfo）——
 * 模拟器里能看到这行字，就证明"Kotlin 编到 iOS、装进 app、跑起来"整条链是通的。
 * 等 Compose 界面搬进 commonMain，这里换成 ComposeUIViewController，整块换掉。
 */

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {

    var window: UIWindow?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        window = UIWindow(frame: UIScreen.main.bounds)

        let vc = UIViewController()
        let label = UILabel(frame: vc.view.bounds)
        label.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        label.textAlignment = .center
        label.font = .systemFont(ofSize: 17, weight: .medium)
        label.text = "AndaXin · " + PlatformKt.platformName()
        vc.view.addSubview(label)

        window?.rootViewController = vc
        window?.makeKeyAndVisible()
        return true
    }
}
