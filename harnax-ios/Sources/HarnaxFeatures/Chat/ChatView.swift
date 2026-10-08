import SwiftUI
import HarnaxCore
import HarnaxKit
#if canImport(UIKit)
import UIKit
#endif

/// D2 — the transcript of one conversation, the stream that fills it, and the input that starts the next
/// turn.
///
/// The screen draws the fold and decides nothing about it: every row comes from `ChatTranscript`, so the
/// folding rules stay unit-testable without a view. What the screen does own is where the viewport is —
/// following the tail is a scroll decision, not a segment decision.
public struct ChatView: View {
    @StateObject private var vm: ChatViewModel
    /// The height the composer is capped against. Only ever the largest measured value: the keyboard takes
    /// this layer down when it comes up, and 「half the screen」 means the screen.
    @State private var screenHeight: CGFloat = 0
    /// Whether the occupancy panel is open. A `Menu` cannot host it: iOS flattens menu content to one leaf per
    /// row, so the two columns collapse back into a list (`ChatView.contextUsageTag`).
    @State private var showsContextUsagePanel = false
    /// The conversation the host asked for, kept beside the view model so a parameter change can be noticed
    /// and handed to `bind`. `@StateObject` keeps the first value it was given, which is exactly why the
    /// switch has to be observed rather than rebuilt.
    private let conversation: ChatConversation

    /// The lines the text field may take before it scrolls instead of growing.
    private var composerLineCap: Int {
        ChatComposerGrowth.lineCap(screenHeight: screenHeight, lineHeight: Self.bodyLineHeight)
    }

    /// The line height `Font.body` actually paints at the user's text size, so a Dynamic Type reader gets
    /// fewer lines rather than a field that overflows its half.
    private static var bodyLineHeight: CGFloat {
        #if canImport(UIKit) && os(iOS)
        return UIFont.preferredFont(forTextStyle: .body).lineHeight
        #else
        return 22
        #endif
    }

    /// The named space the tail is measured in: the scroll view's own bounds, so a `maxY` reads as a
    /// distance from the bottom of what the user can see.
    private static let scrollSpace = "chatTranscriptSpace"
    private static let tailMarker = "chatTranscriptTail"

    public init(
        streaming: any AgentStreaming,
        commands: (any AgentCommanding)? = nil,
        history: (any ChatHistoryReading)? = nil,
        config: (any SessionConfiguring)? = nil,
        workspace: (any SessionWorkspaceReading)? = nil,
        confirming: (any ToolConfirming)? = nil,
        plan: (any PlanReading)? = nil,
        contextUsage: (any ContextUsageReading)? = nil,
        conversation: ChatConversation
    ) {
        _vm = StateObject(wrappedValue: ChatViewModel(
            streaming: streaming,
            confirming: confirming,
            commands: commands,
            history: history,
            config: config,
            workspace: workspace,
            plan: plan,
            contextUsage: contextUsage,
            conversation: conversation
        ))
        self.conversation = conversation
    }

