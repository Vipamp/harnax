import SwiftUI
import HarnaxCore
import HarnaxKit

/// D2 — the transcript of one conversation, the stream that fills it, and the input that starts the next
/// turn.
///
/// The screen draws the fold and decides nothing about it: every row comes from `ChatTranscript`, so the
/// folding rules stay unit-testable without a view. What the screen does own is where the viewport is —
/// following the tail is a scroll decision, not a segment decision.
public struct ChatView: View {
    @StateObject private var vm: ChatViewModel
    /// The conversation the host asked for, kept beside the view model so a parameter change can be noticed
    /// and handed to `bind`. `@StateObject` keeps the first value it was given, which is exactly why the
    /// switch has to be observed rather than rebuilt.
    private let conversation: ChatConversation

    /// The named space the tail is measured in: the scroll view's own bounds, so a `maxY` reads as a
    /// distance from the bottom of what the user can see.
    private static let scrollSpace = "chatTranscriptSpace"
    private static let tailMarker = "chatTranscriptTail"

    public init(
        streaming: any AgentStreaming,
        commands: (any AgentCommanding)? = nil,
        conversation: ChatConversation
    ) {
        _vm = StateObject(wrappedValue: ChatViewModel(
            streaming: streaming,
            commands: commands,
            conversation: conversation
        ))
        self.conversation = conversation
    }

    public var body: some View {
        VStack(spacing: 0) {
            transcript
            ChatInputBar(vm: vm)
        }
        .harnaxScreen()
        .navigationTitle(vm.conversation.title)
        .task(id: conversation) { vm.bind(conversation) }
        .onDisappear { vm.detach() }
    }

    // MARK: - transcript

    private var transcript: some View {
        GeometryReader { viewport in
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(spacing: 0) {
                        rows
                            .padding(.bottom, 12)
                        // Not a fixed padding: the slack a short answer leaves has to sit between the rows and
                        // the tail, or the tail would report a distance from the bottom that is really just
                        // unused viewport.
                        Spacer(minLength: 0)
                        tailMarker
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.top, 12)
                    .frame(minHeight: viewport.size.height, alignment: .top)
                }
                .coordinateSpace(name: Self.scrollSpace)
                .hxDismissKeyboardOnScroll()
                // The `initial:` overload of this modifier is not in the macOS SDK the package is tested
                // against, so the form that exists on both hosts is the one used here.
                .onPreferenceChange(TailEdgeKey.self) { edge in
                    vm.didScroll(distanceFromBottom: max(0, viewport.size.height - edge))
                }
                .onChange(of: vm.scrollToBottomID) { _, _ in
                    proxy.scrollTo(Self.tailMarker, anchor: .bottom)
                }
                .overlay(alignment: .bottomTrailing) { jumpButton }
            }
        }
    }

    @ViewBuilder
    private var rows: some View {
        if vm.transcript.turns.isEmpty {
            HXStateView(.empty, message: hx("chat.empty.hint"))
                .padding(.top, 40)
        } else {
            LazyVStack(alignment: .leading, spacing: 12) {
                ForEach(vm.transcript.turns) { turn in
                    ChatTurnRow(turn: turn, isReading: vm.isStreaming && turn.outcome == .streaming)
                }
                if vm.isStreaming {
                    ChatReadingRow()
                }
                if let notice = vm.stopNotice {
                    ChatNoticeRow(notice: notice)
                }
            }
        }
    }

    private var tailMarker: some View {
        Color.clear
            .frame(height: 1)
            .id(Self.tailMarker)
            .background(GeometryReader { proxy in
                Color.clear.preference(
                    key: TailEdgeKey.self,
                    value: proxy.frame(in: .named(Self.scrollSpace)).maxY
                )
            })
    }

    /// The console's "back to bottom" control, shown once the user has scrolled clear of the tail
    /// (`ChatWindow.tsx:620-627`, rendered at `:3452-3456`).
    @ViewBuilder
    private var jumpButton: some View {
        if !vm.isAnchoredToBottom {
            Button {
                vm.jumpToBottom()
            } label: {
                Image(systemName: "arrow.down")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(Color.hx(.brand))
                    .frame(width: 38, height: 38)
                    .background(Color.hx(.surface), in: Circle())
                    .overlay(Circle().strokeBorder(Color.hx(.separator), lineWidth: 1))
                    .shadow(color: Color.hx(.separator).opacity(0.5), radius: 6, y: 2)
            }
            .padding(.trailing, 16)
            .padding(.bottom, 12)
        }
    }
}

// MARK: - tail distance

/// The bottom edge of the transcript's last row, in the scroll view's own space.
private struct TailEdgeKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
    }
}

// MARK: - keyboard

private extension View {
    /// Dragging the transcript down should put the keyboard away. The modifier is a touch idiom and the
    /// package builds for a macOS host too, so it lives behind the same platform check as every other
    /// keyboard affordance in this app.
    @ViewBuilder
    func hxDismissKeyboardOnScroll() -> some View {
        #if os(iOS)
        scrollDismissesKeyboard(.interactively)
        #else
        self
        #endif
    }
}

// MARK: - input

private struct ChatInputBar: View {
    @ObservedObject var vm: ChatViewModel

    var body: some View {
        HStack(alignment: .bottom, spacing: 10) {
            TextField(
                hx(vm.isStreaming ? "chat.input.busy" : "chat.input.placeholder"),
                text: $vm.draft,
                axis: .vertical
            )
            .font(.body)
            .foregroundStyle(Color.hx(.textPrimary))
            .tint(Color.hx(.brand))
            .lineLimit(2...6)
            .submitLabel(.send)
            .onSubmit { vm.send() }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(Color.hx(.surfaceAlt), in: RoundedRectangle(cornerRadius: 13, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 13, style: .continuous)
                    .strokeBorder(Color.hx(.separator), lineWidth: 1)
            )

            Button {
                if vm.isStreaming {
                    vm.stop()
                } else {
                    vm.send()
                }
            } label: {
                Image(systemName: vm.isStreaming ? "stop.circle.fill" : "arrow.up.circle.fill")
                    .font(.system(size: 30))
                    .foregroundStyle(Color.hx(vm.isStreaming ? .danger : .brand))
            }
            .buttonStyle(.plain)
            .disabled(vm.isStreaming == false && vm.canSend == false)
            .accessibilityLabel(hx(vm.isStreaming ? "chat.action.stop" : "chat.action.send"))
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(Color.hx(.surface))
    }
}
