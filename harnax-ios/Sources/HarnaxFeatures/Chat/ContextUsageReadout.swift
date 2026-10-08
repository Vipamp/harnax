import HarnaxCore
import HarnaxKit

/// One line of the occupancy readout's detail list — a label and the value under it.
public struct ContextUsageDetail: Equatable, Sendable {
    public let label: String
    public let value: String

    public init(label: String, value: String) {
        self.label = label
        self.value = value
    }
}

/// What the header readout says about one `ContextUsage`, as data.
///
/// The console draws the same two pieces as a `Tag` and a hover `Tooltip`
/// (`harnax-webui/src/pages/session/index.tsx:32-83`); the phone has no hover, so the chip opens a menu of the
/// same five rows. Either way the two screens are meant to put the same number against the same word for the
/// same session, which is why the pairing lives here rather than in a view: which of the two travelling token
/// counts answers which question is a judgement, and one that lives inside `Text(...)` calls drifts silently.
/// The four token rows are abbreviated by `TokenFigures.token`, the same ruler the token screen reads, so one
/// number does not say `200000` here and `200.00K` there.
/// The four rules it reads off the payload — `percentText`, `basis`, `numeratorTokens`' owner `lastCallInputTokens`,
/// and `windowSourceTitleKey` — are the contract type's own, tested in `HarnaxCoreTests`.
public enum ContextUsageReadout {
    /// The chip's own two tokens: the percentage, then where its numerator came from
    /// (`index.tsx:75-79`).
    public static func headline(for usage: ContextUsage) -> String {
        "\(usage.percentText) · \(hx(usage.basis.titleKey))"
    }

    /// The five detail rows, in the console's order (`index.tsx:34-57`).
    ///
    /// The billed row is the one leg that must not fall back to a number: a session with no billed call yet has
    /// no bill to show, and `0` there would read as a free call rather than as nothing. The window row carries
    /// the tier its denominator came from in its label, the way the console's `Window · <tier>` does, and a
    /// tier this build has no word for keeps its own wire spelling instead of borrowing the fallback's.
    public static func rows(for usage: ContextUsage) -> [ContextUsageDetail] {
        let tier = usage.windowSourceTitleKey.map { hx($0) } ?? usage.windowSource ?? hx("chat.context.source.fallback")
        return [
            ContextUsageDetail(
                label: hx("chat.context.billed"),
                value: usage.lastCallInputTokens.map { TokenFigures.token(Int64($0)) } ?? hx("chat.context.noneYet")
            ),
            ContextUsageDetail(
                label: hx("chat.context.estimated"), value: TokenFigures.token(Int64(usage.estimatedTokens))
            ),
            ContextUsageDetail(label: hx("chat.context.messages"), value: String(usage.messageCount)),
            ContextUsageDetail(
                label: "\(hx("chat.context.window")) · \(tier)",
                value: TokenFigures.token(Int64(usage.contextWindow))
            ),
            ContextUsageDetail(
                label: hx("chat.context.trigger"), value: TokenFigures.token(Int64(usage.triggerTokens))
            ),
        ]
    }
}
