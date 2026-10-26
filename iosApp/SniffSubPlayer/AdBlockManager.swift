import ContentBlockerConverter
import Foundation
import WebKit

struct AdBlockSubscription: Identifiable, Codable, Hashable {
    let id: UUID
    var name: String
    var urlString: String
    var isEnabled: Bool

    init(id: UUID = UUID(), name: String, urlString: String, isEnabled: Bool = true) {
        self.id = id
        self.name = name
        self.urlString = urlString
        self.isEnabled = isEnabled
    }
}

@MainActor
final class AdBlockManager: ObservableObject {
    enum UpdateState: Equatable {
        case idle
        case updating
        case active
        case failed(String)
    }

    private struct CacheMetadata: Codable {
        var etag: String?
        var lastModified: String?
    }

    private struct ConvertedRules {
        let json: String
        let sourceRulesCount: Int
        let safariRulesCount: Int
        let errorsCount: Int
        let discardedRulesCount: Int
    }

    private enum StorageKey {
        static let isEnabled = "SniffSubPlayer.adBlock.isEnabled"
        static let automaticUpdatesEnabled = "SniffSubPlayer.adBlock.automaticUpdatesEnabled"
        static let subscriptions = "SniffSubPlayer.adBlock.subscriptions"
        static let cacheMetadata = "SniffSubPlayer.adBlock.cacheMetadata"
        static let activeRuleListIdentifier = "SniffSubPlayer.adBlock.activeRuleListIdentifier"
        static let lastCheckedAt = "SniffSubPlayer.adBlock.lastCheckedAt"
        static let lastUpdatedAt = "SniffSubPlayer.adBlock.lastUpdatedAt"
        static let sourceRulesCount = "SniffSubPlayer.adBlock.sourceRulesCount"
        static let safariRulesCount = "SniffSubPlayer.adBlock.safariRulesCount"
        static let conversionErrorsCount = "SniffSubPlayer.adBlock.conversionErrorsCount"
        static let discardedRulesCount = "SniffSubPlayer.adBlock.discardedRulesCount"
        static let configurationNeedsUpdate = "SniffSubPlayer.adBlock.configurationNeedsUpdate"
    }

    private static let ruleListIdentifierPrefix = "SniffSubPlayer.AdBlock."
    private static let automaticUpdateInterval: TimeInterval = 24 * 60 * 60
    private static let maximumDownloadSize = 12 * 1024 * 1024

    @Published private(set) var subscriptions: [AdBlockSubscription] = []
    @Published private(set) var updateState: UpdateState = .idle
    @Published private(set) var lastCheckedAt: Date?
    @Published private(set) var lastUpdatedAt: Date?
    @Published private(set) var sourceRulesCount = 0
    @Published private(set) var safariRulesCount = 0
    @Published private(set) var conversionErrorsCount = 0
    @Published private(set) var discardedRulesCount = 0
    @Published private(set) var ruleRevision = 0
    @Published private(set) var configurationNeedsUpdate = false
    @Published private(set) var isEnabled: Bool
    @Published private(set) var automaticUpdatesEnabled: Bool

    private var activeRuleListIdentifier: String?
    private var cacheMetadata: [String: CacheMetadata] = [:]
    private let defaults: UserDefaults
    private let fileManager: FileManager
    private let session: URLSession

    init(
        defaults: UserDefaults = .standard,
        fileManager: FileManager = .default,
        session: URLSession = .shared
    ) {
        self.defaults = defaults
        self.fileManager = fileManager
        self.session = session
        self.isEnabled = defaults.bool(forKey: StorageKey.isEnabled)

        if defaults.object(forKey: StorageKey.automaticUpdatesEnabled) == nil {
            self.automaticUpdatesEnabled = true
        } else {
            self.automaticUpdatesEnabled = defaults.bool(forKey: StorageKey.automaticUpdatesEnabled)
        }

        if let data = defaults.data(forKey: StorageKey.subscriptions),
           let decoded = try? JSONDecoder().decode([AdBlockSubscription].self, from: data) {
            subscriptions = decoded
        }

        if let data = defaults.data(forKey: StorageKey.cacheMetadata),
           let decoded = try? JSONDecoder().decode([String: CacheMetadata].self, from: data) {
            cacheMetadata = decoded
        }

        activeRuleListIdentifier = defaults.string(forKey: StorageKey.activeRuleListIdentifier)
        lastCheckedAt = defaults.object(forKey: StorageKey.lastCheckedAt) as? Date
        lastUpdatedAt = defaults.object(forKey: StorageKey.lastUpdatedAt) as? Date
        sourceRulesCount = defaults.integer(forKey: StorageKey.sourceRulesCount)
        safariRulesCount = defaults.integer(forKey: StorageKey.safariRulesCount)
        conversionErrorsCount = defaults.integer(forKey: StorageKey.conversionErrorsCount)
        discardedRulesCount = defaults.integer(forKey: StorageKey.discardedRulesCount)
        configurationNeedsUpdate = defaults.bool(forKey: StorageKey.configurationNeedsUpdate)

        if activeRuleListIdentifier != nil {
            updateState = .active
        }
    }

