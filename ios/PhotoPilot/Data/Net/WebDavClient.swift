import Foundation

struct WebDavEntry: Sendable, Equatable {
    let path: String
    let etag: String?
    let contentLength: Int?
    let lastModifiedMillis: Int?
    let isCollection: Bool
}

enum WebDavMultistatusParser {
    static func parse(data: Data, basePath: String) -> [WebDavEntry] {
        let delegate = MultistatusDelegate()
        let parser = XMLParser(data: data)
        parser.delegate = delegate
        guard parser.parse() else { return [] }
        return delegate.entries.map { raw in
            WebDavEntry(
                path: relativePath(href: raw.href, basePath: basePath),
                etag: raw.etag,
                contentLength: raw.contentLength,
                lastModifiedMillis: raw.lastModified.flatMap(httpDateMillis),
                isCollection: raw.isCollection
            )
        }
    }

    static func relativePath(href: String, basePath: String) -> String {
        let decoded = href.removingPercentEncoding ?? href
        var path = decoded
        if !basePath.isEmpty, path.hasPrefix(basePath) {
            let remainder = path.dropFirst(basePath.count)
            if remainder.isEmpty || remainder.hasPrefix("/") {
                path = String(remainder)
            }
        }
        return NasPath.normalize(path) ?? ""
    }

    static func httpDateMillis(_ text: String) -> Int? {
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "GMT")
        formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss 'GMT'"
        guard let date = formatter.date(from: trimmed) else { return nil }
        return Int(date.timeIntervalSince1970 * 1000)
    }

    private struct RawEntry {
        var href = ""
        var etag: String?
        var contentLength: Int?
        var lastModified: String?
        var isCollection = false
    }

    private final class MultistatusDelegate: NSObject, XMLParserDelegate {
        var entries: [RawEntry] = []
        private var current: RawEntry?
        private var currentElement = ""
        private var buffer = ""

        func parser(
            _ parser: XMLParser,
            didStartElement elementName: String,
            namespaceURI: String?,
            qualifiedName qName: String?,
            attributes attributeDict: [String: String]
        ) {
            let local = Self.localName(elementName)
            currentElement = local
            buffer = ""
            if local == "response" {
                current = RawEntry()
            } else if local == "collection", current != nil {
                current?.isCollection = true
            }
        }

        func parser(_ parser: XMLParser, foundCharacters string: String) {
            buffer += string
        }

        func parser(
            _ parser: XMLParser,
            didEndElement elementName: String,
            namespaceURI: String?,
            qualifiedName qName: String?
        ) {
            let local = Self.localName(elementName)
            let text = buffer.trimmingCharacters(in: .whitespacesAndNewlines)
            switch local {
            case "response":
                if let entry = current, !entry.href.isEmpty {
                    entries.append(entry)
                }
                current = nil
            case "href" where current != nil && current!.href.isEmpty:
                current?.href = text
            case "getetag" where current?.etag == nil:
                current?.etag = text.isEmpty ? nil : text
            case "getcontentlength" where current?.contentLength == nil:
                current?.contentLength = Int(text)
            case "getlastmodified" where current?.lastModified == nil:
                current?.lastModified = text.isEmpty ? nil : text
            default:
                break
            }
            buffer = ""
        }

        private static func localName(_ elementName: String) -> String {
            String(elementName.split(separator: ":").last ?? "")
        }
    }
}

final class WebDavClient: @unchecked Sendable {
    struct Config: Sendable {
        let baseURL: URL
        let username: String
        let password: String
        let cleartextPermitted: Bool
    }

    static func makeSession(pinStore: TofuPinStore?) -> (URLSession, NasTlsPolicy) {
        let policy = NasTlsPolicy(pinStore: pinStore)
        let configuration = URLSessionConfiguration.ephemeral
        let session = URLSession(configuration: configuration, delegate: policy, delegateQueue: nil)
        return (session, policy)
    }

    private let config: Config
    private let session: URLSession
    private let tlsPolicy: NasTlsPolicy?

