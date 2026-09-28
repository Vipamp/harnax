import Foundation
import HarnaxCore

/// The two columns every record card puts under its title: who created the row and when.
///
/// `createTime` arrives as a serialised `LocalDateTime`, so it is either `2026-09-12 14:20:00` or the
/// `T`-joined ISO form (`harnax-admin/src/main/resources/application.yml:22-25`). The year is dropped
/// because a management list shows recent rows, and a date this side cannot parse is left out rather than
/// guessed at.
enum RowMeta {
    static func byline(creator: String?, createTime: String?) -> String {
        var pieces: [String] = []
        if let creator = hxPresented(creator) { pieces.append(creator) }
        if let day = hxMonthDay(createTime) { pieces.append(day) }
        return pieces.joined(separator: " · ")
    }
}

/// `2026-09-12 14:20:00` → `09-12`. Anything that is not three dash-separated groups with two-digit
/// month and day is treated as unreadable.
func hxMonthDay(_ raw: String?) -> String? {
    guard let raw, !raw.isEmpty else { return nil }
    let datePart = raw.split(whereSeparator: { $0 == " " || $0 == "T" || $0 == "." }).first
    let parts = datePart?.split(separator: "-") ?? []
    guard parts.count == 3, parts[1].count == 2, parts[2].count == 2 else { return nil }
    return "\(parts[1])-\(parts[2])"
}
