import SwiftUI
import HarnaxCore
import HarnaxKit

/// The console's `SettingsModal` as a sheet — the only way this app creates a conversation.
///
/// Three fields, because three fields is what the route takes (`SessionCreateRequest.kt:11-31`). The console
/// renders a fourth control, an `isPublic` switch (`SettingsModal.tsx:215-225`), whose value never reaches
/// `createData` (`:95-101`) and has no counterpart on the DTO: a copy of it here would be a control that lies,
/// so it is left out rather than hidden.
///
/// What the sheet does *not* do is open what it created. `POST /api/admin/sessions` answers `ResultVo<Void>`
/// (`SessionController.kt:80-89`), so there is no new id to navigate on — `onCreated` tells the list to re-read
/// and the sheet closes (`specs/02-session-chat.md` §"iOS 适配注意点" 15).
public struct SessionCreateSheet: View {
    @StateObject private var vm: SessionCreateViewModel
    @Environment(\.dismiss) private var dismiss

    public init(creating: any SessionCreating, onCreated: @escaping () -> Void = {}) {
        _vm = StateObject(wrappedValue: SessionCreateViewModel(creating: creating, onCreated: onCreated))
    }

    public var body: some View {
        HXSystemFormSheet(
            titleKey: "session.create.sheet.title",
            canSubmit: vm.canSubmit,
            isSaving: vm.isSaving,
            errorText: vm.errorText,
            onSave: { Task { await vm.save() } }
        ) {
            fields
        }
        .onChange(of: vm.created) { _, created in
            if created { dismiss() }
        }
        // The picker's two page routes load after the sheet opens, exactly as the console loads them
        // (`SettingsModal.tsx:64-86`), and the title box is live while they are out.
        .task { await vm.loadChoices() }
    }

    private var fields: some View {
        VStack(alignment: .leading, spacing: 14) {
            HXSystemFieldLabel(
                "session.create.title",
                hint: hx("session.create.title.hint", SessionCreateViewModel.titleLimit)
            )
            HXField("session.create.title.placeholder", text: $vm.title, systemImage: "textformat")
            titleStatusLine

            HXSystemFieldLabel(
                "session.create.note",
                hint: hx("session.create.counter", vm.note.count, SessionCreateViewModel.descriptionLimit)
            )
            descriptionField

            HXSystemFieldLabel("session.create.executor", hint: hx("session.create.executor.hint"))
            executorPicker
            executorSourceLine
        }
    }

    // MARK: - title

    /// The verdict, on the field rather than on the save item. The duplicate route's answer is only ever
    /// about the exact text that was asked about, so the line has to say which one it speaks for — and the
    /// sentence is deliberately "this name is taken", never "you already have a conversation called X": the
    /// count behind it has no tenant and no creator condition (`SessionMapper.xml:44-46`), so the conversation
    /// that owns the name may belong to somebody this list will never show.
    @ViewBuilder
    private var titleStatusLine: some View {
        switch vm.titleVerdict {
        case .unchecked:
            EmptyView()
        case .checking:
            statusLine(hx("session.create.title.checking"), tone: .textTertiary, systemImage: "hourglass")
        case .available:
            statusLine(hx("session.create.title.available"), tone: .success, systemImage: "checkmark.circle")
        case .taken:
            statusLine(hx("session.create.title.taken"), tone: .danger, systemImage: "exclamationmark.circle")
        case let .checkFailed(message):
            statusLine(message, tone: .warning, systemImage: "questionmark.circle")
        }
    }

    /// The description is the console's `TextArea` with `maxLength={500}` (`SettingsModal.tsx:167-176`). The
    /// DTO puts no `@Size` on that field, so this box and its counter are the only place the 500 exists.
    private var descriptionField: some View {
        TextField(hx("session.create.note.placeholder"), text: $vm.note, axis: .vertical)
            .font(.body)
            .foregroundStyle(Color.hx(.textPrimary))
            .tint(Color.hx(.brand))
            .lineLimit(2...6)
            #if canImport(UIKit)
            .textInputAutocapitalization(.sentences)
            #endif
            .padding(.horizontal, 13)
            .padding(.vertical, 12)
            .background(Color.hx(.surface), in: RoundedRectangle(cornerRadius: 13, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 13, style: .continuous)
                    .strokeBorder(Color.hx(.separator), lineWidth: 1)
            )
    }

    // MARK: - executor

    /// One control over two groups, mutually exclusive by the option's own `selectionKey` rather than by
    /// parsing: the console encodes the choice as `"agent:<id>"` / `"team:<id>"` because the two lists are
    /// separate routes and a team can carry an agent's number (`SettingsModal.tsx:92-94`), and
    /// `SessionCreateDraft` is what turns it back into one of `agentId`/`teamId`.
    private var executorPicker: some View {
        Group {
            if vm.choices == nil, vm.isLoadingChoices {
                HXStateView(.loading)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                Picker(selection: $vm.selectionKey) {
                    Text(verbatim: hx("session.create.executor.placeholder"))
                        .tag(Optional<String>.none)
                    if !vm.agents.isEmpty {
                        Section(header: HXText("session.create.kind.agent")) {
                            ForEach(vm.agents) { option in
                                executorRow(option)
                            }
                        }
                    }
                    if !vm.teams.isEmpty {
                        Section(header: HXText("session.create.kind.team")) {
                            ForEach(vm.teams) { option in
                                executorRow(option)
                            }
                        }
                    }
                } label: {
                    HXText("session.create.executor")
                }
                .pickerStyle(.menu)
                .tint(Color.hx(.brand))
            }
        }
    }

    /// The console's `${name} - ${description}` label (`SettingsModal.tsx:196-208`), which is why
    /// `SessionExecutorOption` carries the detail at all.
    private func executorRow(_ option: SessionExecutorOption) -> some View {
        Text(verbatim: option.detail == nil ? option.name : "\(option.name) - \(option.detail!)")
            .tag(Optional(option.selectionKey))
    }

    /// The three ways the picker's source is not a plain list: one group failed, both failed, or nothing
    /// answered at all. Naming the dead group is the point — an empty section would read as an account with no
    /// teams rather than a route that threw.
    @ViewBuilder
    private var executorSourceLine: some View {
        if let notice = vm.unavailableNotice {
            HXBanner(
                "session.create.executor.partial",
                message: notice,
                systemImage: "exclamationmark.triangle",
                tone: vm.executorSourceIsUsable ? .warning : .danger
            )
        }
        if let choicesErrorText = vm.choicesErrorText {
            HStack(alignment: .top, spacing: 10) {
                statusLine(choicesErrorText, tone: .danger, systemImage: "wifi.slash")
                Spacer(minLength: 0)
                Button {
                    Task { await vm.loadChoices() }
                } label: {
                    HXText("common.retry")
                }
                .buttonStyle(.plain)
                .foregroundStyle(Color.hx(.brand))
            }
        }
    }

    // MARK: - parts

    private func statusLine(_ text: String, tone: PaletteSlot, systemImage: String) -> some View {
        HStack(alignment: .top, spacing: 6) {
            Image(systemName: systemImage)
                .font(.caption)
                .foregroundStyle(Color.hx(tone))
            Text(verbatim: text)
                .font(.footnote)
                .foregroundStyle(Color.hx(tone))
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
