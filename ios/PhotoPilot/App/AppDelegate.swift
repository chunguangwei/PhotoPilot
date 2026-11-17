import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        AIExecutionRuntime.shared.register()
        return true
    }

    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String, completionHandler: @escaping () -> Void) {
        let completion = AIBackgroundCompletion(completionHandler)
        Task { await AIModelManager.handleBackgroundEvents(identifier: identifier, completion: { completion.finish() }) }
    }

    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        UISceneConfiguration(name: "Default Configuration", sessionRole: connectingSceneSession.role)
    }

    func application(
        _ application: UIApplication,
        supportedInterfaceOrientationsFor window: UIWindow?
    ) -> UIInterfaceOrientationMask {
        guard UIDevice.current.userInterfaceIdiom == .phone else { return .all }
        let resolved = window
            ?? UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .flatMap(\.windows)
                .first(where: \.isKeyWindow)
        guard let root = resolved?.rootViewController else { return .portrait }
        var top = root
        while let presented = top.presentedViewController {
            top = presented
        }
        return top is PlayerViewController ? .allButUpsideDown : .portrait
    }
}

@MainActor
private final class AIBackgroundCompletion {
    private let handler: () -> Void
    init(_ handler: @escaping () -> Void) { self.handler = handler }
    func finish() { handler() }
}
