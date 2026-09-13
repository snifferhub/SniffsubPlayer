import Foundation
import WebKit

@MainActor
final class BrowserStore: ObservableObject {
    struct UserAgentOption: Identifiable, Hashable {
        let id: String
        let name: String
        let value: String
    }

    static let iPadSafariUA = "Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    static let macOSSafariUA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"

    static let userAgentOptions = [
        UserAgentOption(id: "device-default", name: "设备原生（推荐）", value: ""),
        UserAgentOption(id: "ipad-safari", name: "iPad Safari", value: iPadSafariUA),
        UserAgentOption(id: "macos-safari", name: "macOS Safari", value: macOSSafariUA)
    ]

    private enum StorageKey {
        static let userAgent = "SniffSubPlayer.userAgent"
        static let bookmarks = "SniffSubPlayer.bookmarks"
        static let playerAccentColor = "SniffSubPlayer.playerAccentColor"
    }

    @Published var address = ""
    @Published var title = ""
    @Published var isLoading = false
    @Published var loadProgress = 0.0
    @Published var isSnifferEnabled = false
    @Published private(set) var candidates: [MediaCandidate] = []
    @Published private(set) var bookmarks: [Bookmark] = []
    @Published var userAgent = "" {
        didSet {
            UserDefaults.standard.set(userAgent, forKey: StorageKey.userAgent)
        }
    }
    @Published var playerAccentColorID = PlayerAccentColor.defaultValue {
        didSet {
            UserDefaults.standard.set(playerAccentColorID, forKey: StorageKey.playerAccentColor)
        }
    }

    var currentPageURL = ""

    init() {
        let savedUserAgent = UserDefaults.standard.string(forKey: StorageKey.userAgent)
        if let savedUserAgent,
           Self.userAgentOptions.contains(where: { $0.value == savedUserAgent }) {
            userAgent = savedUserAgent
        }

        if let savedColor = UserDefaults.standard.string(forKey: StorageKey.playerAccentColor),
           !savedColor.isEmpty {
            playerAccentColorID = savedColor
        }

        if let data = UserDefaults.standard.data(forKey: StorageKey.bookmarks),
           let decoded = try? JSONDecoder().decode([Bookmark].self, from: data) {
            bookmarks = decoded
        }
    }

    func addCandidate(url rawURL: String, source: String, pageURL: String?, userAgent detectedUserAgent: String? = nil) {
        let url = rawURL.normalizedMediaURL
        guard !url.isEmpty, isLikelyMediaURL(url) else { return }

        let resolvedPageURL = pageURL?.isEmpty == false ? pageURL! : currentPageURL
        let candidate = MediaCandidate(
            url: url,
            source: source,
            pageURL: resolvedPageURL,
            referer: resolvedPageURL,
            userAgent: detectedUserAgent?.isEmpty == false ? detectedUserAgent! : userAgent
        )

        guard !candidates.contains(where: { $0.url == candidate.url }) else { return }
        candidates.insert(candidate, at: 0)
    }

    func clearCandidates() {
        candidates.removeAll()
    }

    func addBookmark(title rawTitle: String?, url rawURL: String?) {
        let url = (rawURL ?? address).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !url.isEmpty else { return }

        let title = (rawTitle ?? self.title).trimmingCharacters(in: .whitespacesAndNewlines)
        let bookmark = Bookmark(
            title: title.isEmpty ? url : title,
            url: url,
            createdAt: Date()
        )

        bookmarks.removeAll { $0.url == bookmark.url }
        bookmarks.insert(bookmark, at: 0)
        saveBookmarks()
    }

    func isBookmarked(url rawURL: String?) -> Bool {
        let url = (rawURL ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !url.isEmpty else { return false }
        return bookmarks.contains { $0.url == url }
    }

    @discardableResult
    func toggleBookmark(title rawTitle: String?, url rawURL: String?) -> Bool {
        let url = (rawURL ?? address).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !url.isEmpty else { return false }

        if bookmarks.contains(where: { $0.url == url }) {
            bookmarks.removeAll { $0.url == url }
            saveBookmarks()
            return false
        }

        addBookmark(title: rawTitle, url: url)
        return true
    }

    func removeBookmarks(at offsets: IndexSet) {
        bookmarks.remove(atOffsets: offsets)
        saveBookmarks()
    }

    var selectedUserAgentOptionID: String {
        get {
            Self.userAgentOptions.first(where: { $0.value == userAgent })?.id ?? Self.userAgentOptions[0].id
        }
        set {
            guard let option = Self.userAgentOptions.first(where: { $0.id == newValue }) else { return }
            userAgent = option.value
        }
    }

    private func saveBookmarks() {
        guard let data = try? JSONEncoder().encode(bookmarks) else { return }
        UserDefaults.standard.set(data, forKey: StorageKey.bookmarks)
    }

    private func isLikelyMediaURL(_ url: String) -> Bool {
        let lower = url.lowercased()
        if lower.hasPrefix("blob:") { return true }

        let needles = [
            ".m3u8", ".mp4", ".m4v", ".mov", ".mpd", ".m4s", ".ts", ".aac",
            "application/vnd.apple.mpegurl", "video/mp4"
        ]
        return needles.contains { lower.contains($0) }
    }
}
