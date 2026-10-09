import Foundation

/// How full one session's model context is, as the runtime answers it.
///
/// Backend: `harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/ContextUsageResponse.kt:49-59`,
/// reached through the router's proxy —
/// `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:183-191` →
/// `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/AgentServiceClient.kt:192-199` →
/// `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/controller/AgentController.kt:123-134`.
/// The keys are the data class's own property names and they arrive camelCase, so no coding-key
/// strategy is involved.
///
/// Two token numbers travel together because they answer different questions: `estimatedTokens` is what the
/// automatic compaction triggers on and it moves on the turn a compaction runs, while `lastCallInputTokens`
/// is the billed input of the last model call — the real size of that request, system prompt and tool list
/// included, but it only moves on the turn *after* a compaction. They are not proportional, so `ratio` takes
/// the billed numerator whenever the router has one — and only while that bill still describes the context
/// being measured, which is what `billIsCurrent` reports. The four judgements below follow `ratio` rather than
/// re-deriving a second opinion. The same four rules live on the console side in
/// `harnax-webui/src/pages/session/components/contextUsage.ts`; the two screens are meant to say the same
/// number for the same session, so they are kept in step deliberately.
public struct ContextUsage: Decodable, Equatable, Sendable {
    /// Messages currently in the model context — compare with `triggerMessages`, not with the bubbles on
    /// screen, which come from the append-only archive and keep every turn a compaction folded away.
    public let messageCount: Int
    /// Upstream's token estimate over the context.
    public let estimatedTokens: Int
    /// Billed input of this session's latest model call. Nil until the session has a billed call to report; a
    /// bill that priced a context an on-demand compaction has since rewritten still arrives here as the real
    /// number, it just stops being the numerator (`billIsCurrent`).
    public let lastCallInputTokens: Int?
    /// Whether that bill still describes this context. The server clears it when an on-demand compaction
    /// rewrites the context, since the newest row then prices a request that no longer exists.
    public let billIsCurrent: Bool?
    /// The denominator. Zero means the router answered without a reading rather than that the context is empty.
    public let contextWindow: Int
    /// The wire value of `ContextWindowSource`
    /// (`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/ContextUsageResponse.kt:7-16`)
    /// kept as the string the server sent, the way the console keeps it
    /// (`harnax-webui/src/pages/session/index.tsx:46-50`): a fourth tier
    /// added upstream then shows up under its own name instead of being read as the fallback.
    public let windowSource: String?
    /// `lastCallInputTokens` over `contextWindow` while that bill stands, `estimatedTokens` over it until the
    /// session has one or after a compaction has voided the one it had.
    public let ratio: Double
    /// Where the automatic compaction fires for this model.
    public let triggerTokens: Int
    /// The message-count trigger the automatic path uses.
    public let triggerMessages: Int

    /// The answer a session with no context gets: the router's `ResultVo.success(null)` leg, which the transport
    /// hands over as this empty value (see `ContextUsage: HarnaxVoid` in `ContextUsageClient.swift`).
    /// `contextWindow == 0` is what `isReadable` exists to catch. Declared rather than derived from the
    /// initialiser below, because `HarnaxVoid` asks for an `init()` and a defaulted-parameter initialiser is a
    /// different declaration — the same reason `CurrentPlan` spells its own empty value out
    /// (`PlanNote.swift:213-215`).
    public init() {
        self.init(messageCount: 0, estimatedTokens: 0, lastCallInputTokens: nil, contextWindow: 0)
    }

    public init(
        messageCount: Int = 0,
        estimatedTokens: Int = 0,
        lastCallInputTokens: Int? = nil,
        billIsCurrent: Bool? = true,
        contextWindow: Int = 0,
        windowSource: String? = nil,
        ratio: Double = 0,
        triggerTokens: Int = 0,
        triggerMessages: Int = 0
    ) {
        self.messageCount = messageCount
        self.estimatedTokens = estimatedTokens
        self.lastCallInputTokens = lastCallInputTokens
        self.billIsCurrent = billIsCurrent
        self.contextWindow = contextWindow
        self.windowSource = windowSource
        self.ratio = ratio
        self.triggerTokens = triggerTokens
        self.triggerMessages = triggerMessages
    }

    /// A reading exists only when the answer carries a usable denominator.
    ///
    /// Two shapes say otherwise and neither means the context is empty: no agent-service instance holds the
    /// session (`ResultVo.error("No context held for session …")`,
    /// `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/controller/AgentController.kt:123-134`)
    /// and the session was never bound to one (`ResultVo.success(null)`,
    /// `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/proxy/SessionRouterService.kt:397-407`).
    /// The console hides its tag for both rather than showing a `0%`
    /// (`harnax-webui/src/pages/session/index.tsx:142-153`,
    /// `harnax-webui/src/pages/session/components/contextUsage.ts:22-26`), and a header slot that claims an
    /// empty context on a session the router simply cannot see is the one thing this readout must never do.
    public var isReadable: Bool {
        contextWindow > 0 && ratio.isFinite
    }

