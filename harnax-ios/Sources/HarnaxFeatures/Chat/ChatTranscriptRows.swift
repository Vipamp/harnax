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
                ChatSegmentRow(segment: segment)
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
            ChatConfirmationBlock(tools: tools)
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

    /// The console's six-state ladder, in its short-circuit order (`ChatWindow.tsx:319-324`). iOS keeps four
    /// of the names: an approved and a rejected confirmation both end as a result card, because this build
    /// does not answer the request.
    private var status: (titleKey: String, tone: PaletteSlot, symbol: String) {
        if run.awaitingConfirmation {
            return ("chat.tool.pending", .warning, "questionmark.circle")
        }
        if let result = run.result {
            return result.succeeded
                ? ("chat.tool.completed", .success, "checkmark.circle")
                : ("chat.tool.rejected", .danger, "xmark.circle")
        }
        if run.interrupted {
            return ("chat.tool.interrupted", .warning, "exclamationmark.circle")
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

/// What a `ToolConfirmEvent` is waiting on, shown and not answered.
///
/// The answer path is the console's blocking modal on `POST /api/router/agent/confirm`
/// (`ChatWindow.tsx:1657-1738`), and that flow is not part of this build, so these rows carry no buttons —
/// the fold still marks the matching cards as waiting so the transcript reads the same way it does there.
struct ChatConfirmationBlock: View {
    let tools: [ChatPendingTool]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "lock.shield")
                    .font(.caption)
                    .foregroundStyle(Color.hx(.warning))
                HXText("chat.confirm.title")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Color.hx(.warning))
                Spacer(minLength: 0)
            }
            ForEach(Array(tools.enumerated()), id: \.offset) { _, tool in
                row(tool)
            }
            HXText("chat.confirm.note")
                .font(.caption)
                .foregroundStyle(Color.hx(.textTertiary))
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(11)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.hxFill(.warning, alpha: 0.10), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(Color.hx(.warning).opacity(0.45), lineWidth: 1)
        )
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
