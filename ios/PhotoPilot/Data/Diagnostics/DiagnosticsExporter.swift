import Foundation
import UIKit

enum DiagnosticsExporter {
    static func makeReport(errorCode: String? = nil, errorType: String? = nil) -> String {
        let info = Bundle.main.infoDictionary
        var payload: [String: String] = [
            "app_version": info?["CFBundleShortVersionString"] as? String ?? "unknown",
            "app_build": info?["CFBundleVersion"] as? String ?? "unknown",
            "os_version": UIDevice.current.systemVersion,
            "schema_version": "\(PersistenceSchema.version)",
            "generated_at": ISO8601DateFormatter().string(from: Date())
        ]
        if let errorCode {
            payload["error_code"] = errorCode
        }
        if let errorType {
            payload["error_type"] = errorType
        }
        guard let data = try? JSONSerialization.data(
            withJSONObject: payload,
            options: [.prettyPrinted, .sortedKeys]
        ), let text = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return text
    }

    static func writeToTemporaryFile(errorCode: String? = nil, errorType: String? = nil) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("photopilot-diagnostics.json")
        try makeReport(errorCode: errorCode, errorType: errorType)
            .write(to: url, atomically: true, encoding: .utf8)
        return url
    }
}