    public var body: some View {
        VStack(spacing: 0) {
            transcript
            ChatInputBar(vm: vm, lineCap: composerLineCap)
        }
        .background(GeometryReader { proxy in
            Color.clear
                .onAppear { screenHeight = max(screenHeight, proxy.size.height) }
                .onChange(of: proxy.size.height) { _, height in
                    screenHeight = max(screenHeight, height)
                }
        })
        .harnaxScreen()
        .navigationTitle(vm.conversation.title)
        .toolbar {
            // Declared first so it sits left of the workspace entry, which is where the console keeps the
            // readout — beside the title, ahead of the action buttons
            // (`harnax-webui/src/pages/session/index.tsx:359-361`). This is the one unwrap of the reading:
            // the model keeps no reading for the legs that answer without one, so the absence here *is* the
            // judgement (`ContextUsageReadout`).
            if let usage = vm.contextUsage {
                ToolbarItem(placement: .primaryAction) { contextUsageTag(usage) }
            }
            // A deliberate fork from the console, which draws its Workspace button always and checks the status
            // when it is tapped (`harnax-webui/src/pages/session/index.tsx:280-292`): with no sandbox manager
            // running every workspace route is a 404 (`SandboxWorkspaceController.kt:398-416`), so this corner
            // only offers the drawer once a status read said the sandbox is up. The plan's drawer lost the corner
            // — a plan tool call opens it by itself (`ChatWindow.tsx:1501`).
            if vm.workspacePanel != nil, vm.sandboxIsRunning {
                ToolbarItem(placement: .primaryAction) { workspaceButton }
            }
        }
        .sheet(isPresented: $vm.isWorkspacePresented) {
            // The panel the conversation owns, so a switch while the drawer is up cannot leave it listing the
            // session the user just left (`bind` retires it).
            if let panel = vm.workspacePanel {
                WorkspaceSheet(vm: panel)
            }
        }
        .sheet(isPresented: $vm.isPlanPanelPresented) {
            // The panel the conversation already reads its card off, rather than one the sheet builds and
            // unbuilds: closing the drawer must not stop the plan the stream is still showing.
            if let panel = vm.planPanel {
                PlanPanelView(vm: panel, onClose: { vm.isPlanPanelPresented = false })
            }
        }
        .task(id: conversation) {
            vm.bind(conversation)
            // Two reads, one after the other: the rows on screen and the flags that decide which composer
            // controls are real. The second is what the gating matrix reads off
            // (`ChatWindow.tsx:718-737`), so it has to be re-taken for every conversation.
            await vm.load()
            await vm.loadComposerConfig()
            // And the one read that decides whether the workspace entry exists at all.
            await vm.refreshSandboxStatus()
            // The occupancy tag is per-conversation as well, and `bind` cleared the previous one.
            await vm.refreshContextUsage()
        }
        .onDisappear { vm.detach() }
    }

    // MARK: - sandbox workspace

    private var workspaceButton: some View {
        Button { vm.isWorkspacePresented = true } label: {
            Image(systemName: "folder")
        }
        .accessibilityLabel(hx("chat.workspace.title"))
    }

    // MARK: - context occupancy

    /// The header readout: how full the context the agent holds is, and which number that is.
    ///
    /// The console draws it as a tag with a hover tooltip (`harnax-webui/src/pages/session/index.tsx:32-83`); a
    /// phone has no hover, so the same reading opens from the chip — the chip for the headline, the tooltip's
    /// five rows for what it lists. Both halves come from `ContextUsageReadout`, so which number answers which
    /// question is decided once and not in a view. The rows open and close nothing: the readout reports, it
    /// decides no part of the context.
    ///
    /// A popover, not the `Menu` this first used: iOS gives every leaf of menu content its own row, so a word
    /// and the number it bills cannot share one there, whatever is nested in between.
    ///
    /// Absent rather than `0%`, the way the console hides its tag
    /// (`index.tsx:361`): `ChatViewModel.contextUsage` only ever holds a reading that passed
    /// `ContextUsage.isReadable`, and both legs that answer without one — no instance holds the session, the
    /// session was never bound — say the router cannot see this context, never that the context is empty.
    @ViewBuilder
    private func contextUsageTag(_ usage: ContextUsage) -> some View {
        Button { showsContextUsagePanel = true } label: {
            // `orange` for a context that has reached the automatic trigger, neutral for one that has not
            // (`index.tsx:74`): at that point the next turn compacts this context whether or not anyone
            // asks, and a quiet pill would be the readout withholding the only news it has.
            HXChip(
                ContextUsageReadout.headline(for: usage),
                tone: usage.isAtAutoTrigger ? .warning : nil
            )
        }
        .popover(isPresented: $showsContextUsagePanel, arrowEdge: .bottom) {
            // `.popover` rather than the sheet iPhone would otherwise pick for a compact window: the reading
            // belongs beside the chip it explains.
            contextUsagePanel(usage)
                .presentationCompactAdaptation(.popover)
        }
        .accessibilityLabel(hx("chat.context.usage"))
        .accessibilityValue(ContextUsageReadout.headline(for: usage))
    }