    init(config: Config, session: URLSession, tlsPolicy: NasTlsPolicy? = nil) {
        self.config = config
        self.session = session
        self.tlsPolicy = tlsPolicy
    }

    func propfind(path: String, depth: Int) async throws -> [WebDavEntry] {
        let request = try makeRequest(
            method: "PROPFIND",
            path: path,
            headers: ["Depth": "\(depth)", "Content-Type": "application/xml; charset=utf-8"],
            body: Self.propfindBody
        )
        let (data, response) = try await perform(request)
        try validate(response: response, isDownload: false)
        return WebDavMultistatusParser.parse(
            data: data,
            basePath: config.baseURL.path
        )
    }

    func download(path: String, to fileURL: URL) async throws -> Int {
        let request = try makeRequest(method: "GET", path: path, headers: [:], body: nil)
        let (tempURL, response) = try await performDownload(request)
        try validate(response: response, isDownload: true)
        let fileManager = FileManager.default
        try fileManager.createDirectory(
            at: fileURL.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        if fileManager.fileExists(atPath: fileURL.path) {
            try fileManager.removeItem(at: fileURL)
        }
        try fileManager.moveItem(at: tempURL, to: fileURL)
        return (try? fileURL.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
    }

    private func makeRequest(
        method: String,
        path: String,
        headers: [String: String],
        body: Data?
    ) throws -> URLRequest {
        var components = URLComponents(
            url: config.baseURL,
            resolvingAgainstBaseURL: false
        )
        let basePath = config.baseURL.path
        components?.path = basePath + (basePath.hasSuffix("/") ? "" : "/")
            + (path.hasPrefix("/") ? String(path.dropFirst()) : path)
        guard let url = components?.url else {
            throw NasTransportError.pathNotFound
        }
        if url.scheme == "http" {
            guard config.cleartextPermitted else {
                throw NasTransportError.cleartextBlocked
            }
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        for (key, value) in headers {
            request.setValue(value, forHTTPHeaderField: key)
        }
        request.httpBody = body
        if !config.username.isEmpty, url.scheme == "https" || config.cleartextPermitted {
            let raw = Data("\(config.username):\(config.password)".utf8)
            request.setValue(
                "Basic \(raw.base64EncodedString())",
                forHTTPHeaderField: "Authorization"
            )
        }
        return request
    }

    private func perform(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                throw NasTransportError.unavailable
            }
            return (data, http)
        } catch let error as URLError {
            throw map(urlError: error, url: request.url)
        }
    }

    private func performDownload(_ request: URLRequest) async throws -> (URL, HTTPURLResponse) {
        do {
            let (tempURL, response) = try await session.download(for: request)
            guard let http = response as? HTTPURLResponse else {
                throw NasTransportError.unavailable
            }
            return (tempURL, http)
        } catch let error as URLError {
            throw map(urlError: error, url: request.url)
        }
    }

    private func map(urlError: URLError, url: URL?) -> Error {
        if let url, let host = url.host,
           let policyError = tlsPolicy?.lastError(host: host, port: url.port ?? Self.defaultPort(url)) {
            return policyError
        }
        if urlError.code == .cannotConnectToHost {
            return NasTransportError.localNetworkDenied
        }
        return NasTransportError.unavailable
    }

    private func validate(response: HTTPURLResponse, isDownload: Bool) throws {
        switch response.statusCode {
        case 200...299:
            return
        case 401, 403:
            throw NasTransportError.authFailed
        case 404:
            throw isDownload ? NasTransportError.assetGone : NasTransportError.pathNotFound
        default:
            throw NasTransportError.unavailable
        }
    }

    private static func defaultPort(_ url: URL) -> Int {
        url.scheme == "https" ? 443 : 80
    }

    private static let propfindBody = Data("""
    <?xml version="1.0" encoding="utf-8"?>
    <propfind xmlns="DAV:"><prop><getetag/><getcontentlength/><getlastmodified/><resourcetype/></prop></propfind>
    """.utf8)
}