    var enabledSubscriptionsCount: Int {
        subscriptions.filter(\.isEnabled).count
    }

    var isUpdating: Bool {
        updateState == .updating
    }

    var statusText: String {
        switch updateState {
        case .idle:
            return activeRuleListIdentifier == nil ? "尚未生成规则" : "规则已就绪"
        case .updating:
            return "正在下载并转换规则…"
        case .active:
            return isEnabled
                ? "已应用 \(safariRulesCount) 条 WebKit 规则"
                : "规则已就绪（当前关闭）"
        case .failed(let message):
            return message
        }
    }

    func setEnabled(_ enabled: Bool) {
        guard isEnabled != enabled else { return }
        isEnabled = enabled
        defaults.set(enabled, forKey: StorageKey.isEnabled)
        ruleRevision += 1

        if enabled, activeRuleListIdentifier == nil, subscriptions.contains(where: \.isEnabled) {
            Task { await updateRules(force: false) }
        }
    }

    func setAutomaticUpdatesEnabled(_ enabled: Bool) {
        automaticUpdatesEnabled = enabled
        defaults.set(enabled, forKey: StorageKey.automaticUpdatesEnabled)
    }

    func addSubscription(urlString rawValue: String) throws {
        let normalized = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        let url = try validatedURL(from: normalized)

        guard !subscriptions.contains(where: { $0.urlString.caseInsensitiveCompare(url.absoluteString) == .orderedSame }) else {
            throw AdBlockError.duplicateSubscription
        }

        let name = url.host?.replacingOccurrences(of: "raw.", with: "") ?? "规则订阅"
        subscriptions.append(AdBlockSubscription(name: name, urlString: url.absoluteString))
        setConfigurationNeedsUpdate(true)
        saveSubscriptions()
    }

    func setSubscriptionEnabled(id: UUID, enabled: Bool) {
        guard let index = subscriptions.firstIndex(where: { $0.id == id }) else { return }
        subscriptions[index].isEnabled = enabled
        saveSubscriptions()
        if subscriptions.contains(where: \.isEnabled) {
            setConfigurationNeedsUpdate(true)
        } else {
            setConfigurationNeedsUpdate(false)
            clearActiveRules()
        }
    }

    func removeSubscriptions(at offsets: IndexSet) {
        let removed = offsets.map { subscriptions[$0] }
        subscriptions.remove(atOffsets: offsets)
        saveSubscriptions()

        for subscription in removed {
            cacheMetadata.removeValue(forKey: subscription.id.uuidString)
            try? fileManager.removeItem(at: cacheFileURL(for: subscription.id))
        }
        saveCacheMetadata()

        if subscriptions.contains(where: \.isEnabled) {
            setConfigurationNeedsUpdate(true)
        } else {
            setConfigurationNeedsUpdate(false)
            clearActiveRules()
        }
    }

    func refreshIfNeeded() async {
        guard isEnabled, automaticUpdatesEnabled, !subscriptions.isEmpty else { return }
        if let lastCheckedAt,
           Date().timeIntervalSince(lastCheckedAt) < Self.automaticUpdateInterval,
           activeRuleListIdentifier != nil,
           !configurationNeedsUpdate {
            return
        }
        await updateRules(force: false)
    }

    func updateRules(force: Bool = true) async {
        guard updateState != .updating else { return }

        let enabledSubscriptions = subscriptions.filter(\.isEnabled)
        guard !enabledSubscriptions.isEmpty else {
            clearActiveRules()
            return
        }

        updateState = .updating

        do {
            var downloadedRules: [String] = []
            var contentChanged = activeRuleListIdentifier == nil || configurationNeedsUpdate

            for subscription in enabledSubscriptions {
                let result = try await download(subscription: subscription, force: force)
                downloadedRules.append(result.text)
                contentChanged = contentChanged || result.changed
                if result.changed {
                    setConfigurationNeedsUpdate(true)
                }
            }

            lastCheckedAt = Date()
            defaults.set(lastCheckedAt, forKey: StorageKey.lastCheckedAt)

            if !contentChanged, activeRuleListIdentifier != nil {
                updateState = .active
                return
            }

            setConfigurationNeedsUpdate(true)
            let combinedRules = downloadedRules.joined(separator: "\n")
            let converted = try await convert(rulesText: combinedRules)
            let newIdentifier = Self.ruleListIdentifierPrefix + UUID().uuidString

            _ = try await compile(json: converted.json, identifier: newIdentifier)

            let previousIdentifier = activeRuleListIdentifier
            activeRuleListIdentifier = newIdentifier
            sourceRulesCount = converted.sourceRulesCount
            safariRulesCount = converted.safariRulesCount
            conversionErrorsCount = converted.errorsCount
            discardedRulesCount = converted.discardedRulesCount
            lastUpdatedAt = Date()
            setConfigurationNeedsUpdate(false)
            updateState = .active
            persistCompilationState()
            ruleRevision += 1

            if let previousIdentifier, previousIdentifier != newIdentifier {
                try? await WKContentRuleListStore.default().removeContentRuleList(forIdentifier: previousIdentifier)
            }
        } catch {
            updateState = .failed(error.localizedDescription)
        }
    }

