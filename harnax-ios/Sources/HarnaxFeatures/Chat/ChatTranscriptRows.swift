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
                let words = turn.segments.compactMap(\.text).joined(separator: "\n")
                if !words.isEmpty {
                    Text(verbatim: words)
                        .font(.body)
                        .foregroundStyle(Color.hx(.onBrand))
                        .fixedSize(horizontal: false, vertical: true)
                        .textSelection(.enabled)
                        .padding(.horizontal, 13)
                        .padding(.vertical, 10)
                        .background(Color.hx(.brand), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
            }
        }
    }

    private var answer: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(turn.segments) { segment in
                ChatSegmentRow(segment: segment, vm: vm)
            }
            if turn.hasContent {
                meta
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// The console stamps the bubble with the turn's time; an answer with nothing in it has nothing to
    /// stamp, and an empty row would read as a message that failed to draw.
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

struct ChatSegmentRow: View {
    let segment: ChatSegment
    @ObservedObject var vm: ChatViewModel

    var body: some View {
        switch segment.kind {
        case let .text(message):
            // Plain text. `Package.swift` carries no markdown renderer and none is pulled in for this, so
            // the answer shows as the source it arrives in rather than as formatted prose.
            Text(verbatim: message)
                .font(.body)
                .foregroundStyle(Color.hx(.textPrimary))
                .fixedSize(horizontal: false, vertical: true)
                .textSelection(.enabled)
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
            ChatFileRow(attachment: attachment)
        }
    }
}

// MARK: - thinking

struct ChatThinkingBlock: View {
    let message: String
    /// The console's thinking block opens expanded (`ChatWindow.tsx:211-228`).
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
/// (`ChatWindow.tsx:2771-2773` renders a standalone one as nothing).
struct ChatToolCard: View {
    let run: ChatToolRun
    /// Nil until the user taps: a running card opens itself, a finished one stays shut, and once the tap
    /// happens the user's choice holds (`expanded = manual ?? !!busy`, `ChatWindow.tsx:305-307`).
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

    /// The console's six-state ladder, in its short-circuit order (`ChatWindow.tsx:319-324`): the ask still
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
    }
}

// MARK: - confirmation

/// What a `ToolConfirmEvent` is waiting on, and the way to answer it.
///
/// The answer goes to `POST /api/router/agent/confirm`, which replies with the stream that resumes the run
/// (`SessionRouterService.kt:221`), so the panel settles as soon as it is submitted and a nested ask draws
/// its own panel underneath it rather than editing this one (`ChatWindow.tsx:2036-2130`).
///
/// Two shapes of the same card, decided by one flag pair: with an answer channel and a live block, the rows
/// carry a choice each and the block ends in a single 确定; without one, the block keeps the wait state this
/// build shipped with and offers no control that could only fail (`ChatWindow.tsx:451-460`).
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
    /// modal does the same with its two buttons (`ChatWindow.tsx:1712-1728`).
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
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.hx(.surfaceAlt), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
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
