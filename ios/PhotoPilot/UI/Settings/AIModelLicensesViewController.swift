import UIKit

final class AIModelLicensesViewController: UIViewController {
    private let content = UITextView()
    private let pageLabel = UILabel()
    private let previousButton = UIButton(type: .system)
    private let nextButton = UIButton(type: .system)
    private var pages: [String] = []
    private var pageIndex = 0
    private var loadTask: Task<Void, Never>?

    override func viewDidLoad() {
        super.viewDidLoad()
        title = AIText.value("AI model and runtime licenses", "AI 模型与推理组件许可")
        view.backgroundColor = ThemeColor.canvas
        navigationItem.leftBarButtonItem = UIBarButtonItem(title: L10n.string("common.back"), style: .plain, target: self, action: #selector(close))
        content.isEditable = false
        content.font = .preferredFont(forTextStyle: .footnote)
        content.adjustsFontForContentSizeCategory = true
        content.backgroundColor = .clear
        content.text = AIText.value("Loading licenses…", "正在载入许可…")
        content.translatesAutoresizingMaskIntoConstraints = false
        previousButton.setTitle(AIText.value("Previous", "上一页"), for: .normal)
        nextButton.setTitle(AIText.value("Next", "下一页"), for: .normal)
        previousButton.addTarget(self, action: #selector(previousPage), for: .touchUpInside)
        nextButton.addTarget(self, action: #selector(nextPage), for: .touchUpInside)
        pageLabel.font = .preferredFont(forTextStyle: .footnote)
        pageLabel.adjustsFontForContentSizeCategory = true
        pageLabel.textAlignment = .center
        pageLabel.numberOfLines = 0
        let controls = UIStackView(arrangedSubviews: [previousButton, pageLabel, nextButton])
        controls.axis = .horizontal
        controls.distribution = .fillEqually
        controls.spacing = 12
        controls.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(content)
        view.addSubview(controls)
        NSLayoutConstraint.activate([
            content.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            content.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 16),
            content.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -16),
            content.bottomAnchor.constraint(equalTo: controls.topAnchor, constant: -8),
            controls.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 16),
            controls.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -16),
            controls.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            controls.heightAnchor.constraint(greaterThanOrEqualToConstant: 48)
        ])
        previousButton.isEnabled = false
        nextButton.isEnabled = false
        loadTask = Task { [weak self] in
            let result = await Task.detached { () -> [String] in
                guard let url = Bundle.main.url(forResource: "AIThirdPartyNotices", withExtension: "txt"),
                      let text = try? String(contentsOf: url, encoding: .utf8) else { return [] }
                var pages: [String] = []
                var start = text.startIndex
                while start < text.endIndex {
                    let end = text.index(start, offsetBy: 24_000, limitedBy: text.endIndex) ?? text.endIndex
                    pages.append(String(text[start..<end]))
                    start = end
                }
                return pages
            }.value
            guard !Task.isCancelled, let self else { return }
            self.pages = result
            if result.isEmpty { self.content.text = AIText.value("The license file could not be read.", "无法读取许可文件。") }
            else { self.renderPage() }
        }
    }

    deinit { loadTask?.cancel() }
    @objc private func close() { dismiss(animated: true) }
    @objc private func previousPage() { pageIndex = max(0, pageIndex - 1); renderPage() }
    @objc private func nextPage() { pageIndex = min(pages.count - 1, pageIndex + 1); renderPage() }

    private func renderPage() {
        content.text = pages[pageIndex]
        content.setContentOffset(.zero, animated: false)
        pageLabel.text = "\(pageIndex + 1) / \(pages.count)"
        previousButton.isEnabled = pageIndex > 0
        nextButton.isEnabled = pageIndex + 1 < pages.count
    }
}
