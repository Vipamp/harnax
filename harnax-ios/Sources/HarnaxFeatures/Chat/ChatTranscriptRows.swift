import SwiftUI
import HarnaxCore
import HarnaxKit

/// One bubble: what the user sent, or everything the answer produced.
///
/// Internal rather than public: the transcript rows are this screen's drawing of the fold, and the seam the
/// host wires is `ChatView`.
struct ChatTurnRow: View {
    let turn: ChatTurn
    /// This bubble is the one the stream is still writing into.
    let isReading: Bool
    /// The screen's state, so a confirmation row can offer its answer. Rows draw nothing of their own
    /// decision: the fold says what is waiting and the view model says what may be sent back.
    @ObservedObject var vm: ChatViewModel

    var body: some View {
        switch turn.role {
        case .user:
            userBubble
        case .assistant:
            answer
        }
    }

    /// The user's turn: the pictures they attached over the words they typed.
    ///
    /// A picture with no caption draws no bubble at all — the text segment is absent on that send, and an empty
    /// coloured box would read as a message that failed to arrive.
    private var userBubble: some View {
        HStack(alignment: .top, spacing: 0) {
            Spacer(minLength: 48)
            VStack(alignment: .trailing, spacing: 6) {
                if !turn.images.isEmpty {
                    HStack(spacing: 6) {
                        ForEach(Array(turn.images.enumerated()), id: \.offset) { _, dataURL in
                            ChatImageThumb(dataURL: dataURL)
                        }
                    }
                }
                let words = turn.copyableText
                if !words.isEmpty {
                    Text(verbatim: words)
                        .font(.body)
                        .foregroundStyle(Color.hx(.onBrand))
                        .fixedSize(horizontal: false, vertical: true)
                        .textSelection(.enabled)
                        .padding(.horizontal, 13)
                        .padding(.vertical, 10)
                        .background(Color.hx(.brand), in: Self.userCard)
                        .hxCopyMenu(words)
                }
            }
        }
    }

    @ViewBuilder
    private var answer: some View {
        // A turn with nothing in it yet draws no row at all: the reading row already says 「正在读」, and an
        // empty container would read as a message that failed to arrive.
        if turn.hasContent {
            if let member = turn.member {
                ChatMemberBubble(turn: turn, member: member, vm: vm)
            } else {
                leadAnswer
            }
        }
    }

    private var leadAnswer: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(turn.segments) { segment in
                ChatSegmentRow(segment: segment, vm: vm)
            }
            meta
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.hx(.surface), in: Self.answerCard)
        .overlay(Self.answerCard.strokeBorder(Color.hx(.separator), lineWidth: 1))
        .hxCopyMenu(turn.copyableText)
    }

    /// The console's assistant bubble (`ChatWindow.less:266-275`): one tier above the screen, which is what
    /// keeps the `surfaceAlt` blocks inside it — code, tool cards — readable, and tailed on the leading
    /// bottom corner where the user's is tailed on the trailing one.
    private static let answerCard = UnevenRoundedRectangle(topLeadingRadius: 16, bottomLeadingRadius: 4,
                                                           bottomTrailingRadius: 16, topTrailingRadius: 16,
                                                           style: .continuous)

    /// The user's side of the pair, tailed on the trailing bottom corner (`ChatWindow.less:257-264`).
    private static let userCard = UnevenRoundedRectangle(topLeadingRadius: 16, bottomLeadingRadius: 16,
                                                         bottomTrailingRadius: 4, topTrailingRadius: 16,
                                                         style: .continuous)

    /// The console stamps the bubble with the turn's time. `answer` draws nothing for a turn that has no
    /// content yet, so the stamp here always has a bubble to sit on.
    private var meta: some View {
        HStack(spacing: 6) {
            Text(verbatim: turn.timestamp.formatted(date: .omitted, time: .shortened))
                .font(.caption2)
                .foregroundStyle(Color.hx(.textTertiary))
            if isReading {
                HXText("chat.streaming")
                    .font(.caption2)
                    .foregroundStyle(Color.hx(.brand))
            }
            Spacer(minLength: 0)
        }
    }
}

