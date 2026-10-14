import Foundation

enum AppLanguage {
    static let systemValue = "system"
    static let zhValue = "zh"
    static let enValue = "en"
    static let values = [systemValue, zhValue, enValue]

    static let defaultsMirrorKey = "dev.photopilot.appLanguage"

    nonisolated(unsafe) private(set) static var overrideValue = systemValue

    static func configure(value: String?) {
        let next = values.contains(where: { $0 == value }) ? (value ?? systemValue) : systemValue
        overrideValue = next
        UserDefaults.standard.set(next, forKey: defaultsMirrorKey)
    }

    static var resolvedCode: String {
        if overrideValue != systemValue {
            return overrideValue
        }
        let preferred = Locale.preferredLanguages.first ?? Locale.current.identifier
        return preferred.hasPrefix("zh") ? zhValue : enValue
    }

    static var preferChinese: Bool {
        resolvedCode == zhValue
    }

    static func titleValue(for value: String) -> String {
        switch value {
        case zhValue: return L10n.string("settings.language_zh")
        case enValue: return L10n.string("settings.language_en")
        default: return L10n.string("settings.language_system")
        }
    }
}

enum L10n {
    static let didChangeNotification = Notification.Name("dev.photopilot.language.didChange")

    private static let directoryNames = [AppLanguage.zhValue: "zh-Hans", AppLanguage.enValue: "en"]
    nonisolated(unsafe) private static var cachedBundles: [String: Bundle] = [:]
    nonisolated(unsafe) private static var cachedCode: String?

    static var bundle: Bundle {
        let code = AppLanguage.resolvedCode
        if code != cachedCode {
            cachedBundles.removeAll()
            cachedCode = code
        }
        if let cached = cachedBundles[code] {
            return cached
        }
        let resolved: Bundle
        if let name = directoryNames[code],
           let resourcePath = Bundle.main.resourcePath,
           let sub = Bundle(path: resourcePath + "/" + name + ".lproj") {
            resolved = sub
        } else {
            resolved = .main
        }
        cachedBundles[code] = resolved
        return resolved
    }

    static func string(_ key: String) -> String {
        bundle.localizedString(forKey: key, value: nil, table: nil)
    }

    static func format(_ key: String, _ args: CVarArg...) -> String {
        String(format: string(key), arguments: args)
    }

    static func announceChange() {
        cachedBundles.removeAll()
        cachedCode = nil
        NotificationCenter.default.post(name: didChangeNotification, object: nil)
    }
}