    /// The numerator `ratio` actually used, so the detail rows can say where the headline came from instead of
    /// making the reader work it out.
    public var numeratorTokens: Int {
        guard hasUsableBill, let bill = lastCallInputTokens else { return estimatedTokens }
        return bill
    }

    /// Where the numerator came from: the bill whenever the router has one it still stands behind, the
    /// estimate until it does.
    public var basis: ContextUsageBasis {
        hasUsableBill ? .billed : .estimated
    }

    /// Whether the bill is the number this readout reports — the router has one, and it still describes the
    /// context that is live.
    ///
    /// An on-demand compaction rewrites the context without making a model call of its own, so the newest row
    /// then prices a request that no longer exists; serving it as the numerator would keep the headline at the
    /// pre-compaction figure until the next turn. The server voids such a bill with `billIsCurrent: false`
    /// (`ContextUsageResponse.kt:53`) and the next real call writes a newer row, so this falls back on its
    /// own. Only an explicit `false` voids: the default is `true` and a server that predates the field leaves
    /// the key off the wire, and reading either silence as voided would move every session onto the estimate.
    private var hasUsableBill: Bool {
        lastCallInputTokens != nil && billIsCurrent != false
    }

    /// Whether the next turn is going to compact this context whether or not anyone asks.
    ///
    /// A trigger of zero is the runtime saying it worked the number out for a model with no window, so nothing
    /// is "at" it — that is why the comparison is guarded rather than the raw `>=`
    /// (`harnax-webui/src/pages/session/components/contextUsage.ts:66-70`).
    public var isAtAutoTrigger: Bool {
        triggerTokens > 0 && numeratorTokens >= triggerTokens
    }

    /// The headline number, one header slot wide, so the decimals follow the magnitude instead of a fixed
    /// format: whole per cent above ten, one decimal down to one, two below that with the trailing zeros
    /// dropped (`harnax-webui/src/pages/session/components/contextUsage.ts:73-79`).
    /// Zero and an unreadable ratio both read as `0%`.
    public var percentText: String {
        Self.percentText(ratio)
    }

    /// The same rule as `percentText`, open so the screen and the tests ask the same question of a ratio.
    public static func percentText(_ ratio: Double) -> String {
        guard ratio.isFinite, ratio > 0 else { return "0%" }
        let percent = ratio * 100
        if percent >= 10 {
            // `Int(Double)` is a fatal error past its range, and a `ratio` divided out of a corrupt
            // denominator can be anything the wire carries. `isFinite` does not cover it: multiplying by a
            // hundred can overflow to infinity here.
            let whole = percent.rounded()
            return whole < Double(Int.max) ? "\(Int(whole))%" : "\(Int.max)%"
        }
        if percent >= 1 { return String(format: "%.1f%%", percent) }
        var text = String(format: "%.2f", percent)
        while text.hasSuffix("0") { text.removeLast() }
        if text.hasSuffix(".") { text.removeLast() }
        return "\(text)%"
    }

    /// The catalogue key naming where the denominator came from. An unknown tier keeps its own wording rather
    /// than borrowing the fallback's, which would say "the runtime had no idea" when it said something else.
    /// An empty tier is the fallback's answer, which is the console's own leg
    /// (`harnax-webui/src/pages/session/index.tsx:48-49`, `usage.windowSource || 'FALLBACK'`).
    public var windowSourceTitleKey: String? {
        switch windowSource {
        case "MODEL_FIELD": return "chat.context.source.modelField"
        case "UPSTREAM_TABLE": return "chat.context.source.upstreamTable"
        case "", "FALLBACK", .none: return "chat.context.source.fallback"
        default: return nil
        }
    }
}

/// Which of the two numbers in `ContextUsage` is the readout's numerator.
public enum ContextUsageBasis: String, Equatable, Sendable {
    /// The billed input of the last model call.
    case billed
    /// Upstream's estimate, used until the session has a call to bill.
    case estimated

    /// The one word the readout shows beside the percentage.
    public var titleKey: String {
        switch self {
        case .billed: return "chat.context.basis.billed"
        case .estimated: return "chat.context.basis.estimated"
        }
    }
}

/// The one read this screen lives off.
///
/// Its own protocol rather than a member of `ChatHistoryReading` or `PlanReading`: all three ride the same
/// router controller, and none of them belongs on the others' screen — a conversation with no reading is the
/// ordinary case, not an error, and the answer has to be able to say that.
public protocol ContextUsageReading: Sendable {
    /// `GET /api/router/agent/context/{sessionId}` —
    /// `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:183-191`.
    ///
    /// The no-reading leg answers `ResultVo.success(null)`, and that is `ContextUsage()` with
    /// `isReadable == false` rather than a failure. The *other* leg — a business `code` with no `data` — does
    /// arrive as a failure, and the caller treats the two alike.
    func contextUsage(sessionId: String) async -> Result<ContextUsage, APIError>
}