    func applyRules(
        to contentController: WKUserContentController,
        completion: @escaping () -> Void
    ) {
        contentController.removeAllContentRuleLists()

        guard isEnabled, let identifier = activeRuleListIdentifier else {
            completion()
            return
        }

        WKContentRuleListStore.default().lookUpContentRuleList(forIdentifier: identifier) { [weak self] ruleList, _ in
            Task { @MainActor in
                guard let self,
                      self.isEnabled,
                      self.activeRuleListIdentifier == identifier,
                      let ruleList else {
                    completion()
                    return
                }

                contentController.add(ruleList)
                completion()
            }
        }
    }

    private func download(
        subscription: AdBlockSubscription,
        force: Bool
    ) async throws -> (text: String, changed: Bool) {
        let url = try validatedURL(from: subscription.urlString)
        let cachedData = try? Data(contentsOf: cacheFileURL(for: subscription.id))
        let metadata = cacheMetadata[subscription.id.uuidString]
        var request = URLRequest(url: url)
        request.timeoutInterval = 45
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("SniffSubPlayer/0.1", forHTTPHeaderField: "User-Agent")

        if !force, cachedData != nil {
            if let etag = metadata?.etag {
                request.setValue(etag, forHTTPHeaderField: "If-None-Match")
            }
            if let lastModified = metadata?.lastModified {
                request.setValue(lastModified, forHTTPHeaderField: "If-Modified-Since")
            }
        }

        let (data, response) = try await session.data(for: request)
        guard let response = response as? HTTPURLResponse else {
            throw AdBlockError.invalidResponse
        }

        if response.statusCode == 304, let cachedData {
            return (try decodeRules(cachedData, response: nil), false)
        }

        guard (200...299).contains(response.statusCode) else {
            throw AdBlockError.httpStatus(response.statusCode)
        }

        guard data.count <= Self.maximumDownloadSize else {
            throw AdBlockError.downloadTooLarge
        }

        let text = try decodeRules(data, response: response)
        let changed = cachedData != data
        try saveRawRules(data, for: subscription.id)
        cacheMetadata[subscription.id.uuidString] = CacheMetadata(
            etag: response.value(forHTTPHeaderField: "ETag"),
            lastModified: response.value(forHTTPHeaderField: "Last-Modified")
        )
        saveCacheMetadata()
        return (text, changed)
    }

    private func decodeRules(_ data: Data, response: HTTPURLResponse?) throws -> String {
        if let contentType = response?.value(forHTTPHeaderField: "Content-Type")?.lowercased(),
           contentType.contains("text/html") {
            throw AdBlockError.receivedWebPage
        }

        guard var text = String(data: data, encoding: .utf8) else {
            throw AdBlockError.invalidEncoding
        }

        text = text.replacingOccurrences(of: "\u{feff}", with: "")
        let prefix = text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased().prefix(100)
        guard !prefix.hasPrefix("<!doctype html"), !prefix.hasPrefix("<html") else {
            throw AdBlockError.receivedWebPage
        }

        guard text.contains("\n") || text.contains("||") || text.contains("##") else {
            throw AdBlockError.notRuleList
        }
        return text
    }

    private func convert(rulesText: String) async throws -> ConvertedRules {
        let text = rulesText
        let result = await Task.detached(priority: .utility) {
            var contiguousText = text
            contiguousText.makeContiguousUTF8()
            let lines = contiguousText.split(whereSeparator: \.isNewline).map(String.init)
            let conversion = ContentBlockerConverter().convertArray(
                rules: lines,
                safariVersion: SafariVersion.autodetect(),
                advancedBlocking: false,
                maxJsonSizeBytes: nil,
                progress: nil
            )

            return ConvertedRules(
                json: conversion.safariRulesJSON,
                sourceRulesCount: conversion.sourceRulesCount,
                safariRulesCount: conversion.safariRulesCount,
                errorsCount: conversion.errorsCount,
                discardedRulesCount: conversion.discardedSafariRules
            )
        }.value

        guard result.sourceRulesCount > 0, result.safariRulesCount > 0 else {
            throw AdBlockError.noConvertibleRules
        }
        return result
    }

