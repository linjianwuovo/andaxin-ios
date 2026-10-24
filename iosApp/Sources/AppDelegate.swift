import UIKit

/*
 * iOS 壳的入口。骨架第二版：这一版先不链 shared.framework ——
 * framework 本体在 CI 的「linkReleaseFrameworkIosArm64」那步已经真实产出（见产物），
 * 但怎么把它挂进 XcodeGen 生成的工程（先编 framework 再生成工程 + framework 依赖路径），
 * 留给下一版接线。这版先证三件事：工程能生成、app 能构建、模拟器能点亮。
 * framework 接上后，这里摆的那行字换成 platformName()（来自 shared）。
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
        label.text = "AndaXin · iOS 壳（骨架，未接 framework）"
        vc.view.addSubview(label)

        window?.rootViewController = vc
        window?.makeKeyAndVisible()
        return true
    }
}