/// One team member's run, drawn as a bubble of its own.
///
/// The attribution is the point: a member speaks on the lead's channel, and without a name on the bubble its
/// words are indistinguishable from the answer the user asked for (`ChatWindow.tsx:2919-2955`). The fold
/// already routed the frames here (`TeamRunMerge`); this is the same fact drawn.
///
/// Everything inside is drawn by the lead's own row views — a member's tool cards and its inline confirmation
/// are the same blocks — so the two appearances differ by the container and the header line, not by the
/// content's rendering.
struct ChatMemberBubble: View {
    let turn: ChatTurn
    let member: TeamMemberRun
    @ObservedObject var vm: ChatViewModel

    /// The console's rule: expanded while the run works, collapsed when it is over, and once the user has
    /// tapped, their choice holds (`isRunExpanded`, `ChatWindow.tsx:2878-2883`).
    @State private var manual: Bool?
    @State private var taskExpanded = false

    private var expanded: Bool { manual ?? member.isOpen }

    /// The lead's card with both leading corners pulled in, which is where the rule band sits.
    private static let memberCard = UnevenRoundedRectangle(topLeadingRadius: 4, bottomLeadingRadius: 4,
                                                           bottomTrailingRadius: 16, topTrailingRadius: 16,
                                                           style: .continuous)

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            header
            if expanded {
                taskLine
                ForEach(turn.segments) { segment in
                    ChatSegmentRow(segment: segment, vm: vm)
                }
                if case let .failed(_, message) = turn.outcome, !message.isEmpty {
                    HXBanner("chat.error.title", message: message, systemImage: "exclamationmark.triangle", tone: .danger)
                }
            }
        }
        .padding(11)
        .frame(maxWidth: .infinity, alignment: .leading)
        // 「与主管同形，用左侧色带标明这是被委派的运行」（`ChatWindow.less:277-281`）: the same tier and the
        // same 16pt card, tailed on the leading corners rather than on one.
        .background(Color.hx(.surface), in: Self.memberCard)
        .overlay(
            Self.memberCard.strokeBorder(Color.hx(tone).opacity(0.45), lineWidth: 1)
        )
        // The rule down the inside edge is what says 「this is someone else's turn」 before any word is read.
        .overlay(alignment: .leading) {
            RoundedRectangle(cornerRadius: 1)
                .fill(Color.hx(.brand))
                .frame(width: 3)
        }
        .hxCopyMenu(turn.copyableText)
    }

    private var header: some View {
        Button {
            manual = expanded ? false : true
        } label: {
            HStack(spacing: 6) {
                Image(systemName: "person.2")
                    .font(.caption)
                    .foregroundStyle(Color.hx(tone))
                HXChip(member.source.memberAgentName)
                HXChip(member.source.teamName, tone: .teal)
                HXBadge(member.statusTitleKey, tone: tone)
                Spacer(minLength: 4)
                summary
                Image(systemName: expanded ? "chevron.down" : "chevron.right")
                    .font(.caption2)
                    .foregroundStyle(Color.hx(.textTertiary))
            }
        }
        .buttonStyle(.plain)
    }

    /// Tools it called and how long it took — the two facts that tell a finished run from one still going
    /// (`ChatWindow.tsx:2933-2950`).
    @ViewBuilder
    private var summary: some View {
        HStack(spacing: 6) {
            if member.toolCount > 0 {
                Text(verbatim: hxCount("chat.team.tools", member.toolCount))
                    .font(.caption2)
                    .foregroundStyle(Color.hx(.textTertiary))
            }
            if let duration = member.durationText {
                HStack(spacing: 3) {
                    Image(systemName: "clock")
                        .font(.caption2)
                    Text(verbatim: duration)
                }
                .font(.caption2)
                .foregroundStyle(Color.hx(.textTertiary))
            }
        }
    }

    /// The task the lead delegated, cut for one line and openable for the rest.
    ///
    /// The whole text is already on screen once — in the lead's own `team_delegate` card — so the cut here is
    /// a summary by design, not a truncation the user has no way past (`firstTaskLine`, `teamRun.ts:54-62`).
    @ViewBuilder
    private var taskLine: some View {
        if let task = member.task, !member.headline.isEmpty {
            let isCut = task != member.headline
            Group {
                if isCut {
                    Button {
                        taskExpanded.toggle()
                    } label: {
                        taskText(taskExpanded ? task : member.headline)
                    }
                    .buttonStyle(.plain)
                } else {
                    taskText(task)
                }
            }
        }
    }

    private func taskText(_ text: String) -> some View {
        HXText("chat.team.task", text)
            .font(.caption)
            .foregroundStyle(Color.hx(.textSecondary))
            .fixedSize(horizontal: false, vertical: true)
            .multilineTextAlignment(.leading)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// The state's colour, in the four tones the transcript already uses for those four states.
    private var tone: PaletteSlot {
        switch member.status {
        case .running: return .brand
        case .awaitingConfirm: return .warning
        case .done: return .success
        case .failed: return .danger
        }
    }
}