    private func compile(json: String, identifier: String) async throws -> WKContentRuleList {
        try await withCheckedThrowingContinuation { continuation in
            WKContentRuleListStore.default().compileContentRuleList(
                forIdentifier: identifier,
                encodedContentRuleList: json
            ) { ruleList, error in
                if let ruleList {
                    continuation.resume(returning: ruleList)
                } else {
                    continuation.resume(throwing: error ?? AdBlockError.compilationFailed)
                }
            }
        }
    }

    private func validatedURL(from value: String) throws -> URL {
        guard let url = URL(string: value),
              url.scheme?.lowercased() == "https",
              url.host?.isEmpty == false else {
            throw AdBlockError.invalidURL
        }
        return url
    }

    private func saveSubscriptions() {
        if let data = try? JSONEncoder().encode(subscriptions) {
            defaults.set(data, forKey: StorageKey.subscriptions)
        }
    }

    private func saveCacheMetadata() {
        if let data = try? JSONEncoder().encode(cacheMetadata) {
            defaults.set(data, forKey: StorageKey.cacheMetadata)
        }
    }

    private func persistCompilationState() {
        defaults.set(activeRuleListIdentifier, forKey: StorageKey.activeRuleListIdentifier)
        defaults.set(lastUpdatedAt, forKey: StorageKey.lastUpdatedAt)
        defaults.set(sourceRulesCount, forKey: StorageKey.sourceRulesCount)
        defaults.set(safariRulesCount, forKey: StorageKey.safariRulesCount)
        defaults.set(conversionErrorsCount, forKey: StorageKey.conversionErrorsCount)
        defaults.set(discardedRulesCount, forKey: StorageKey.discardedRulesCount)
    }

    private func setConfigurationNeedsUpdate(_ needsUpdate: Bool) {
        configurationNeedsUpdate = needsUpdate
        defaults.set(needsUpdate, forKey: StorageKey.configurationNeedsUpdate)
    }

    private func clearActiveRules() {
        let previousIdentifier = activeRuleListIdentifier
        activeRuleListIdentifier = nil
        sourceRulesCount = 0
        safariRulesCount = 0
        conversionErrorsCount = 0
        discardedRulesCount = 0
        lastUpdatedAt = nil
        updateState = .idle
        defaults.removeObject(forKey: StorageKey.activeRuleListIdentifier)
        defaults.removeObject(forKey: StorageKey.lastUpdatedAt)
        defaults.set(0, forKey: StorageKey.sourceRulesCount)
        defaults.set(0, forKey: StorageKey.safariRulesCount)
        defaults.set(0, forKey: StorageKey.conversionErrorsCount)
        defaults.set(0, forKey: StorageKey.discardedRulesCount)
        ruleRevision += 1

        if let previousIdentifier {
            Task {
                try? await WKContentRuleListStore.default().removeContentRuleList(forIdentifier: previousIdentifier)
            }
        }
    }

    private func saveRawRules(_ data: Data, for id: UUID) throws {
        let directory = try cacheDirectoryURL()
        try data.write(to: directory.appendingPathComponent("\(id.uuidString).txt"), options: .atomic)
    }

    private func cacheFileURL(for id: UUID) -> URL {
        let base = (try? cacheDirectoryURL()) ?? fileManager.temporaryDirectory
        return base.appendingPathComponent("\(id.uuidString).txt")
    }

    private func cacheDirectoryURL() throws -> URL {
        let base = try fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let directory = base.appendingPathComponent("AdBlockRules", isDirectory: true)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }
}

private enum AdBlockError: LocalizedError {
    case invalidURL
    case duplicateSubscription
    case invalidResponse
    case httpStatus(Int)
    case downloadTooLarge
    case receivedWebPage
    case invalidEncoding
    case notRuleList
    case noConvertibleRules
    case compilationFailed

    var errorDescription: String? {
        switch self {
        case .invalidURL:
            return "请输入有效的 HTTPS raw 规则链接"
        case .duplicateSubscription:
            return "该规则订阅已经存在"
        case .invalidResponse:
            return "规则服务器返回了无效响应"
        case .httpStatus(let status):
            return "下载规则失败（HTTP \(status)）"
        case .downloadTooLarge:
            return "单个规则文件不能超过 12 MB"
        case .receivedWebPage:
            return "链接返回的是网页，请使用规则文件的 raw 链接"
        case .invalidEncoding:
            return "规则文件不是 UTF-8 编码"
        case .notRuleList:
            return "下载内容不像 AdGuard/EasyList 规则文件"
        case .noConvertibleRules:
            return "没有找到可以转换为 WebKit 格式的规则"
        case .compilationFailed:
            return "WebKit 规则编译失败"
        }
    }
}
