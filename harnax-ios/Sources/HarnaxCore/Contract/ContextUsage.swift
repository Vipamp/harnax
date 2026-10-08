import Foundation

/// How full one session's model context is, as the runtime answers it.
///
/// Backend: `harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/ContextUsageResponse.kt:41-50`,
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
/// the billed numerator whenever the router has one, and the four judgements below follow `ratio` rather than
/// re-deriving a second opinion. The same four rules live on the console side in
/// `harnax-webui/src/pages/session/components/contextUsage.ts`; the two screens are meant to say the same
/// number for the same session, so they are kept in step deliberately.
public struct ContextUsage: Decodable, Equatable, Sendable {
    /// Messages currently in the model context — compare with `triggerMessages`, not with the bubbles on
    /// screen, which come from the append-only archive and keep every turn a compaction folded away.
    public let messageCount: Int
    /// Upstream's token estimate over the context.
    public let estimatedTokens: Int
    /// Billed input of this session's latest model call. Nil until the session has a billed call to report,
    /// which is the only thing that decides the readout's basis.
    public let lastCallInputTokens: Int?
    /// The denominator. Zero means the router answered without a reading rather than that the context is empty.
    public let contextWindow: Int
    /// The wire value of `ContextWindowSource`
    /// (`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/ContextUsageResponse.kt:7-16`)
    /// kept as the string the server sent, the way the console keeps it
    /// (`harnax-webui/src/pages/session/index.tsx:46-50`): a fourth tier
    /// added upstream then shows up under its own name instead of being read as the fallback.
    public let windowSource: String?
    /// `lastCallInputTokens` over `contextWindow`, or `estimatedTokens` over it until there is a bill.
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
        contextWindow: Int = 0,
        windowSource: String? = nil,
        ratio: Double = 0,
        triggerTokens: Int = 0,
        triggerMessages: Int = 0
    ) {
        self.messageCount = messageCount
        self.estimatedTokens = estimatedTokens
        self.lastCallInputTokens = lastCallInputTokens
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
        lastCallInputTokens ?? estimatedTokens
    }

    /// Where the numerator came from: the bill whenever the router has one, the estimate until it does.
    public var basis: ContextUsageBasis {
        lastCallInputTokens == nil ? .estimated : .billed
    }

    /// Whether the next turn is going to compact this context whether or not anyone asks.
    ///
    /// A trigger of zero is the runtime saying it worked the number out for a model with no window, so nothing
    /// is "at" it — that is why the comparison is guarded rather than the raw `>=`
    /// (`harnax-webui/src/pages/session/components/contextUsage.ts:47-51`).
    public var isAtAutoTrigger: Bool {
        triggerTokens > 0 && numeratorTokens >= triggerTokens
    }

    /// The headline number, one header slot wide, so the decimals follow the magnitude instead of a fixed
    /// format: whole per cent above ten, one decimal down to one, two below that with the trailing zeros
    /// dropped (`harnax-webui/src/pages/session/components/contextUsage.ts:54-60`).
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
