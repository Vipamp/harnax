import SwiftUI
import HarnaxCore
import HarnaxKit

/// `/agent/task` — the create/edit sheet for one scheduled task.
///
/// The field set is `§3.1`'s eight keys, in the order the console lists them, and the two switches are the
/// 0/1 columns rather than new concepts. Nothing here asks for `taskStatus`, `creator`, `active` or
/// `tenantId`: the service fills all four on create and overwrites `taskStatus` on every update
/// (`AgentTaskCrudServiceImpl.kt:97-105,156-157`), so a control for them would be a control that lies.
///
/// Validation is silent by design: `canSubmit` greys the save item and each field's hint states the rule it
/// enforces, which is how the two sibling forms in this app behave. The reason is the asymmetry this domain
/// cannot see around — a `@Valid` refusal costs a round trip and comes back as 400 `name: …` while a business
/// refusal comes back as 400 or **500** depending on which route ran (`§4.2`), so the only errors that read
/// alike are the ones raised before sending.
public struct TaskFormView: View {
    @StateObject private var vm: TaskFormViewModel

    /// Carries the sheet's parting news to the list, because two of the three things it can report are not
    /// failures: a saved edit has paused the task, and a 40902 is a row that was written (`§4.3`).
    private let onSaved: (TaskFormSaveOutcome) -> Void

    public init(row: AgentTaskSummary?, catalog: any AgentTaskCataloging, onSaved: @escaping (TaskFormSaveOutcome) -> Void) {
        _vm = StateObject(wrappedValue: TaskFormViewModel(row: row, catalog: catalog))
        self.onSaved = onSaved
    }

    public var body: some View {
        HXSystemFormSheet(
            titleKey: vm.isEditing ? "task.form.edit" : "task.form.create",
            canSubmit: vm.canSubmit,
            isSaving: vm.isSaving,
            errorText: vm.errorText,
            onSave: { Task { await vm.save() } }
        ) {
            fields
        }
        .onChange(of: vm.outcome) { _, outcome in
            if let outcome { onSaved(outcome) }
        }
        .task { await vm.loadAgents() }
    }

    private var fields: some View {
        VStack(alignment: .leading, spacing: 14) {
            // Said before the fields rather than after a save, because the pause is the one consequence of
            // editing that the form itself cannot undo (`§3.2`).
            if vm.isEditing {
                HXBanner(
                    "task.form.pause.warning",
                    systemImage: "pause.circle",
                    tone: .warning
                )
            }

            HXSystemFieldLabel("task.form.name")
            HXField("task.form.name.placeholder", text: $vm.name, systemImage: "textformat")

            HXSystemFieldLabel("task.form.agent", hint: vm.agentErrorText ?? hx("task.form.agent.hint"))
            agentPicker

            HXSystemFieldLabel("task.form.prompt")
            multilineField(placeholderKey: "task.form.prompt.placeholder", text: $vm.prompt, lines: 3...8)

            HXSystemFieldLabel("task.form.cron", hint: hx("task.form.cron.hint"))
            HXField("task.form.cron.placeholder", text: $vm.cronExpression, systemImage: "clock")
            presetChips
            if let preview = nextRunPreview {
                Text(verbatim: preview)
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.textTertiary))
                    .fixedSize(horizontal: false, vertical: true)
            }

            HXSystemFieldLabel("task.form.timeout", hint: hx("task.form.timeout.hint"))
            HXField("task.form.timeout.placeholder", text: $vm.timeoutText, systemImage: "gauge", kind: .number)

            HXGroupCard {
                toggleRow(titleKey: "task.form.concurrent", hintKey: "task.form.concurrent.hint", isOn: $vm.concurrent)
                toggleRow(
                    titleKey: "task.form.public",
                    hintKey: "task.form.public.hint",
                    isOn: $vm.isPublic,
                    divider: false
                )
            }

