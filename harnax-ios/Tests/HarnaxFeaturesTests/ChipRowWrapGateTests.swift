import XCTest
@testable import HarnaxFeatures

/// A row of chips has to wrap, because a chip will not give width back.
///
/// Reported 2026-10-11: on the 会话记忆审批 queue the card's number row ran past the right edge of the phone.
/// `HXChip` ends with `.fixedSize()` (`HarnaxKit/Components/HXBadge.swift:45`), so the row cannot be squeezed
/// into the card the way plain text can — an `HStack` of five of them just gets wider than the screen. The
/// queue row carries up to five chips (`v3` / `尚无记忆层`, `12,345 个字符`, `清空 12 个会话文件`,
/// `改写 12 个日志文件`, `已被重新合并`), roughly 470pt of chips at caption2 against the 370pt a card leaves on a
/// 430pt phone; the English strings clear the same budget by less than the margin for a longer number. The
/// house answer already exists — `HXFlow` (`HarnaxKit/Components/HXFlow.swift`) is the wrapping pill row every
/// other list and detail screen uses for a chip cloud — so the gate below is about which layout these rows
/// ask for, not about a new component.
final class ChipRowWrapGateTests: XCTestCase {
    /// The chip rows of the two review queues, with the declaration each one lives in.
    private let chipRows: [(file: String, marker: String)] = [
        ("HarnaxFeatures/MemoryDrafts/MemoryDraftListView.swift", "private var badges"),
        ("HarnaxFeatures/MemoryDrafts/MemoryDraftDetailView.swift", "private func badges"),
        ("HarnaxFeatures/SkillDrafts/SkillDraftListView.swift", "private var badges")
    ]

    func testEveryChipRowStartsFromTheWrappingLayout() throws {
        for entry in chipRows {
            let text = try FeatureSources.code(of: entry.file)
            let body = try FeatureSources.block(text, from: entry.marker)
            XCTAssertTrue(
                body.contains("HXChip"),
                "\(entry.marker) in \(entry.file) no longer draws chips, so this gate needs a new subject"
            )
            guard let brace = body.firstIndex(of: "{") else {
                return XCTFail("\(entry.marker) in \(entry.file) has no body")
            }
            let inner = String(body[body.index(after: brace)...])
                .trimmingCharacters(in: .whitespacesAndNewlines)
            XCTAssertTrue(
                inner.hasPrefix("HXFlow("),
                "\(entry.file) puts fixed-width chips in an outer row that cannot wrap: the chips past the "
                    + "card width leave the screen instead of dropping to a second line — \(inner.prefix(60))"
            )
        }
    }

    /// The premise the gate rests on. If chips ever become compressible, the overflow would stop being a
    /// wrapping problem and these rows could go back to an `HStack` — with truncation, which is its own loss.
    func testTheChipStillRefusesToCompress() throws {
        let body = try FeatureSources.block(
            try FeatureSources.code(of: "HarnaxKit/Components/HXBadge.swift"),
            from: "public struct HXChip"
        )
        XCTAssertTrue(
            body.contains(".fixedSize()"),
            "HXChip lost its fixedSize: a chip row would then truncate instead of overflowing, which is a "
                + "different trade-off than the one these rows were written for"
        )
    }
}
