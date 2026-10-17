import UIKit

enum ThemeColor {
    static let canvas = dynamic(light: 0xF4F1EB, dark: 0x171A18)
    static let panel = dynamic(light: 0xFCFAF6, dark: 0x242825)
    static let textPrimary = dynamic(light: 0x202321, dark: 0xF4F1EB)
    static let textSecondary = dynamic(light: 0x62675F, dark: 0xB7BDB4)
    static let accentBrand = dynamic(light: 0xB89A68, dark: 0xCFB380)
    static let accentAction = dynamic(light: 0x6A5431, dark: 0xDBC69F)
    static let separator = dynamic(light: 0xDDDCD4, dark: 0x3D443F)
    static let statusSuccess = dynamic(light: 0x35644A, dark: 0x91BC9F)
    static let statusWarning = dynamic(light: 0x865B20, dark: 0xE0BC7B)
    static let statusError = dynamic(light: 0xA73930, dark: 0xEEAAA2)
    static let restSurface = UIColor(red: 0, green: 0, blue: 0, alpha: 1)
    static let restCard = UIColor(red: 0x21 / 255, green: 0x24 / 255, blue: 0x22 / 255, alpha: 1)
    static let textOnPhoto = UIColor(red: 0xF4 / 255, green: 0xF1 / 255, blue: 0xEB / 255, alpha: 1)
    static let photoControl = UIColor(red: 0x17 / 255, green: 0x1A / 255, blue: 0x18 / 255, alpha: 1)

    static func dynamic(light: UInt32, dark: UInt32) -> UIColor {
        UIColor { traits in
            let hex = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(
                red: CGFloat((hex >> 16) & 0xFF) / 255,
                green: CGFloat((hex >> 8) & 0xFF) / 255,
                blue: CGFloat(hex & 0xFF) / 255,
                alpha: 1
            )
        }
    }
}

enum ThemeFont {
    static func title() -> UIFont { scaled(size: 32, weight: .semibold, textStyle: .largeTitle) }
    static func heading() -> UIFont { scaled(size: 22, weight: .semibold, textStyle: .title2) }
    static func body() -> UIFont { scaled(size: 17, weight: .regular, textStyle: .body) }
    static func caption() -> UIFont { scaled(size: 15, weight: .regular, textStyle: .subheadline) }
    static func meta() -> UIFont { scaled(size: 13, weight: .regular, textStyle: .footnote) }

    static func clock() -> UIFont {
        let base = UIFont.monospacedDigitSystemFont(ofSize: 64, weight: .light)
        return UIFontMetrics(forTextStyle: .largeTitle).scaledFont(for: base, maximumPointSize: 88)
    }

    private static func scaled(size: CGFloat, weight: UIFont.Weight, textStyle: UIFont.TextStyle) -> UIFont {
        UIFontMetrics(forTextStyle: textStyle).scaledFont(for: UIFont.systemFont(ofSize: size, weight: weight))
    }
}

enum ThemeMetrics {
    static let space4: CGFloat = 4
    static let space8: CGFloat = 8
    static let space12: CGFloat = 12
    static let space16: CGFloat = 16
    static let space24: CGFloat = 24
    static let space32: CGFloat = 32
    static let space48: CGFloat = 48
    static let radiusThumbnail: CGFloat = 12
    static let radiusGroup: CGFloat = 16
    static let radiusModal: CGFloat = 24
    static let sidebarWidth: CGFloat = 216
    static let railWidth: CGFloat = 88
    static let contentInset: CGFloat = 32
    static let contentInsetCompact: CGFloat = 16
    static let bodyMaxWidth: CGFloat = 720
    static let gridGap: CGFloat = 16
    static let gridCellMin: CGFloat = 150
    static let gridCellMinCompact: CGFloat = 85
    static let gridCellMax: CGFloat = 240
    static let touchTarget: CGFloat = 44
    static let controlOpacity: CGFloat = 0.94
}
