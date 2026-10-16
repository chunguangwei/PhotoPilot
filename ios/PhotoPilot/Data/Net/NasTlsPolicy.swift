import Foundation
import Security

enum NasTransportError: Error, Equatable {
    case certUntrusted(fingerprint: String, subject: String?, validFrom: Int?, validTo: Int?)
    case certHostnameMismatch(fingerprint: String)
    case fingerprintChanged(fingerprint: String)
    case cleartextBlocked
    case certificateExpired
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
        if let nsError = evaluationError as NSError?,
           nsError.domain == NSOSStatusErrorDomain,
           nsError.code == Self.statusCertificateExpired
            || nsError.code == Self.statusCertificateNotYetValid {
            return .reject(.certificateExpired)
        }
        let hostnameMismatch = chainIsValidIgnoringHostname(trust: trust)
        let key = "\(host):\(port)"
        if let pin = pinStore?.pin(for: key) {
            return pin.fingerprint == fingerprint
                ? .allow
                : .reject(.fingerprintChanged(fingerprint: fingerprint))
        }
        if hostnameMismatch {
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
