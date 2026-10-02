import SwiftUI
import HarnaxCore
import HarnaxKit

/// E2 — the create/edit sheet for one channel.
///
/// The field list is the conditional matrix rendered as views, and two rules drive it: which credential
/// fields exist depends on the type *and* the mode (`ChannelType.fields(mode:)`), and the mode selector itself
/// disappears on a type with one runnable mode. Everything else — the auto-start switch, the three capability
/// switches, the description — sits outside the matrix because it has nothing to do with the platform.
///
/// A masked credential is shown as the mask and sent back as the mask. The server recognises its own display
/// form and keeps the real value (`ChannelServiceImpl.kt:304-309`), so there is no "leave untouched" checkbox
/// to get wrong.
public struct ChannelFormView: View {
    @StateObject private var vm: ChannelFormViewModel
    private let onSaved: () -> Void

    public init(
        row: ChannelSummary?,
        catalog: any ChannelCataloging,
        agents: any AgentCataloging,
        onSaved: @escaping () -> Void
    ) {
        _vm = StateObject(wrappedValue: ChannelFormViewModel(row: row, catalog: catalog, agents: agents))
        self.onSaved = onSaved
    }

    public var body: some View {
        HXSystemFormSheet(
            titleKey: vm.row == nil ? "channel.create" : "channel.edit",
            canSubmit: vm.canSubmit,
            isSaving: vm.isSaving,
            errorText: vm.errorText,
            onSave: { Task { await vm.save() } }
        ) {
            fields
        }
        .onChange(of: vm.saved) { _, saved in
            if saved { onSaved() }
        }
        .task { await vm.loadAgents() }
    }

    private var fields: some View {
        VStack(alignment: .leading, spacing: 14) {
            HXSystemFieldLabel("channel.form.type")
            typeChips

            if vm.showsUnsupportedNotice {
                HXBanner(
                    "channel.type.unsupported.note",
                    systemImage: "exclamationmark.triangle",
                    tone: .warning
                )
            }

            HXSystemFieldLabel("channel.form.name")
            HXField("channel.name.placeholder", text: $vm.name, systemImage: "textformat")

            HXSystemFieldLabel("channel.form.agent", hint: vm.agentErrorText)
            agentPicker

            if vm.showsModePicker {
                HXSystemFieldLabel("channel.form.mode")
                modePicker
            }

            credentials

            HXGroupCard {
                HXRow(text: hx("channel.form.autoStart"), subtitle: hx("channel.form.autoStart.hint")) {
                    Toggle(hx("channel.form.autoStart"), isOn: $vm.autoStart)
                        .labelsHidden()
                        .tint(Color.hx(.brand))
                }
                thinkingRow
                HXRow(text: hx("channel.form.search")) {
                    Toggle(hx("channel.form.search"), isOn: $vm.searchEnabled)
                        .labelsHidden()
                        .tint(Color.hx(.brand))
                }
                HXRow(text: hx("channel.form.plan"), divider: false) {
                    Toggle(hx("channel.form.plan"), isOn: $vm.planEnabled)
                        .labelsHidden()
                        .tint(Color.hx(.brand))
                }
            }

            HXSystemFieldLabel("channel.form.description")
            HXField("channel.description.placeholder", text: $vm.description, systemImage: "note.text")

            if vm.row != nil { readOnlyBlocks }
        }
    }

    /// O7 — the five types stay on screen, and the one the runtime cannot serve stays unselectable.
    ///
    /// The option is not removed: a channel already stored as `http` has to show its own type in the row that
    /// opens it, and an operator comparing the two wants to see what the list offers. What it loses is the
    /// tap, plus the marker that says why. The rule itself lives in `ChannelFormViewModel.canSubmit` and
    /// `save()`, so a caller that never renders this row cannot get a write out either.
    private var typeChips: some View {
        HXFlow(spacing: 8) {
            ForEach(ChannelType.allCases, id: \.rawValue) { type in
                Button {
                    vm.setType(type)
                } label: {
                    typeChipLabel(
                        title: hx(type.titleKey),
                        marker: type.hasRuntimeAdaptor ? nil : hx("channel.type.unsupported"),
                        isSelected: vm.typeChoice == type
                    )
                }
                .buttonStyle(.plain)
                .disabled(!type.hasRuntimeAdaptor)
                .opacity(type.hasRuntimeAdaptor ? 1 : 0.55)
            }
        }
    }

    private func typeChipLabel(title: String, marker: String?, isSelected: Bool) -> some View {
        HStack(spacing: 5) {
            Text(verbatim: title)
            if let marker {
                Text(verbatim: "· " + marker)
                    .font(.caption2)
            }
        }
        .font(.caption.weight(.semibold))
        .foregroundStyle(isSelected ? Color.hx(.onBrand) : Color.hx(.textSecondary))
        .padding(.horizontal, 12)
        .padding(.vertical, 7)
        .background(isSelected ? Color.hx(.brand) : Color.hx(.surfaceAlt), in: Capsule())
        .overlay(
            Capsule().strokeBorder(isSelected ? Color.clear : Color.hx(.separator), lineWidth: 1)
        )
    }

