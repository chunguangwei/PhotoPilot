import UIKit

final class NasAddViewController: UIViewController, UITextFieldDelegate {
    private let environment: AppEnvironment

    private let schemeSegmented = UISegmentedControl()
    private let transportSegmented = UISegmentedControl()
    private let httpsButton = ActionButton(titleKey: "source.nas.transport_https", style: .secondary)
    private let httpButton = ActionButton(titleKey: "source.nas.transport_http", style: .secondary)
    private var adaptiveRows: [(UIStackView, NSLayoutConstraint)] = []
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
    private let saveButton = ActionButton(titleKey: "common.next", style: .primary)
    private weak var formScrollView: UIScrollView?
    private weak var focusedField: UIView?

    private var testing = false
    private var pendingContinue = false
    private var testedClient: WebDavClient?
    private var testedSmbAdapter: SmbSourceAdapter?
    private var testedEndpoint: NasEndpoint?
    private var testedCredential: NasCredential?
    private let prefillEndpoint: NasEndpoint?
    private let prefillCredential: NasCredential?
    private let prefillDirectories: [String]
    private let prefillRecursive: Bool

    init(
        environment: AppEnvironment,
        prefillEndpoint: NasEndpoint? = nil,
        prefillCredential: NasCredential? = nil,
        prefillDirectories: [String] = [],
        prefillRecursive: Bool = false
    ) {
        self.environment = environment
        self.prefillEndpoint = prefillEndpoint
        self.prefillCredential = prefillCredential
        self.prefillDirectories = prefillDirectories
        self.prefillRecursive = prefillRecursive
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = ThemeColor.canvas

        let backButton = ActionButton(titleKey: "common.back", style: .secondary)
        backButton.addTarget(self, action: #selector(handleBack), for: .touchUpInside)
        let titleLabel = UILabel()
        titleLabel.text = L10n.string("source.nas.title")
        titleLabel.font = ThemeFont.heading()
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = ThemeColor.textPrimary
        titleLabel.numberOfLines = 0
        titleLabel.accessibilityTraits.insert(.header)
        let topBar = UIStackView(arrangedSubviews: [backButton, titleLabel, UIView()])
        topBar.axis = .horizontal
        topBar.alignment = .center
        topBar.spacing = ThemeMetrics.space12
        topBar.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(topBar)

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
        httpsButton.addTarget(self, action: #selector(selectHTTPS), for: .touchUpInside)
        httpButton.addTarget(self, action: #selector(selectHTTP), for: .touchUpInside)
        let transportChoices = UIStackView(arrangedSubviews: [httpsButton, httpButton])
        transportChoices.axis = .vertical
        transportChoices.spacing = ThemeMetrics.space8
        transportRow = makeRow(labelKey: "source.nas.transport", control: transportChoices)
        updateTransportSelection()

        configure(field: hostField, labelKey: "source.nas.host", keyboard: .URL)
        configure(field: portField, labelKey: "source.nas.port", keyboard: .numberPad)
        portField.text = "443"
        configure(field: pathField, labelKey: "source.nas.path", keyboard: .default)
        pathField.placeholder = L10n.string("source.nas.path_optional_hint")
        configure(field: shareField, labelKey: "source.nas.share", keyboard: .default)
        shareField.placeholder = L10n.string("source.nas.share_optional_hint")
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

        statusLabel.font = ThemeFont.meta()
        statusLabel.adjustsFontForContentSizeCategory = true
        statusLabel.numberOfLines = 0
        statusLabel.textAlignment = .center

        let guestHint = UILabel()
        guestHint.text = L10n.string("source.nas.guest_hint")
        guestHint.font = ThemeFont.meta()
        guestHint.adjustsFontForContentSizeCategory = true
        guestHint.textColor = ThemeColor.textSecondary
        guestHint.numberOfLines = 0

        let form = UIStackView(arrangedSubviews: [
            makeRow(labelKey: "source.nas.protocol", control: schemeSegmented),
            transportRow ?? UIView(),
            makeRow(labelKey: "source.nas.host", control: hostField),
            makeRow(labelKey: "source.nas.port", control: portField),
            makeRow(labelKey: "source.nas.path", control: pathField),
            shareRow ?? UIView(),
            makeRow(labelKey: "source.nas.username", control: usernameField),
            makeRow(labelKey: "source.nas.password", control: passwordField),
            guestHint,
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

        let readable = form.widthAnchor.constraint(
            lessThanOrEqualToConstant: ThemeMetrics.bodyMaxWidth
        )
        let fillWidth = form.widthAnchor.constraint(
            equalTo: scrollView.frameLayoutGuide.widthAnchor,
            constant: -2 * ThemeMetrics.contentInsetCompact
        )
        fillWidth.priority = UILayoutPriority(999)
        NSLayoutConstraint.activate([
            topBar.topAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.topAnchor,
                constant: ThemeMetrics.space8
            ),
            topBar.leadingAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.leadingAnchor,
                constant: ThemeMetrics.contentInsetCompact
            ),
            topBar.trailingAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.trailingAnchor,
                constant: -ThemeMetrics.contentInsetCompact
            ),
            scrollView.topAnchor.constraint(equalTo: topBar.bottomAnchor, constant: ThemeMetrics.space8),
            scrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
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
                constant: ThemeMetrics.contentInsetCompact
            ),
            form.trailingAnchor.constraint(
                lessThanOrEqualTo: scrollView.frameLayoutGuide.trailingAnchor,
                constant: -ThemeMetrics.contentInsetCompact
            ),
            readable,
            fillWidth
        ])
        formScrollView = scrollView
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleKeyboardWillChange(_:)),
            name: UIResponder.keyboardWillChangeFrameNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleFieldDidBegin(_:)),
            name: UITextField.textDidBeginEditingNotification,
            object: nil
        )
        enableKeyboardDismissal()
        applyPrefill()
        updateTransportSelection()
    }

    override func viewWillLayoutSubviews() {
        super.viewWillLayoutSubviews()
        let stacked = view.bounds.width < 600 || traitCollection.preferredContentSizeCategory.isAccessibilityCategory
        for (row, width) in adaptiveRows {
            width.isActive = !stacked
            row.axis = stacked ? .vertical : .horizontal
            row.alignment = stacked ? .fill : .center
        }
    }

    @objc private func selectHTTPS() {
        transportSegmented.selectedSegmentIndex = 0
        handleTransportChanged()
        updateTransportSelection()
    }

    @objc private func selectHTTP() {
        transportSegmented.selectedSegmentIndex = 1
        handleTransportChanged()
        updateTransportSelection()
    }

    private func updateTransportSelection() {
        httpsButton.isOptionSelected = transportSegmented.selectedSegmentIndex == 0
        httpButton.isOptionSelected = transportSegmented.selectedSegmentIndex == 1
    }

    @objc private func handleFieldDidBegin(_ notification: Notification) {
        guard let field = notification.object as? UITextField, field.isDescendant(of: view) else { return }
        focusedField = field
        DispatchQueue.main.async { [weak self] in
            self?.scrollFocusedFieldAboveKeyboard()
        }
    }

    @objc private func handleKeyboardWillChange(_ notification: Notification) {
        guard let scrollView = formScrollView,
              let frame = notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect,
              let duration = notification.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double
        else { return }
        let endFrame = view.convert(frame, from: view.window)
        let overlap = max(0, view.bounds.maxY - endFrame.minY)
        let bottomInset = overlap > 0 ? overlap + ThemeMetrics.space8 : 0
        UIView.animate(withDuration: duration) {
            scrollView.contentInset.bottom = bottomInset
            scrollView.verticalScrollIndicatorInsets.bottom = bottomInset
        }
        scrollFocusedFieldAboveKeyboard()
    }

    private func scrollFocusedFieldAboveKeyboard() {
        guard let scrollView = formScrollView, let field = focusedField else { return }
        scrollView.layoutIfNeeded()
        let rect = field.convert(field.bounds, to: scrollView).insetBy(dx: 0, dy: -ThemeMetrics.space8)
        let visibleBottom = scrollView.contentOffset.y
            + scrollView.bounds.height
            - scrollView.contentInset.bottom
        guard rect.maxY > visibleBottom else { return }
        var offset = scrollView.contentOffset
        offset.y += rect.maxY - visibleBottom
        scrollView.setContentOffset(offset, animated: true)
    }

    private func applyPrefill() {
        guard let endpoint = prefillEndpoint else { return }
        schemeSegmented.selectedSegmentIndex = endpoint.scheme == "smb" ? 1 : 0
        transportSegmented.selectedSegmentIndex = endpoint.scheme == "webdav_http" ? 1 : 0
        shareRow?.isHidden = !isSmbSelected
        transportRow?.isHidden = isSmbSelected
        hostField.text = endpoint.host
        portField.text = "\(endpoint.port)"
        if isSmbSelected {
            let split = NasPath.splitShare(endpoint.path)
            shareField.text = split.share
            pathField.text = split.subpath
        } else {
            pathField.text = endpoint.path
        }
        if let credential = prefillCredential {
            usernameField.text = credential.username
            passwordField.text = credential.password
        }
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
        label.numberOfLines = 0
        let labelWidth = label.widthAnchor.constraint(equalToConstant: ThemeMetrics.railWidth)
        let row = UIStackView(arrangedSubviews: [label, control])
        row.axis = .horizontal
        row.alignment = .center
        row.spacing = ThemeMetrics.space12
        adaptiveRows.append((row, labelWidth))
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
            guard let shareInput = NasPath.normalize(shareField.text ?? "") else {
                return nil
            }
            let split = NasPath.splitShare(shareInput)
            let subpath = [split.subpath, path].filter { !$0.isEmpty }.joined(separator: "/")
            let combined = [split.share, subpath].filter { !$0.isEmpty }.joined(separator: "/")
            return NasEndpoint(
                scheme: scheme,
                host: hostInput.host,
                port: resolvedPort,
                path: combined
            )
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
    }

    @objc private func handleRevealPassword() {
        passwordField.isSecureTextEntry.toggle()
    }

    @objc private func handleTest() {
        guard !testing else { return }
        guard let endpoint = buildEndpoint() else {
            pendingContinue = false
            return
        }
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
                _ = try await client.propfind(path: "", depth: 0)
                testedClient = client
                testedEndpoint = endpoint
                testedCredential = credential
                let secure = endpoint.scheme == "webdav"
                statusLabel.text = L10n.string("source.nas.test_ok")
                    + " · "
                    + L10n.string(secure ? "source.nas.security_encrypted" : "source.nas.security_plain")
                statusLabel.textColor = ThemeColor.statusSuccess
                continueAfterTestIfNeeded(endpoint: endpoint)
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
        let split = NasPath.splitShare(endpoint.path)
        let adapter = SmbSourceAdapter(
            config: SmbConfig(
                host: endpoint.host,
                port: endpoint.port,
                share: split.share,
                username: credential.username,
                password: credential.password
            ),
            directories: []
        )
        Task { [weak self] in
            guard let self else { return }
            defer { testing = false }
            do {
                if split.share.isEmpty {
                    _ = try await adapter.listShares()
                } else {
                    _ = try await adapter.enumerateAssets(cursor: nil)
                    if !split.subpath.isEmpty {
                        _ = try await adapter.listSubdirectories(path: split.subpath)
                    }
                }
                testedSmbAdapter = adapter
                testedEndpoint = endpoint
                testedCredential = credential
                statusLabel.text = L10n.string("source.nas.test_ok")
                    + " · "
                    + L10n.string(adapter.sessionEncrypted
                            ? "source.nas.security_encrypted" : "source.nas.security_plain")
                statusLabel.textColor = ThemeColor.statusSuccess
                continueAfterTestIfNeeded(endpoint: endpoint)
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
            guard !fingerprint.isEmpty else {
                showError(key: "error.src_unavailable.body")
                return
            }
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
            guard !fingerprint.isEmpty else {
                showError(key: "error.src_unavailable.body")
                return
            }
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
            guard !fingerprint.isEmpty else {
                showError(key: "error.src_unavailable.body")
                return
            }
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
        case .shareNotFound:
            showError(key: "source.nas.share_not_found")
        case .unreachable:
            showError(key: "error.src_unreachable.body")
        case .hostUnknown:
            showError(key: "error.src_host_unknown.body")
        case .timeout:
            showError(key: "error.net_timeout.body")
        case .protocolMismatch:
            showError(key: "error.src_protocol_mismatch.body")
        case .notWebDav:
            showError(key: "error.src_not_webdav.body")
        case .localNetworkDenied:
            showError(key: "permission.local_network.denied_body")
            statusLabel.textColor = ThemeColor.statusError
        case .assetGone, .unavailable, .protocolUnsupported, .permissionDenied:
            showError(key: "error.src_unavailable.body")
        }
    }

    private func showError(key: String) {
        pendingContinue = false
        statusLabel.text = L10n.string(key)
        statusLabel.textColor = ThemeColor.statusError
    }

    @objc private func handleSave() {
        guard let endpoint = testedEndpoint else {
            pendingContinue = true
            handleTest()
            return
        }
        pendingContinue = false
        openFolderPicker(endpoint: endpoint)
    }

    private func continueAfterTestIfNeeded(endpoint: NasEndpoint) {
        guard pendingContinue else { return }
        pendingContinue = false
        openFolderPicker(endpoint: endpoint)
    }

    private func openFolderPicker(endpoint: NasEndpoint) {
        let listFolders: @Sendable (String) async throws -> [String]
        var rootPath = ""
        if isSmbSelected, let adapter = testedSmbAdapter {
            let split = NasPath.splitShare(endpoint.path)
            rootPath = split.share.isEmpty ? "" : split.subpath
            listFolders = { path in
                try await adapter.listFolders(path: path)
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
        let preselected = DirectorySelection()
        preselected.paths = Set(prefillDirectories)
        preselected.recursive = prefillRecursive
        let picker = NasFolderPickerViewController(
            listFolders: listFolders,
            path: rootPath,
            selection: preselected
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
                if let prefillEndpoint {
                    let oldKey = NasAddress.sourceFingerprint(
                        scheme: prefillEndpoint.scheme,
                        host: prefillEndpoint.host,
                        port: prefillEndpoint.port,
                        path: prefillEndpoint.path
                    )
                    if oldKey != fingerprint {
                        if let old = try? environment.sources.webdavSources()
                            .first(where: { $0.endpointKey == oldKey }) {
                            try? await environment.assets.disconnectSource(
                                sourceID: old.id,
                                keepCopies: true
                            )
                        }
                        environment.credentialStore.delete(fingerprint: oldKey)
                    }
                }
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
