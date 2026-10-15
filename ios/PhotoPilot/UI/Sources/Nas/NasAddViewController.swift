import UIKit

final class NasAddViewController: UIViewController, UITextFieldDelegate {
    private let environment: AppEnvironment

    private let schemeSegmented = UISegmentedControl()
    private let transportSegmented = UISegmentedControl()
    private var transportRow: UIView?
    private let shareField = UITextField()
    private var shareRow: UIView?
    private let hostField = UITextField()
    private let portField = UITextField()
    private let pathField = UITextField()
    private let usernameField = UITextField()
    private let passwordField = UITextField()
    private let testButton = ActionButton(titleKey: "source.nas.test_connection", style: .secondary)
    private let statusLabel = UILabel()
    private let saveButton = ActionButton(titleKey: "common.save", style: .primary)

    private var testing = false
    private var testedClient: WebDavClient?
    private var testedSmbAdapter: SmbSourceAdapter?
    private var testedEndpoint: NasEndpoint?
    private var testedCredential: NasCredential?

    init(environment: AppEnvironment) {
        self.environment = environment
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = ThemeColor.canvas

        let topBar = ScreenHeaderView(title: L10n.string("source.nas.title"))
        topBar.onBack = { [weak self] in self?.handleBack() }
        topBar.pinToTop(of: view)

        schemeSegmented.insertSegment(
            withTitle: L10n.string("source.nas.protocol_webdav"),
            at: 0,
            animated: false
        )
        schemeSegmented.insertSegment(
            withTitle: L10n.string("source.nas.protocol_smb"),
            at: 1,
            animated: false
        )
        schemeSegmented.selectedSegmentIndex = 0
        schemeSegmented.addTarget(self, action: #selector(handleSchemeChanged), for: .valueChanged)
        schemeSegmented.heightAnchor.constraint(
            greaterThanOrEqualToConstant: ThemeMetrics.touchTarget
        ).isActive = true

        transportSegmented.insertSegment(
            withTitle: L10n.string("source.nas.transport_https"),
            at: 0,
            animated: false
        )
        transportSegmented.insertSegment(
            withTitle: L10n.string("source.nas.transport_http"),
            at: 1,
            animated: false
        )
        transportSegmented.selectedSegmentIndex = 0
        transportSegmented.addTarget(self, action: #selector(handleTransportChanged), for: .valueChanged)
        transportSegmented.heightAnchor.constraint(
            greaterThanOrEqualToConstant: ThemeMetrics.touchTarget
        ).isActive = true
        transportRow = makeRow(labelKey: "source.nas.transport", control: transportSegmented)

        configure(field: hostField, labelKey: "source.nas.host", keyboard: .URL)
        configure(field: portField, labelKey: "source.nas.port", keyboard: .numberPad)
        portField.text = "443"
        configure(field: pathField, labelKey: "source.nas.path", keyboard: .default)
        configure(field: shareField, labelKey: "source.nas.share", keyboard: .default)
        shareField.addTarget(self, action: #selector(handleFieldChanged), for: .editingChanged)
        shareRow = makeRow(labelKey: "source.nas.share", control: shareField)
        shareRow?.isHidden = true
        configure(field: usernameField, labelKey: "source.nas.username", keyboard: .default)
        configure(field: passwordField, labelKey: "source.nas.password", keyboard: .default)
        passwordField.isSecureTextEntry = true
        let revealButton = UIButton(type: .system)
        revealButton.setImage(UIImage(systemName: "eye"), for: .normal)
        revealButton.tintColor = ThemeColor.textSecondary
        revealButton.accessibilityLabel = L10n.string("source.nas.password")
        revealButton.addTarget(self, action: #selector(handleRevealPassword), for: .touchUpInside)
        revealButton.frame = CGRect(
            x: 0,
            y: 0,
            width: ThemeMetrics.touchTarget,
            height: ThemeMetrics.touchTarget
        )
        passwordField.rightView = revealButton
        passwordField.rightViewMode = .always

        pathField.addTarget(self, action: #selector(handlePathChanged), for: .editingChanged)
        hostField.addTarget(self, action: #selector(handleFieldChanged), for: .editingChanged)
        portField.addTarget(self, action: #selector(handleFieldChanged), for: .editingChanged)
        usernameField.addTarget(self, action: #selector(handleFieldChanged), for: .editingChanged)
        passwordField.addTarget(self, action: #selector(handleFieldChanged), for: .editingChanged)

        testButton.addTarget(self, action: #selector(handleTest), for: .touchUpInside)
        saveButton.addTarget(self, action: #selector(handleSave), for: .touchUpInside)
        saveButton.isEnabled = false
        saveButton.alpha = ThemeMetrics.controlOpacity

        statusLabel.font = ThemeFont.meta()
        statusLabel.adjustsFontForContentSizeCategory = true
        statusLabel.numberOfLines = 0
        statusLabel.textAlignment = .center

        let form = UIStackView(arrangedSubviews: [
            makeRow(labelKey: "source.nas.protocol", control: schemeSegmented),
            transportRow ?? UIView(),
            makeRow(labelKey: "source.nas.host", control: hostField),
            makeRow(labelKey: "source.nas.port", control: portField),
            makeRow(labelKey: "source.nas.path", control: pathField),
            shareRow ?? UIView(),
            makeRow(labelKey: "source.nas.username", control: usernameField),
            makeRow(labelKey: "source.nas.password", control: passwordField),
            testButton,
            statusLabel,
            saveButton,
        ])
        form.axis = .vertical
        form.spacing = ThemeMetrics.space12
        form.translatesAutoresizingMaskIntoConstraints = false

        let scrollView = UIScrollView()
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        scrollView.addSubview(form)
        view.addSubview(scrollView)

        let readable = form.widthAnchor.constraint(equalToConstant: ThemeMetrics.bodyMaxWidth)
        readable.priority = .defaultHigh
        NSLayoutConstraint.activate([
            scrollView.topAnchor.constraint(equalTo: topBar.bottomAnchor, constant: ThemeMetrics.space8),
            scrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: view.keyboardLayoutGuide.topAnchor),
            form.topAnchor.constraint(
                equalTo: scrollView.contentLayoutGuide.topAnchor,
                constant: ThemeMetrics.space8
            ),
            form.bottomAnchor.constraint(
                equalTo: scrollView.contentLayoutGuide.bottomAnchor,
                constant: -ThemeMetrics.space24
            ),
            form.centerXAnchor.constraint(equalTo: scrollView.frameLayoutGuide.centerXAnchor),
            form.leadingAnchor.constraint(
                greaterThanOrEqualTo: scrollView.frameLayoutGuide.leadingAnchor,
                constant: ThemeMetrics.contentInset
            ),
            form.trailingAnchor.constraint(
                lessThanOrEqualTo: scrollView.frameLayoutGuide.trailingAnchor,
                constant: -ThemeMetrics.contentInset
            ),
            readable,
        ])
        enableKeyboardDismissal()
    }

    private func configure(field: UITextField, labelKey: String, keyboard: UIKeyboardType) {
        field.font = ThemeFont.body()
        field.adjustsFontForContentSizeCategory = true
        field.textColor = ThemeColor.textPrimary
        field.borderStyle = .roundedRect
        field.keyboardType = keyboard
        field.autocapitalizationType = .none
        field.autocorrectionType = .no
        field.delegate = self
        field.accessibilityLabel = L10n.string(labelKey)
        field.heightAnchor.constraint(
            greaterThanOrEqualToConstant: ThemeMetrics.touchTarget
        ).isActive = true
        field.setContentHuggingPriority(.defaultLow, for: .horizontal)
    }

    private func makeRow(labelKey: String, control: UIView) -> UIView {
        let label = UILabel()
        label.text = L10n.string(labelKey)
        label.font = ThemeFont.body()
        label.adjustsFontForContentSizeCategory = true
        label.textColor = ThemeColor.textPrimary
        label.setContentHuggingPriority(.defaultHigh, for: .horizontal)
        label.widthAnchor.constraint(equalToConstant: ThemeMetrics.railWidth).isActive = true
        let row = UIStackView(arrangedSubviews: [label, control])
        row.axis = .horizontal
        row.alignment = .center
        row.spacing = ThemeMetrics.space12
        return row
    }

    private var isSmbSelected: Bool {
        schemeSegmented.selectedSegmentIndex == 1
    }

    private func buildEndpoint() -> NasEndpoint? {
        guard let hostInput = NasAddress.parseHostInput(hostField.text ?? "") else { return nil }
        let isHTTPS = transportSegmented.selectedSegmentIndex == 0
        let scheme = isSmbSelected ? "smb" : (isHTTPS ? "webdav" : "webdav_http")
        let port = hostInput.port
            ?? Int(portField.text ?? "")
            ?? NasAddress.defaultPort(scheme)
        guard let resolvedPort = port else { return nil }
        guard let path = NasPath.normalize(pathField.text ?? "") else { return nil }
        if isSmbSelected {
            guard let share = NasPath.normalize(shareField.text ?? ""), !share.isEmpty else {
                return nil
            }
            return NasEndpoint(scheme: scheme, host: hostInput.host, port: resolvedPort, path: share)
        }
        return NasEndpoint(
            scheme: scheme,
            host: hostInput.host,
            port: resolvedPort,
            path: path
        )
    }

    private func makeClient(endpoint: NasEndpoint, credential: NasCredential) -> WebDavClient {
        var components = URLComponents()
        components.scheme = endpoint.scheme == "webdav" ? "https" : "http"
        components.host = NasAddress.urlHost(endpoint.host)
        components.port = endpoint.port
        components.path = "/" + endpoint.path
        let pins = environment.tofuPins
        let permitted = endpoint.scheme == "webdav_http"
            && NasTlsPolicy.isCleartextPermitted(host: endpoint.host, pinStore: pins)
        let (session, policy) = WebDavClient.makeSession(pinStore: pins)
        return WebDavClient(
            config: WebDavClient.Config(
                baseURL: components.url ?? URL(fileURLWithPath: "/"),
                username: credential.username,
                password: credential.password,
                cleartextPermitted: permitted
            ),
            session: session,
            tlsPolicy: policy
        )
    }

    @objc private func handleSchemeChanged() {
        if isSmbSelected {
            portField.text = "445"
        } else {
            portField.text = transportSegmented.selectedSegmentIndex == 0 ? "443" : "80"
        }
        shareRow?.isHidden = !isSmbSelected
        transportRow?.isHidden = isSmbSelected
        markTestStale()
    }

    @objc private func handleTransportChanged() {
        portField.text = transportSegmented.selectedSegmentIndex == 0 ? "443" : "80"
        markTestStale()
    }

    @objc private func handlePathChanged() {
        let valid = NasPath.normalize(pathField.text ?? "") != nil
        pathField.layer.borderColor = valid
            ? ThemeColor.separator.cgColor
            : ThemeColor.statusError.cgColor
        pathField.layer.borderWidth = valid ? 0 : NavChromeMetrics.selectionIndicator
        markTestStale()
    }

    @objc private func handleFieldChanged() {
        markTestStale()
    }

    private func markTestStale() {
        testedClient = nil
        testedSmbAdapter = nil
        testedEndpoint = nil
        saveButton.isEnabled = false
        saveButton.alpha = ThemeMetrics.controlOpacity
    }

    @objc private func handleRevealPassword() {
        passwordField.isSecureTextEntry.toggle()
    }

    @objc private func handleTest() {
        guard !testing, let endpoint = buildEndpoint() else { return }
        if isSmbSelected {
            handleSmbTest(endpoint: endpoint)
            return
        }
        testing = true
        statusLabel.text = L10n.string("source.nas.testing")
        statusLabel.textColor = ThemeColor.textSecondary
        let credential = NasCredential(
            scheme: endpoint.scheme,
            host: endpoint.host,
            port: endpoint.port,
            path: endpoint.path,
            username: usernameField.text ?? "",
            password: passwordField.text ?? ""
        )
        let client = makeClient(endpoint: endpoint, credential: credential)
        Task { [weak self] in
            guard let self else { return }
            defer { testing = false }
            do {
                _ = try await client.propfind(path: endpoint.path, depth: 0)
                testedClient = client
                testedEndpoint = endpoint
                testedCredential = credential
                let secure = endpoint.scheme == "webdav"
                statusLabel.text = L10n.string("source.nas.test_ok")
                    + " · "
                    + L10n.string(secure ? "source.nas.security_encrypted" : "source.nas.security_plain")
                statusLabel.textColor = ThemeColor.statusSuccess
                saveButton.isEnabled = true
                saveButton.alpha = 1
            } catch let error as NasTransportError {
                handleTestError(error, endpoint: endpoint, credential: credential)
            } catch {
                showError(key: "error.src_unavailable.body")
            }
        }
    }

    private func handleSmbTest(endpoint: NasEndpoint) {
        if !NasAddress.isPrivateEligible(host: endpoint.host) {
            present(NasCleartextViewController(
                host: endpoint.host,
                privateEligible: false
            ) {}, animated: true)
            return
        }
        if !environment.tofuPins.allowsCleartext(host: endpoint.host) {
            present(NasCleartextViewController(
                host: endpoint.host,
                privateEligible: true
            ) { [weak self] in
                self?.environment.tofuPins.setCleartext(host: endpoint.host, allowed: true)
                self?.handleTest()
            }, animated: true)
            return
        }
        testing = true
        statusLabel.text = L10n.string("source.nas.testing")
        statusLabel.textColor = ThemeColor.textSecondary
        let credential = NasCredential(
            scheme: "smb",
            host: endpoint.host,
            port: endpoint.port,
            path: endpoint.path,
            username: usernameField.text ?? "",
            password: passwordField.text ?? ""
        )
        let adapter = SmbSourceAdapter(
            config: SmbConfig(
                host: endpoint.host,
                port: endpoint.port,
                share: endpoint.path,
                username: credential.username,
                password: credential.password
            ),
            directories: []
        )
        Task { [weak self] in
            guard let self else { return }
            defer { testing = false }
            do {
                _ = try await adapter.enumerateAssets(cursor: nil)
                testedSmbAdapter = adapter
                testedEndpoint = endpoint
                testedCredential = credential
                statusLabel.text = L10n.string("source.nas.test_ok")
                    + " · "
                    + L10n.string(adapter.sessionEncrypted
                            ? "source.nas.security_encrypted" : "source.nas.security_plain")
                statusLabel.textColor = ThemeColor.statusSuccess
                saveButton.isEnabled = true
                saveButton.alpha = 1
            } catch let error as NasTransportError {
                handleTestError(error, endpoint: endpoint, credential: credential)
            } catch {
                showError(key: "error.src_unavailable.body")
            }
        }
    }

    private func handleTestError(
        _ error: NasTransportError,
        endpoint: NasEndpoint,
        credential: NasCredential
    ) {
        let hostPort = "\(endpoint.host):\(endpoint.port)"
        switch error {
        case .certUntrusted(let fingerprint, let subject, _, _):
            present(NasCertTrustViewController(
                mode: .untrusted(fingerprint: fingerprint, subject: subject)
            ) { [weak self] in
                self?.environment.tofuPins.savePin(
                    hostPort: hostPort,
                    fingerprint: fingerprint,
                    confirmed: true
                )
                self?.handleTest()
            }, animated: true)
        case .certHostnameMismatch(let fingerprint):
            present(NasCertTrustViewController(
                mode: .hostnameMismatch(fingerprint: fingerprint)
            ) { [weak self] in
                self?.environment.tofuPins.savePin(
                    hostPort: hostPort,
                    fingerprint: fingerprint,
                    confirmed: true
                )
                self?.handleTest()
            }, animated: true)
        case .fingerprintChanged(let fingerprint):
            present(NasCertTrustViewController(
                mode: .changed(fingerprint: fingerprint)
            ) { [weak self] in
                self?.environment.tofuPins.savePin(
                    hostPort: hostPort,
                    fingerprint: fingerprint,
                    confirmed: true
                )
                self?.handleTest()
            }, animated: true)
        case .cleartextBlocked:
            present(NasCleartextViewController(
                host: endpoint.host,
                privateEligible: NasAddress.isPrivateEligible(host: endpoint.host)
            ) { [weak self] in
                self?.environment.tofuPins.setCleartext(host: endpoint.host, allowed: true)
                self?.handleTest()
            }, animated: true)
        case .certificateExpired:
            showError(key: "error.src_unavailable.body")
        case .authFailed:
            showError(key: "error.src_auth_failed.body")
        case .pathNotFound:
            showError(key: "error.src_path_not_found.body")
        case .localNetworkDenied:
            showError(key: "permission.local_network.denied_body")
            statusLabel.textColor = ThemeColor.statusError
        case .assetGone, .unavailable, .protocolUnsupported, .permissionDenied:
            showError(key: "error.src_unavailable.body")
        }
    }

    private func showError(key: String) {
        statusLabel.text = L10n.string(key)
        statusLabel.textColor = ThemeColor.statusError
    }

    @objc private func handleSave() {
        guard let endpoint = testedEndpoint else { return }
        let listFolders: @Sendable (String) async throws -> [String]
        var rootPath = endpoint.path
        if isSmbSelected, let adapter = testedSmbAdapter {
            rootPath = ""
            listFolders = { path in
                try await adapter.listSubdirectories(path: path)
            }
        } else if let client = testedClient {
            listFolders = { path in
                let entries = try await client.propfind(path: path, depth: 1)
                return entries
                    .filter { $0.isCollection && $0.path != path }
                    .map(\.path)
                    .sorted()
            }
        } else {
            return
        }
        let picker = NasFolderPickerViewController(
            listFolders: listFolders,
            path: rootPath,
            selection: DirectorySelection()
        ) { [weak self] directories, recursive in
            self?.finish(directories: directories, recursive: recursive)
        }
        navigationController?.pushViewController(picker, animated: true)
    }

    private func finish(directories: [String], recursive: Bool) {
        guard let endpoint = testedEndpoint,
              let credential = testedCredential,
              let normalized = NasScope.normalizeScope(directories) else { return }
        let fingerprint = NasAddress.sourceFingerprint(
            scheme: endpoint.scheme,
            host: endpoint.host,
            port: endpoint.port,
            path: endpoint.path
        )
        Task { [weak self] in
            guard let self else { return }
            do {
                let sourceID = isSmbSelected
                    ? try environment.sources.ensureSmbSource(
                        endpointKey: fingerprint,
                        displayName: endpoint.host,
                        recursive: recursive,
                        directories: normalized
                    )
                    : try environment.sources.ensureWebdavSource(
                        endpointKey: fingerprint,
                        displayName: endpoint.host,
                        recursive: recursive,
                        directories: normalized
                    )
                _ = environment.credentialStore.save(
                    fingerprint: fingerprint,
                    credential: credential
                )
                let runner = isSmbSelected
                    ? try await environment.startSmbImport(sourceID: sourceID)
                    : try await environment.startWebDavImport(sourceID: sourceID)
                navigationController?.pushViewController(
                    ImportProgressViewController(runner: runner, environment: environment),
                    animated: true
                )
            } catch {
                showError(key: "error.src_unavailable.body")
            }
        }
    }

    @objc private func handleBack() {
        if let navigationController, navigationController.viewControllers.first != self {
            navigationController.popViewController(animated: true)
        } else {
            dismiss(animated: true)
        }
    }

    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        textField.resignFirstResponder()
        return true
    }
}

final class DirectorySelection {
    var paths: Set<String> = []
    var recursive = false
}