            HXSystemFieldLabel("task.form.description", hint: hx("task.form.description.hint"))
            multilineField(placeholderKey: "task.form.description.placeholder", text: $vm.note, lines: 2...5)

            if vm.isEditing, !vm.hasChanges {
                Text(verbatim: hx("task.form.unchanged"))
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.textTertiary))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    // MARK: - target agent

    /// A menu over `GET /agent-tasks/agents`, which is admin's own list of *running* agents rather than the
    /// agent domain's page (`§1` #12) — so the option set can legitimately miss the agent a stored task points
    /// at, and the view model re-adds that one row itself.
    private var agentPicker: some View {
        Group {
            if vm.agents.isEmpty, vm.isLoadingAgents {
                HXStateView(.loading)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                Picker(selection: agentSelection) {
                    Text(verbatim: hx("task.form.agent.placeholder")).tag(Int64?.none as Int64?)
                    ForEach(vm.agents) { option in
                        Text(verbatim: option.displayName ?? "").tag(Int64?.some(option.id ?? 0))
                    }
                } label: {
                    HXText("task.form.agent")
                }
                .pickerStyle(.menu)
                .tint(Color.hx(.brand))
            }
        }
    }

    private var agentSelection: Binding<Int64?> {
        Binding(get: { vm.agentId }, set: { vm.agentId = $0 })
    }

    // MARK: - cron presets

    /// The five shortcuts, as chips over the same text box. There is no preset/custom switch anywhere in this
    /// flow: the body carries one `cronExpression`, so a tap overwrites what was typed and typing clears the
    /// lit chip (`§3.3`).
    private var presetChips: some View {
        HXFlow(spacing: 8) {
            ForEach(CronPreset.allCases) { preset in
                Button {
                    vm.choose(preset)
                } label: {
                    presetChipLabel(title: hx(preset.titleKey), isSelected: vm.selectedPreset == preset)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(hx(preset.titleKey))
            }
        }
    }

    private func presetChipLabel(title: String, isSelected: Bool) -> some View {
        Text(verbatim: title)
            .font(.caption.weight(.semibold))
            .foregroundStyle(isSelected ? Color.hx(.onBrand) : Color.hx(.textSecondary))
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(isSelected ? Color.hx(.brand) : Color.hx(.surfaceAlt), in: Capsule())
            .overlay(
                Capsule().strokeBorder(isSelected ? Color.clear : Color.hx(.separator), lineWidth: 1)
            )
    }

    /// The expression previewed against GMT+8, which is the only timezone the console's own column set leaves
    /// to guess at (`§2.6`). Shown whatever the row's status, because on this sheet the expression is the
    /// subject and the pause is a side effect.
    private var nextRunPreview: String? {
        guard vm.validationErrorKey == nil,
              let date = QuartzCron.nextMatch(of: vm.cronExpression.trimmingCharacters(in: .whitespacesAndNewlines), after: Date())
        else { return nil }
        return hx("task.form.cron.preview", date.formatted(date: .abbreviated, time: .shortened))
    }

    // MARK: - parts

    private func toggleRow(titleKey: String, hintKey: String, isOn: Binding<Bool>, divider: Bool = true) -> some View {
        HXRow(text: hx(titleKey), subtitle: hx(hintKey), systemImage: nil, divider: divider) {
            Toggle(hx(titleKey), isOn: isOn)
                .labelsHidden()
                .tint(Color.hx(.brand))
        }
    }

    /// Both text areas of `§3.1`. `HXField` is a one-line box and the prompt column is a `TEXT` with no
    /// `@Size` at all (`AgentTaskCreateRequest.kt:35-37`), so the growing field is spelled out here rather
    /// than given a character ceiling.
    private func multilineField(placeholderKey: String, text: Binding<String>, lines: ClosedRange<Int>) -> some View {
        TextField(hx(placeholderKey), text: text, axis: .vertical)
            .font(.body)
            .foregroundStyle(Color.hx(.textPrimary))
            .tint(Color.hx(.brand))
            .lineLimit(lines)
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
}
