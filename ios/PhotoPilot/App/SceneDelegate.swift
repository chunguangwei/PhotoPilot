import UIKit

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        guard let windowScene = scene as? UIWindowScene else { return }
        AppLanguage.configure(
            value: UserDefaults.standard.string(forKey: AppLanguage.defaultsMirrorKey)
        )

        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = LaunchViewController()
        self.window = window
        window.makeKeyAndVisible()
    }

}
