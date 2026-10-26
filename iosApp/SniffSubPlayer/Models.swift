import ComposeApp
import Foundation
import SwiftUI

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
    static func milliseconds(_ seconds: TimeInterval) -> Int64 {
        guard seconds.isFinite else { return 0 }
        let value = seconds * 1000
        if value >= Double(Int64.max) { return Int64.max }
        if value <= Double(Int64.min) { return Int64.min }
        return Int64(value)
    }
}
