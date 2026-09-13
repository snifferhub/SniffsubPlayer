import Foundation
import SwiftUI
import UIKit

enum PlayerAccentColor {
    static let defaultValue = "#E8A7BF"

    static func color(from storedValue: String) -> Color {
        if storedValue.hasPrefix("#") {
            let hex = String(storedValue.dropFirst())
            if hex.count == 6, let rgb = Int(hex, radix: 16) {
                return Color(
                    red: Double((rgb >> 16) & 0xFF) / 255,
                    green: Double((rgb >> 8) & 0xFF) / 255,
                    blue: Double(rgb & 0xFF) / 255
                )
            }
        }

        return Color(red: 232.0 / 255.0, green: 167.0 / 255.0, blue: 191.0 / 255.0)
    }

    static func storageValue(from color: Color) -> String {
        let uiColor = UIColor(color)
        var red: CGFloat = 0
        var green: CGFloat = 0
        var blue: CGFloat = 0
        var alpha: CGFloat = 0
        guard uiColor.getRed(&red, green: &green, blue: &blue, alpha: &alpha) else {
            return defaultValue
        }

        return String(
            format: "#%02X%02X%02X",
            Int((red * 255).rounded()),
            Int((green * 255).rounded()),
            Int((blue * 255).rounded())
        )
    }
}

enum MediaKind: String, Codable, CaseIterable {
    case hls = "HLS"
    case mp4 = "MP4"
    case dash = "DASH"
    case segment = "Segment"
    case blob = "Blob"
    case other = "Other"

    static func classify(_ urlString: String) -> MediaKind {
        let lower = urlString.lowercased()
        if lower.hasPrefix("blob:") { return .blob }
        if lower.contains(".m3u8") { return .hls }
        if lower.contains(".mp4") || lower.contains(".m4v") || lower.contains(".mov") { return .mp4 }
        if lower.contains(".mpd") { return .dash }
        if lower.contains(".m4s") || lower.contains(".ts") || lower.contains(".aac") { return .segment }
        return .other
    }
}

struct MediaCandidate: Identifiable, Hashable, Codable {
    let id: UUID
    let url: String
    let kind: MediaKind
    let source: String
    let pageURL: String
    let referer: String
    let userAgent: String
    let detectedAt: Date

    init(url: String, source: String, pageURL: String, referer: String, userAgent: String) {
        self.id = UUID()
        self.url = url
        self.kind = MediaKind.classify(url)
        self.source = source
        self.pageURL = pageURL
        self.referer = referer
        self.userAgent = userAgent
        self.detectedAt = Date()
    }
}

struct Bookmark: Identifiable, Hashable, Codable {
    let id: UUID
    let title: String
    let url: String
    let createdAt: Date

    init(id: UUID = UUID(), title: String, url: String, createdAt: Date) {
        self.id = id
        self.title = title
        self.url = url
        self.createdAt = createdAt
    }
}

struct SubtitleCue: Identifiable, Hashable {
    let id = UUID()
    let start: TimeInterval
    let end: TimeInterval
    let text: String
}

struct OnlineSubtitle: Identifiable, Hashable, Decodable {
    let id = UUID()
    let name: String
    let extraName: String?
    let url: String

    enum CodingKeys: String, CodingKey {
        case name
        case extraName = "extra_name"
        case url
    }

    var displayName: String {
        if let extraName, !extraName.isEmpty {
            return "\(name) (\(extraName))"
        }
        return name
    }
}

struct RemoteSubtitleSearchResponse: Decodable {
    let code: Int?
    let data: [OnlineSubtitle]?
}

enum VideoIDExtractor {
    static func extract(from values: [String]) -> String {
        for value in values {
            if let id = extract(from: value) {
                return id
            }
        }
        return ""
    }

    private static func extract(from value: String) -> String? {
        let patterns = [
            #"(?i)([a-z]{2,6})[-_]?(\d{2,5})"#,
            #"(?i)/([a-z0-9]+-[a-z0-9]+)(?:[/?#]|$)"#
        ]

        for pattern in patterns {
            guard let regex = try? NSRegularExpression(pattern: pattern) else { continue }
            let range = NSRange(value.startIndex..<value.endIndex, in: value)
            guard let match = regex.firstMatch(in: value, range: range),
                  match.numberOfRanges >= 3,
                  let prefixRange = Range(match.range(at: 1), in: value),
                  let numberRange = Range(match.range(at: 2), in: value) else {
                continue
            }

            return "\(value[prefixRange].uppercased())-\(value[numberRange])"
        }

        return nil
    }
}

enum SubtitleParser {
    static func parse(_ rawText: String, offset: TimeInterval = 0) -> [SubtitleCue] {
        let normalized = rawText
            .replacingOccurrences(of: "\u{feff}", with: "")
            .replacingOccurrences(of: "\r", with: "")
            .replacingOccurrences(of: #"(?i)^WEBVTT[^\n]*\n+"#, with: "", options: .regularExpression)

        return normalized
            .components(separatedBy: "\n\n")
            .flatMap { block in block.components(separatedBy: "\n\n\n") }
            .compactMap { parseBlock($0, offset: offset) }
            .sorted { $0.start < $1.start }
    }

    private static func parseBlock(_ block: String, offset: TimeInterval) -> SubtitleCue? {
        let lines = block
            .components(separatedBy: "\n")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }

        guard let timeIndex = lines.firstIndex(where: { $0.contains("-->") }) else {
            return nil
        }

        let parts = lines[timeIndex].components(separatedBy: "-->")
        guard parts.count >= 2 else { return nil }

        let startText = parts[0].trimmingCharacters(in: .whitespaces)
        let endText = parts[1]
            .trimmingCharacters(in: .whitespaces)
            .components(separatedBy: .whitespaces)
            .first ?? ""

        guard let start = parseTimestamp(startText), let end = parseTimestamp(endText) else {
            return nil
        }

        let text = lines
            .dropFirst(timeIndex + 1)
            .joined(separator: "\n")
            .replacingOccurrences(of: #"<[^>]+>"#, with: "", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)

        guard !text.isEmpty, end > start else { return nil }
        return SubtitleCue(start: max(0, start + offset), end: max(0, end + offset), text: text)
    }

    private static func parseTimestamp(_ value: String) -> TimeInterval? {
        let clean = value.replacingOccurrences(of: ",", with: ".")
        var pieces = clean.split(separator: ":").map(String.init)
        guard pieces.count >= 2 else { return nil }

        let secondsText = pieces.removeLast()
        guard let seconds = Double(secondsText),
              let minutes = Double(pieces.removeLast()) else {
            return nil
        }

        let hours = pieces.last.flatMap(Double.init) ?? 0
        return hours * 3600 + minutes * 60 + seconds
    }
}

extension String {
    var normalizedMediaURL: String {
        trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