    /// The picker is a menu over the first hundred agents, which is what the console asks the same route for
    /// (`harnax-webui/src/services/ant-design-pro/channel.ts:100-107`).
    ///
    /// A failed read is a dead end without this control: `agentId` is `@NotNull` on the create body, so an
    /// empty picker leaves `canSubmit` false and the sheet offers nothing but Cancel. The console can swallow
    /// the same failure into `console.error` and wait for a page reload
    /// (`harnax-webui/src/pages/channel/index.tsx:91-93`); on a phone the retry belongs on the field, which is
    /// what `ChannelFormViewModel`'s own note about `agentErrorText` promises. The button calls the same entry
    /// point `.task` calls, and that method only guards away a read while the list is filled or in flight.
    private var agentPicker: some View {
        VStack(alignment: .leading, spacing: 6) {
            Group {
                if vm.agents.isEmpty, vm.isLoadingAgents {
                    HXStateView(.loading)
                        .frame(maxWidth: .infinity, alignment: .leading)
                } else {
                    Picker(selection: agentSelection) {
                        Text(verbatim: hx("channel.form.agent.placeholder")).tag(Int64?.none as Int64?)
                        ForEach(vm.agents) { option in
                            Text(verbatim: option.name).tag(Int64?.some(option.id))
                        }
                    } label: {
                        HXText("channel.form.agent")
                    }
                    .pickerStyle(.menu)
                    .tint(Color.hx(.brand))
                }
            }
            if vm.agentErrorText != nil {
                Button {
                    Task { await vm.loadAgents() }
                } label: {
                    HXText("common.retry")
                }
                .buttonStyle(.hxInline)
                .disabled(vm.isLoadingAgents)
            }
        }
    }

    private var modePicker: some View {
        Picker(selection: modeSelection) {
            ForEach(vm.typeChoice?.modes ?? [], id: \.rawValue) { mode in
                Text(verbatim: hx(mode.titleKey)).tag(ChannelMode?.some(mode))
            }
        } label: {
            HXText("channel.form.mode")
        }
        .pickerStyle(.menu)
        .tint(Color.hx(.brand))
    }

    @ViewBuilder
    private var credentials: some View {
        if vm.typeChoice == nil {
            HXText("channel.form.pick.type")
                .font(.footnote)
                .foregroundStyle(Color.hx(.textTertiary))
        } else if vm.showsScanHint {
            HXBanner(
                "channel.wechat.scan",
                message: hx("channel.form.wechat.hint"),
                systemImage: "qrcode",
                tone: .brand
            )
        } else {
            ForEach(vm.visibleFields, id: \.key) { field in
                HXSystemFieldLabel(
                    field.labelKey,
                    hint: field.isSecret ? hx("channel.field.secret.hint") : nil
                )
                HXField(
                    field.placeholderKey,
                    text: binding(for: field),
                    systemImage: field.isSecret ? "lock" : "textformat",
                    secure: field.isSecret
                )
            }
        }
    }

    /// Thinking is the one switch with a third position on create, because omitting the field is how the form
    /// hands the decision to the bound model (`ChannelServiceImpl.kt:83`).
    @ViewBuilder
    private var thinkingRow: some View {
        if vm.row == nil {
            HXRow(text: hx("channel.form.think"), subtitle: hx("channel.form.think.hint")) {
                Picker(selection: $vm.thinkingChoice) {
                    Text(verbatim: hx("channel.think.follow")).tag(ChannelFormViewModel.ThinkingChoice.followModel)
                    Text(verbatim: hx("channel.think.on")).tag(ChannelFormViewModel.ThinkingChoice.on)
                    Text(verbatim: hx("channel.think.off")).tag(ChannelFormViewModel.ThinkingChoice.off)
                } label: {
                    HXText("channel.form.think")
                }
                .pickerStyle(.menu)
                .tint(Color.hx(.brand))
            }
        } else {
            HXRow(text: hx("channel.form.think"), subtitle: hx("channel.form.think.required")) {
                Toggle(hx("channel.form.think"), isOn: Binding(
                    get: { vm.thinkingChoice == .on },
                    set: { vm.thinkingChoice = $0 ? .on : .off }
                ))
                .labelsHidden()
                .tint(Color.hx(.brand))
            }
        }
    }

    /// The two blocks the console attaches to a stored channel and never lets anyone edit: the session id,
    /// minted once at create, and the callback URL, which the server only derives for a webhook row
    /// (`UpdateForm.tsx:340-481`, `ChannelServiceImpl.kt:231-236`).
    @ViewBuilder
    private var readOnlyBlocks: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let sessionId = vm.immutableSessionId {
                HXSystemFieldLabel("channel.form.sessionId", hint: hx("channel.form.sessionId.hint"))
                HXValueText(sessionId, lines: 2)
            }
            if let callbackUrl = vm.callbackUrl {
                HXSystemFieldLabel("channel.form.callbackUrl", hint: hx("channel.form.callbackUrl.hint"))
                HXValueText(callbackUrl, lines: 3)
            }
        }
    }

    private var agentSelection: Binding<Int64?> {
        Binding(get: { vm.agentId }, set: { vm.agentId = $0 })
    }

    private var modeSelection: Binding<ChannelMode?> {
        Binding(get: { vm.modeChoice }, set: { if let mode = $0 { vm.setMode(mode) } })
    }

    private func binding(for field: ChannelField) -> Binding<String> {
        Binding(
            get: { vm.fieldValue(for: field) },
            set: { vm.setFieldValue($0, for: field) }
        )
    }
}
