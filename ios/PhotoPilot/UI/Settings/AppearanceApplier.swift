import UIKit

enum AppearanceApplier {
    static func apply(_ value: String) {
        let style: UIUserInterfaceStyle
        switch value {
        case "light":
            style = .light
        case "dark":
            style = .dark
        default:
            style = .unspecified
        }
        for scene in UIApplication.shared.connectedScenes {
            guard let windowScene = scene as? UIWindowScene else { continue }
            for window in windowScene.windows {
                window.overrideUserInterfaceStyle = style
            }
        }
    }
}
