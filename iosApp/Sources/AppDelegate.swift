import UIKit
import shared

/*
 * iOS 壳的入口。framework 接线版：脚本相位按当前 SDK/配置把对应 flavor 的
 * shared.framework 放到固定路径，工程静态引用它 —— 这行 platformName() 来自
 * shared（NSProcessInfo 那发），模拟器里看到它就等于「Kotlin→iOS 链路全程通」。
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