    /// The five readings as a two-column table: the word on the left, the number it bills right-aligned
    /// against the other four.
    ///
    /// `Grid` does the alignment the console's tooltip gets from a table (`index.tsx:61-71`): each column sizes
    /// off its widest cell, so the numbers share a right edge without a fixed width a longer locale would clip.
    /// The floor on the panel is what makes it read as two columns rather than as a list with a number glued to
    /// each word, and the word column is the one that swallows the slack: with the numbers held to their own
    /// width, that column keeps the right edge of the table wherever the panel lands.
    ///
    /// `.footnote`, not `.subheadline`, because of the widest row measured at both sizes — the English word for
    /// the window row and the English `not recorded yet` together need 382pt at 15pt against a 375pt phone, and
    /// 344pt at 13pt.
    private func contextUsagePanel(_ usage: ContextUsage) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HXText("chat.context.usage")
                .font(.subheadline.weight(.medium))
            Divider()
            Grid(alignment: .leading, horizontalSpacing: 20, verticalSpacing: 8) {
                ForEach(ContextUsageReadout.rows(for: usage), id: \.label) { row in
                    GridRow {
                        Text(verbatim: row.label)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .foregroundStyle(Color.hx(.textSecondary))
                        Text(verbatim: row.value)
                            .gridColumnAlignment(.trailing)
                    }
                }
            }
            .font(.footnote)
        }
        .padding(16)
        .frame(minWidth: 320, alignment: .leading)
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
            // Three different screens share one shape here — an unread conversation, a failed read and a
            // genuinely empty one all have no rows — so they are told apart rather than collapsed into the
            // empty state, which would read as though the session had nothing in it.
            if let failure = vm.historyFailure {
                HXStateView(.error, message: failure) {
                    Task { await vm.load() }
                }
                .padding(.top, 40)
            } else if vm.isLoadingHistory {
                HXStateView(.loading)
                    .padding(.top, 40)
            } else {
                HXStateView(.empty, message: hx("chat.empty.hint"))
                    .padding(.top, 40)
            }
        } else {
            LazyVStack(alignment: .leading, spacing: 12) {
                ForEach(vm.transcript.turns) { turn in
                    ChatTurnRow(
                        turn: turn,
                        isReading: vm.isStreaming && turn.outcome == .streaming,
                        vm: vm
                    )
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
            .accessibilityLabel(hx("chat.action.jumpToBottom"))
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

/// The console's `inputCard` (`ChatWindow.tsx:3459-3700`): the strip of pictures waiting to go, the text box,
/// and the row of chips under it that writes the conversation's four settings.
private struct ChatInputBar: View {
    @ObservedObject var vm: ChatViewModel
    /// The ceiling the field grows to. Past it the field scrolls rather than eating the transcript.
    let lineCap: Int
    @State private var showsPhotoPicker = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let notice = vm.composerNotice {
                HXBanner(
                    "chat.composer.notice",
                    message: notice.text,
                    systemImage: Self.icon(for: notice.tone),
                    tone: Self.slot(for: notice.tone)
                )
                // A toast times out on its own; a banner has to be put away. Tapping it away is also the only
                // way the same sentence can be said twice in a row.
                .onTapGesture { vm.composerNotice = nil }
            }

            if !vm.images.isEmpty {
                ChatImageStrip(images: vm.images) { vm.removeImage(at: $0) }
            }

            HStack(alignment: .bottom, spacing: 10) {
                TextField(
                    hx(vm.isStreaming ? "chat.input.busy" : "chat.input.placeholder"),
                    text: $vm.draft,
                    axis: .vertical
                )
                .font(.body)
                .foregroundStyle(Color.hx(.textPrimary))
                .tint(Color.hx(.brand))
                // Half the screen for a long draft, then the field's own scroll: a pasted answer stays
                // readable without the transcript above it being pushed off screen.
                .lineLimit(2 ... lineCap)
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

            ChatComposerToolbar(vm: vm, showsPhotoPicker: $showsPhotoPicker)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(Color.hx(.surface))
        .confirmationDialog(
            Text(verbatim: hx(vm.pendingCommand?.kind == .stopSandbox
                ? "chat.sandbox.stop.title"
                : "chat.clear.title")),
            isPresented: Binding(
                get: { vm.pendingCommand != nil },
                set: { if !$0 { vm.cancelPendingCommand() } }
            ),
            titleVisibility: .visible
        ) {
            Button(role: .destructive) {
                vm.confirmPendingCommand()
            } label: {
                HXText(vm.pendingCommand?.kind == .stopSandbox ? "chat.sandbox.stop.action" : "chat.clear.action")
            }
            Button(role: .cancel) {
                vm.cancelPendingCommand()
            } label: {
                HXText("common.cancel")
            }
        } message: {
            Text(verbatim: hx(vm.pendingCommand?.kind == .stopSandbox
                ? "chat.sandbox.stop.note"
                : "chat.clear.note"))
        }
    }

    private static func icon(for tone: ChatComposerNotice.Tone) -> String {
        switch tone {
        case .info: return "info.circle"
        case .warning: return "exclamationmark.triangle"
        case .error: return "exclamationmark.triangle"
        }
    }

    private static func slot(for tone: ChatComposerNotice.Tone) -> PaletteSlot {
        switch tone {
        case .info: return .brand
        case .warning: return .warning
        case .error: return .danger
        }
    }
}

/// The pictures waiting for the send, each with its own way of being taken back out
/// (`ChatWindow.tsx:3463-3477`).
private struct ChatImageStrip: View {
    let images: [String]
    let onRemove: (Int) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(Array(images.enumerated()), id: \.offset) { index, dataURL in
                    ZStack(alignment: .topTrailing) {
                        ChatImageThumb(dataURL: dataURL)
                        Button {
                            onRemove(index)
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .font(.system(size: 17))
                                .foregroundStyle(Color.hx(.surface), Color.hx(.textSecondary))
                        }
                        .buttonStyle(.plain)
                        .padding(3)
                        .accessibilityLabel(hx("chat.image.remove"))
                    }
                }
            }
        }
    }
}