struct ChatSegmentRow: View {
    let segment: ChatSegment
    @ObservedObject var vm: ChatViewModel

    var body: some View {
        switch segment.kind {
        case let .text(message):
            // The answer is Markdown and the kit draws it: headings, lists, fences, tables, quotes and tappable
            // links, with an unclosed fence running to the end of the document so a half-written answer during
            // a stream shows no stray backticks. Prose here is not selectable — that is the renderer's own rule,
            // because a `Text` with selection on is not reliable about handing a link run its tap — while every
            // fence is, and the turn's raw source stays one long press away (`hxCopyMenu`).
            HXMarkdownText(message, on: .surface)
        case let .thinking(message):
            ChatThinkingBlock(message: message)
        case let .tool(run):
            ChatToolCard(run: run)
        case let .confirmation(tools):
            ChatConfirmationBlock(
                tools: tools,
                isWaiting: vm.isWaitingConfirmation(segment.id),
                vm: vm
            )
        case let .file(attachment):
            ChatFileRow(attachment: attachment, vm: vm)
        case let .plan(note):
            // The drawer's own card, not a second drawing of a plan: the console renders its inline
            // `plan_card` from the same component as the panel's current plan
            // (`ChatWindow.tsx:2799-2863` against `:2988-3053`) and both read the one
            // `currentPlanExpanded`, so closing the card in the stream closes it in the drawer too.
            // `live` is the one poll behind both, which is also what stops the stream's card from
            // claiming to update after `plan_exit` let the plan go.
            CurrentPlanCard(
                plan: note,
                expanded: vm.isPlanExpanded,
                live: vm.isPlanLive,
                onToggle: { vm.togglePlanExpansion() }
            )
        }
    }
}

// MARK: - thinking

struct ChatThinkingBlock: View {
    let message: String
    /// The console's thinking block opens expanded (`ChatWindow.tsx:216-233`).
    @State private var expanded = true

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button {
                expanded.toggle()
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "lightbulb")
                    HXText("chat.thinking")
                    Image(systemName: expanded ? "chevron.down" : "chevron.right")
                        .font(.caption2)
                    Spacer(minLength: 0)
                }
                .font(.caption.weight(.semibold))
                .foregroundStyle(Color.hx(.textTertiary))
            }
            .buttonStyle(.plain)

            if expanded {
                Text(verbatim: message)
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.textSecondary))
                    .fixedSize(horizontal: false, vertical: true)
                    .textSelection(.enabled)
                    .padding(.leading, 9)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .overlay(alignment: .leading) {
                        RoundedRectangle(cornerRadius: 1)
                            .fill(Color.hx(.separator))
                            .frame(width: 2)
                    }
            }
        }
    }
}

// MARK: - tool card

/// A call and the result it paired with, in one card. A result never stands on its own
/// (`ChatWindow.tsx:2786-2788` renders a standalone one as nothing).
struct ChatToolCard: View {
    let run: ChatToolRun
    /// Nil until the user taps: a running card opens itself, a finished one stays shut, and once the tap
    /// happens the user's choice holds (`expanded = manual ?? !!busy`, `ChatWindow.tsx:310-312`).
    @State private var manual: Bool?

