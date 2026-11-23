import Foundation
import Security

enum NasTransportError: Error, Equatable {
    case certUntrusted(fingerprint: String, subject: String?, validFrom: Int?, validTo: Int?)
    case certHostnameMismatch(fingerprint: String)
    case fingerprintChanged(fingerprint: String)
    case cleartextBlocked
    case certificateExpired(fingerprint: String, subject: String?, validFrom: Int?, validTo: Int?)
    case authFailed
    case pathNotFound
    case assetGone
    case permissionDenied
    case unavailable
    case localNetworkDenied
    case protocolUnsupported
    case unreachable
    case hostUnknown
    case timeout
    case protocolMismatch
    case notWebDav
    case shareNotFound
}

extension NasTransportError: LocalizedError {
    var errorDescription: String? {
        switch self {
        case .certUntrusted:
            return AIText.value(
                "The server certificate isn’t trusted. Confirm its fingerprint in the source settings.",
                "服务器证书未受信任，请在来源设置中确认证书指纹。"
            )
        case .certHostnameMismatch:
            return AIText.value(
                "The certificate doesn’t match this address.",
                "证书与该地址不匹配。"
            )
        case .fingerprintChanged:
            return AIText.value(
                "The certificate fingerprint changed. Confirm it again in the source settings.",
                "证书指纹已变化，请在来源设置中重新确认。"
            )
        case .certificateExpired:
            return AIText.value(
                "The server certificate has expired. Renew it on the NAS, then retry.",
                "服务器证书已过期，请在 NAS 上更新证书后重试。"
            )
        case .cleartextBlocked:
            return AIText.value(
                "Cleartext connections need an explicit allowance for this private address.",
                "明文连接需要为该私有地址显式放行。"
            )
        case .authFailed:
            return AIText.value("The username or password was rejected.", "用户名或密码被拒绝。")
        case .pathNotFound:
            return AIText.value("The folder was not found on the server.", "服务器上找不到该目录。")
        case .assetGone:
            return AIText.value("This photo is no longer on the server.", "这张照片已不在服务器上。")
        case .permissionDenied:
            return AIText.value("The server denied access to this item.", "服务器拒绝访问该项目。")
        case .unavailable:
            return AIText.value("The server is temporarily unavailable.", "服务器暂时不可用。")
        case .localNetworkDenied:
            return AIText.value(
                "Local network access was denied. Allow it in Settings.",
                "本地网络访问被拒绝，请在系统设置中允许。"
            )
        case .protocolUnsupported:
            return AIText.value("The server doesn’t support this protocol.", "服务器不支持该协议。")
        case .unreachable:
            return AIText.value("The server can’t be reached.", "无法连接到服务器。")
        case .hostUnknown:
            return AIText.value("The server address couldn’t be resolved.", "无法解析服务器地址。")
        case .timeout:
            return AIText.value("The server timed out.", "连接服务器超时。")
        case .protocolMismatch:
            return AIText.value("The server responded unexpectedly.", "服务器响应不符合预期。")
        case .notWebDav:
            return AIText.value("This folder isn’t a WebDAV share.", "该目录不是 WebDAV 共享。")
        case .shareNotFound:
            return AIText.value("The share was not found on the server.", "服务器上找不到该共享。")
        }
    }
}

final class NasTlsPolicy: NSObject, URLSessionDelegate {
    enum TrustDecision: Equatable {
        case allow
        case reject(NasTransportError)
    }

    static let statusCertificateExpired = -67818
    static let statusCertificateNotYetValid = -67821

    private let pinStore: TofuPinStore?
    private let lock = NSLock()
    private var decisionErrors: [String: NasTransportError] = [:]

    init(pinStore: TofuPinStore? = nil) {
        self.pinStore = pinStore
    }

    func lastError(host: String, port: Int) -> NasTransportError? {
        lock.lock()
        defer { lock.unlock() }
        return decisionErrors["\(host.lowercased()):\(port)"]
    }

    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust else {
            completionHandler(.performDefaultHandling, nil)
            return
        }
        let host = challenge.protectionSpace.host.lowercased()
        let port = challenge.protectionSpace.port
        let decision = evaluate(trust: trust, host: host, port: port)
        switch decision {
        case .allow:
            completionHandler(.useCredential, URLCredential(trust: trust))
        case .reject(let error):
            lock.lock()
            decisionErrors["\(host):\(port)"] = error
            lock.unlock()
            completionHandler(.cancelAuthenticationChallenge, nil)
        }
    }

    func evaluate(trust: SecTrust, host: String, port: Int) -> TrustDecision {
        var evaluationError: CFError?
        if SecTrustEvaluateWithError(trust, &evaluationError) {
            return .allow
        }
        guard let fingerprint = Self.leafFingerprint(trust: trust) else {
            return .reject(.unavailable)
        }
        let key = "\(host):\(port)"
        if let nsError = evaluationError as NSError?,
           nsError.domain == NSOSStatusErrorDomain,
           nsError.code == Self.statusCertificateExpired
            || nsError.code == Self.statusCertificateNotYetValid {
            return .reject(.certificateExpired(
                fingerprint: fingerprint,
                subject: Self.leafCertificate(trust: trust)
                    .flatMap { SecCertificateCopySubjectSummary($0) as String? },
                validFrom: nil,
                validTo: nil
            ))
        }
        if let pin = pinStore?.pin(for: key) {
            return pin.fingerprint == fingerprint
                ? .allow
                : .reject(.fingerprintChanged(fingerprint: fingerprint))
        }
        if chainIsValidIgnoringHostname(trust: trust) {
            return .reject(.certHostnameMismatch(fingerprint: fingerprint))
        }
        return .reject(.certUntrusted(
            fingerprint: fingerprint,
            subject: Self.leafCertificate(trust: trust)
                .flatMap { SecCertificateCopySubjectSummary($0) as String? },
            validFrom: nil,
            validTo: nil
        ))
    }

    static func isCleartextPermitted(host: String, pinStore: TofuPinStore) -> Bool {
        isCleartextPermitted(host: host, allowedHosts: pinStore.cleartextHosts())
    }

    static func isCleartextPermitted(host: String, allowedHosts: Set<String>) -> Bool {
        NasAddress.isPrivateEligible(host: host) && allowedHosts.contains(host.lowercased())
    }

    static func leafFingerprint(trust: SecTrust) -> String? {
        guard let leaf = leafCertificate(trust: trust) else { return nil }
        return SHA256Hex.digest(data: SecCertificateCopyData(leaf) as Data)
    }

    static func leafCertificate(trust: SecTrust) -> SecCertificate? {
        guard let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate] else { return nil }
        return chain.first
    }

    private func chainIsValidIgnoringHostname(trust: SecTrust) -> Bool {
        let basic = SecPolicyCreateBasicX509()
        SecTrustSetPolicies(trust, basic)
        var error: CFError?
        return SecTrustEvaluateWithError(trust, &error)
    }
}