/// The chip row. Every one of them stays tappable even when the model forbids it — the console's gating is a
/// muted style plus a warning, not a dead control, because a dead control has nothing to say
/// (spec's matrix, `ChatWindow.tsx:3493-3600`).
private struct ChatComposerToolbar: View {
    @ObservedObject var vm: ChatViewModel
    @Binding private var showsPhotoPicker: Bool
    /// Whether the chip is asking which source the picture comes from. The console asks no question because its
    /// one picture control clicks a file input and lets the operating system decide
    /// (`ChatWindow.tsx:3500`); `PhotosPicker` has no camera route at all, so this side asks the question in
    /// words and hands the shot to UIKit.
    @State private var showsSourceSheet = false
    /// Whether the camera is open. Its own flag, because it is its own sheet.
    @State private var showsCamera = false

    init(vm: ChatViewModel, showsPhotoPicker: Binding<Bool>) {
        self.vm = vm
        _showsPhotoPicker = showsPhotoPicker
    }

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 7) {
                imageChip
                ChatComposerChip(
                    titleKey: "chat.composer.think",
                    systemImage: "lightbulb",
                    isOn: vm.composer.enableThink,
                    isMuted: !vm.composer.modelSupportReasoning
                ) {
                    vm.toggleThink()
                }
                ChatComposerChip(
                    titleKey: "chat.composer.search",
                    systemImage: "globe",
                    isOn: vm.composer.enableSearch,
                    isMuted: !vm.composer.canToggleSearch
                ) {
                    vm.toggleSearch()
                }
                ChatComposerChip(
                    titleKey: "chat.composer.plan",
                    systemImage: "list.bullet",
                    isOn: vm.composer.enablePlan
                ) {
                    vm.togglePlan()
                }
                permissionChip
                // The compaction entry, in the console's place in the row — after the permission mode, before
                // the two that destroy things (`ChatWindow.tsx:3716-3765`). It asks for no confirmation: it is
                // the one command here that leaves the conversation itself untouched, because the bubbles on
                // screen come from the archive and keep every turn it folds away
                // (`ChatViewModel.requestCompact`).
                ChatComposerChip(
                    titleKey: "chat.composer.compact",
                    systemImage: "arrow.down.right.and.arrow.up.left",
                    // `run(command:)` parks `isStreaming` for the length of the one request, which is what the
                    // console's separate `compacting` flag does (`:3717`).
                    isMuted: vm.isStreaming
                ) {
                    vm.requestCompact()
                }
                // The console carries what the entry does as the item's hover title
                // (`ChatWindow.tsx:3718-3721`); a chip has no hover, so the sentence goes to the description a
                // screen reader speaks after the chip's own name.
                .accessibilityHint(hx("chat.context.compactTip"))
                // Both go dead for the length of a run, the way the send button turns into a stop button:
                // a command on a busy session gets refused server-side, so the chips have to say they are
                // not available rather than look tappable (`ChatWindow.tsx:3679-3686`).
                ChatComposerChip(
                    titleKey: "chat.composer.stopSandbox",
                    systemImage: "stop",
                    isMuted: vm.isStreaming
                ) {
                    vm.requestStopSandbox()
                }
                ChatComposerChip(
                    titleKey: "chat.composer.clear",
                    systemImage: "trash",
                    isMuted: vm.isStreaming
                ) {
                    vm.requestClear()
                }
            }
        }
        .chatPhotoPicker(isPresented: $showsPhotoPicker) { urls in
            vm.addImages(urls)
        }
        .chatCameraPicker(isPresented: $showsCamera) { urls in
            vm.addImages(urls)
        }
        // The two sources, asked in the one place the console lets the operating system answer it. A row that
        // this device cannot honour is still on screen and still answers with a sentence, because a control
        // with nothing to say is the thing this row is built to avoid.
        .confirmationDialog(
            Text(verbatim: hx("chat.image.source")),
            isPresented: $showsSourceSheet,
            titleVisibility: .visible
        ) {
            Button {
                showsPhotoPicker = true
            } label: {
                HXText("chat.image.album")
            }
            Button {
                if vm.requestCamera(hasCamera: ChatCameraDevice.isAvailable) { showsCamera = true }
            } label: {
                HXText("chat.image.camera")
            }
            Button(role: .cancel) {
                showsSourceSheet = false
            } label: {
                HXText("common.cancel")
            }
        }
    }

    /// The picture button. A model with no vision cannot pick, but a picture already in the strip still goes
    /// out — the console blocks the picker, never the state (`ChatWindow.tsx:3493-3505`). The chip stays
    /// tappable so the refusal has somewhere to be said; on the test host the sheet is simply absent.
    private var imageChip: some View {
        ChatComposerChip(
            titleKey: "chat.composer.image",
            systemImage: "photo",
            isOn: !vm.images.isEmpty,
            isMuted: !vm.canPickImages
        ) {
            if vm.requestImages() { showsSourceSheet = true }
        }
    }

    @ViewBuilder
    private var permissionChip: some View {
        let mode = vm.composer.permissionMode
        if vm.canPickPermission {
            Menu {
                // `allCases` order, which is the dropdown's order (`ChatWindow.tsx:3601-3638`).
                ForEach(vm.permissionOptions, id: \.self) { option in
                    Button {
                        vm.selectPermission(option)
                    } label: {
                        HStack(spacing: 6) {
                            HXText(option.titleKey)
                            if option == mode {
                                Image(systemName: "checkmark")
                            }
                        }
                    }
                }
            } label: {
                ChatComposerLabel(
                    titleKey: mode.titleKey,
                    systemImage: "bolt",
                    // Anything but the runtime's own default is worth looking at (`:3633`).
                    isOn: mode != .defaultMode
                )
            }
            .menuStyle(.borderlessButton)
        } else {
            // A `task-` conversation: the mode it runs on is shown, and no change is offered. Tapping says so.
            ChatComposerChip(
                titleKey: mode.titleKey,
                systemImage: "lock",
                isMuted: true
            ) {
                vm.selectPermission(mode)
            }
        }
    }
}

/// One chip's contents, shared by the plain buttons and the permission menu's label.
private struct ChatComposerLabel: View {
    let titleKey: String
    let systemImage: String
    var isOn = false
    var isMuted = false

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: systemImage)
                .font(.system(size: 12))
            HXText(titleKey)
                .font(.caption)
                .lineLimit(1)
        }
        .foregroundStyle(Color.hx(isOn ? .onBrand : .textSecondary))
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(Color.hx(isOn ? .brand : .surfaceAlt), in: Capsule())
        .overlay(Capsule().strokeBorder(Color.hx(.separator), lineWidth: isOn ? 0 : 1))
        .opacity(isMuted ? 0.45 : 1)
    }
}

private struct ChatComposerChip: View {
    let titleKey: String
    let systemImage: String
    var isOn = false
    var isMuted = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ChatComposerLabel(titleKey: titleKey, systemImage: systemImage, isOn: isOn, isMuted: isMuted)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(hx(titleKey))
    }
}