    private var expanded: Bool { manual ?? run.isRunning }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            if expanded {
                Rectangle()
                    .fill(Color.hx(.separator))
                    .frame(height: 1)
                    .padding(.vertical, 8)
                details
            }
        }
        .padding(11)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.hx(.surface), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(Color.hx(status.tone).opacity(0.45), lineWidth: 1)
        )
    }

    private var header: some View {
        Button {
            manual = expanded ? false : true
        } label: {
            HStack(spacing: 7) {
                Image(systemName: status.symbol)
                    .font(.caption)
                    .foregroundStyle(Color.hx(status.tone))
                HXChip(hxPresented(run.toolName) ?? "")
                Spacer(minLength: 4)
                HXBadge(status.titleKey, tone: status.tone)
                Image(systemName: expanded ? "chevron.down" : "chevron.right")
                    .font(.caption2)
                    .foregroundStyle(Color.hx(.textTertiary))
            }
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private var details: some View {
        VStack(alignment: .leading, spacing: 8) {
            let arguments = chatArgumentsText(run.arguments)
            if !arguments.isEmpty {
                ChatCodeBlock(titleKey: "chat.tool.arguments", text: arguments, tone: .textSecondary)
            }
            if let result = run.result {
                ChatCodeBlock(
                    titleKey: result.succeeded ? "chat.tool.result" : "chat.tool.failure",
                    text: result.message,
                    tone: result.succeeded ? .success : .danger
                )
            }
        }
    }

    /// The console's six-state ladder, in its short-circuit order (`ChatWindow.tsx:324-329`): the ask still
    /// waiting outranks everything, then the refusal the user gave it, then an approval whose result has not
    /// come back, then a turn that closed before the result arrived. A refusal keeps its own name even once
    /// the server's refusal result lands, because the two say different things.
    private var status: (titleKey: String, tone: PaletteSlot, symbol: String) {
        if run.awaitingConfirmation {
            return ("chat.tool.pending", .warning, "questionmark.circle")
        }
        if run.confirmAnswer == .denied {
            return ("chat.tool.denied", .danger, "hand.raised")
        }
        if run.confirmAnswer != nil, run.result == nil {
            return ("chat.tool.confirmed", .success, "checkmark.seal")
        }
        if run.interrupted, run.result == nil {
            return ("chat.tool.interrupted", .warning, "exclamationmark.circle")
        }
        if let result = run.result {
            return result.succeeded
                ? ("chat.tool.completed", .success, "checkmark.circle")
                : ("chat.tool.rejected", .danger, "xmark.circle")
        }
        return ("chat.tool.running", .brand, "clock.arrow.circlepath")
    }
}

struct ChatCodeBlock: View {
    let titleKey: String
    let text: String
    let tone: PaletteSlot

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HXText(titleKey)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(Color.hx(tone))
            Text(verbatim: text)
                .font(.system(.caption, design: .monospaced))
                .foregroundStyle(Color.hx(.textPrimary))
                .fixedSize(horizontal: false, vertical: true)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(8)
                .background(Color.hx(.surfaceAlt), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
        }
        .hxCopyMenu(text)
    }
}

// MARK: - confirmation

/// What a `ToolConfirmEvent` is waiting on, and the way to answer it.
///
/// The answer goes to `POST /api/router/agent/confirm`, which replies with the stream that resumes the run
/// (`SessionRouterService.kt:221`), so the panel settles as soon as it is submitted and a nested ask draws
/// its own panel underneath it rather than editing this one (`ChatWindow.tsx:2041-2135`).
///
/// Two shapes of the same card, decided by one flag pair: with an answer channel and a live block, the rows
/// carry a choice each and the block ends in a single 确定; without one, the block keeps the wait state this
/// build shipped with and offers no control that could only fail (`ChatWindow.tsx:456-465`).
struct ChatConfirmationBlock: View {
    let tools: [ChatPendingTool]
    /// The newest ask, so the record of an answered one does not offer to answer it again.
    let isWaiting: Bool
    @ObservedObject var vm: ChatViewModel

