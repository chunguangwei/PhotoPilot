import Foundation

enum FormatStrings {
    static func string(_ key: String, _ args: CVarArg...) -> String {
        String(format: L10n.string(key), arguments: args)
    }

    static func fileSizeText(byteSize: Int64) -> String {
        if byteSize < 1_048_576 {
            let kilobytes = max(1, Int((byteSize + 1023) / 1024))
            return string("format.file_size_kb", kilobytes)
        }
        if byteSize < 1_073_741_824 {
            let megabytes = max(1, Int((Double(byteSize) / 1_048_576).rounded()))
            return string("format.file_size_mb", megabytes)
        }
        let gigabytes = max(1, Int((Double(byteSize) / 1_073_741_824).rounded()))
        return string("format.file_size_gb", gigabytes)
    }
}