    /// The rows carry an answer, settled or not — a block with none is the one still waiting.
    private var isSettled: Bool { tools.allSatisfy { $0.answer != nil } }
    private var canAnswer: Bool { isWaiting && vm.canAnswerConfirmation && !isSettled }
    /// A member run is answered whole-round, so per-row choices would be a promise the wire cannot keep
    /// (`DefaultAgentRunner.kt:427`).
    private var isMemberRun: Bool { tools.lazy.compactMap(\.childRunId).first != nil }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            header
            ForEach(Array(tools.enumerated()), id: \.offset) { _, tool in
                row(tool)
            }
            footer
        }
        .padding(11)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.hxFill(.warning, alpha: 0.10), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(Color.hx(.warning).opacity(0.45), lineWidth: 1)
        )
    }

    /// The batch answers hang off the header the card already has, instead of adding a second row of buttons
    /// under the rows (`FEATURES.md` §3: 全部拒绝 / 总是允许 alongside the per-tool 单个批准).
    private var header: some View {
        HStack(spacing: 6) {
            Image(systemName: "lock.shield")
                .font(.caption)
                .foregroundStyle(Color.hx(.warning))
            HXText("chat.confirm.title")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.hx(.warning))
            Spacer(minLength: 0)
            if canAnswer, !isMemberRun {
                batchMenu
            }
        }
    }

    private var footer: some View {
        Group {
            if canAnswer {
                VStack(alignment: .leading, spacing: 6) {
                    HXText("chat.confirm.prompt")
                        .font(.caption)
                        .foregroundStyle(Color.hx(.textSecondary))
                        .fixedSize(horizontal: false, vertical: true)
                    if isMemberRun {
                        HXText("chat.confirm.member")
                            .font(.caption)
                            .foregroundStyle(Color.hx(.textTertiary))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    // One final control for the whole panel, whatever the number of rows above it.
                    Button {
                        vm.submitConfirmation()
                    } label: {
                        HXText("chat.confirm.submit")
                    }
                    .buttonStyle(.hxPrimary)
                }
            } else if !isSettled {
                HXText("chat.confirm.note")
                    .font(.caption)
                    .foregroundStyle(Color.hx(.textTertiary))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func row(_ tool: ChatPendingTool) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(spacing: 6) {
                HXChip(hxPresented(tool.toolName) ?? "")
                HXBadge(
                    tool.isDangerous ? "chat.confirm.dangerous" : "chat.confirm.safe",
                    tone: tool.isDangerous ? .danger : .success
                )
                Spacer(minLength: 0)
                if canAnswer {
                    choiceMenu(tool)
                } else if let answer = tool.answer {
                    HXBadge(answer.settledTitleKey, tone: settledTone(answer))
                }
            }
            let arguments = chatArgumentsText(tool.arguments)
            if !arguments.isEmpty {
                Text(verbatim: arguments)
                    .font(.system(.caption2, design: .monospaced))
                    .foregroundStyle(Color.hx(.textSecondary))
                    .fixedSize(horizontal: false, vertical: true)
                    .textSelection(.enabled)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// The row's own answer, which is what 「单个批准」 needs and what a mixed panel sends per tool.
    private func choiceMenu(_ tool: ChatPendingTool) -> some View {
        let chosen = vm.confirmationChoice(for: tool.toolId)
        return Menu {
            ForEach(ToolConfirmAnswer.allCases, id: \.self) { option in
                Button {
                    vm.setConfirmationChoice(option, for: tool.toolId)
                } label: {
                    HStack(spacing: 6) {
                        HXText(option.titleKey)
                        if option == chosen {
                            Image(systemName: "checkmark")
                        }
                    }
                }
            }
        } label: {
            menuLabel(chosen.titleKey, slot: chosen == .denied ? .danger : .success)
        }
        .menuStyle(.borderlessButton)
        .fixedSize()
    }

    /// The header's batch choices. An answer here writes every row and goes straight off — the console's
    /// modal does the same with its two buttons (`ChatWindow.tsx:1717-1733`).
    private var batchMenu: some View {
        Menu {
            Button {
                vm.answerAllConfirmation(.allowed)
            } label: {
                HXText("chat.confirm.all")
            }
            Button {
                vm.answerAllConfirmation(.alwaysAllowed)
            } label: {
                HXText("chat.confirm.alwaysAll")
            }
            Button(role: .destructive) {
                vm.answerAllConfirmation(.denied)
            } label: {
                HXText("chat.confirm.denyAll")
            }
        } label: {
            menuLabel("chat.confirm.batch", slot: .textSecondary)
        }
        .menuStyle(.borderlessButton)
        .fixedSize()
    }

    private func menuLabel(_ titleKey: String, slot: PaletteSlot) -> some View {
        HStack(spacing: 4) {
            HXText(titleKey)
            Image(systemName: "chevron.down")
                .font(.caption2)
        }
        .font(.caption.weight(.semibold))
        .foregroundStyle(Color.hx(slot))
    }

    /// A settled row is not colour-only: the badge carries the word as well (`DESIGN.md` line 231).
    private func settledTone(_ answer: ToolConfirmAnswer) -> PaletteSlot {
        answer.isConfirmed ? .success : .danger
    }
}

// MARK: - file

/// A file the run left in the sandbox. The end frame is the only place these are reported, and the console
/// draws them nowhere (`specs/02-session-chat.md` “未确认” 1), so this is the row that gives them a place.
struct ChatFileRow: View {
    let attachment: ChatFileAttachment
    @ObservedObject var vm: ChatViewModel

    var body: some View {
        HStack(spacing: 7) {
            Image(systemName: "doc")
                .font(.caption)
                .foregroundStyle(Color.hx(.textTertiary))
            Text(verbatim: hxPresented(attachment.fileName) ?? "")
                .font(.footnote)
                .foregroundStyle(Color.hx(.textPrimary))
                .lineLimit(1)
            Spacer(minLength: 4)
            Text(verbatim: ByteCountFormatter.string(fromByteCount: attachment.fileSize, countStyle: .file))
                .font(.caption)
                .foregroundStyle(Color.hx(.textTertiary))
            if vm.canTakeArtifacts {
                action
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.hx(.surfaceAlt), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }

    /// The same trailing control the artifact drawer uses, so a byte the run left behind is taken the same way
    /// as one the run published. While the bytes are coming the icon becomes the spinner the drawer's row shows.
    @ViewBuilder
    private var action: some View {
        if vm.isDownloading(attachment) {
            ProgressView().tint(Color.hx(.brand))
        } else {
            Button {
                Task { await vm.download(attachment) }
            } label: {
                Image(systemName: "arrow.down.circle")
            }
            .buttonStyle(.hxInline)
            .accessibilityLabel(Text(verbatim: hx("chat.artifacts.download")))
        }
    }
}

// MARK: - stream state

/// The tail of a stream that is still reading, shown while the socket is open.
struct ChatReadingRow: View {
    var body: some View {
        HStack(spacing: 8) {
            ProgressView()
                .progressViewStyle(.circular)
                .tint(Color.hx(.brand))
            HXText("chat.streaming")
                .font(.footnote)
                .foregroundStyle(Color.hx(.textTertiary))
            Spacer(minLength: 0)
        }
    }
}

/// Why the answer stopped, under whatever did arrive.
struct ChatNoticeRow: View {
    let notice: ChatViewModel.StopNotice

    var body: some View {
        switch notice {
        case .disconnected:
            HXBanner("chat.error.disconnected", systemImage: "wifi.slash", tone: .warning)
        case let .failed(text):
            HXBanner("chat.error.title", message: text, systemImage: "exclamationmark.triangle", tone: .danger)
        }
    }
}

// MARK: - copying a whole bubble

/// The long-press entry that puts a whole block on the pasteboard, sitting next to the free text selection
/// every one of these rows already carries. `HXValueText` set this shape for identifiers
/// (`HXValue.swift:26-32`), and one gesture for one action is why the code blocks use it too rather than the
/// console's own copy icon.
///
/// An empty block gets no menu at all: a popup offering to copy nothing reads as a broken control.
private struct HXCopyMenu: ViewModifier {
    let text: String

    func body(content: Content) -> some View {
        if text.isEmpty {
            content
        } else {
            content.contextMenu {
                Button {
                    HXPasteboard.copy(text)
                } label: {
                    HXText("common.copy")
                }
            }
        }
    }
}

private extension View {
    func hxCopyMenu(_ text: String) -> some View {
        modifier(HXCopyMenu(text: text))
    }
}
