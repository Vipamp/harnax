import SwiftUI
import HarnaxCore
import HarnaxKit

/// SKILL-1 / SKILL-2 — the repository form sheet, create and edit in one screen.
///
/// The field order is the console's (`harnax-webui/src/pages/skill/components/RepositoryForm.tsx:188-327`):
/// name, type, the pair the chosen type owns, then version, description and the two switches. What the sheet
/// adds is only what a phone forces — the type as a row of chips instead of a `Select`, and the required rule
/// as a sentence under the field instead of a red border, because a refused submit has nowhere to scroll to.
///
/// Nothing here decides what goes out: `SkillRepositoryFormModel` builds both bodies, and the list view model
/// runs the reload and publishes the answer, so this screen holds no request logic of its own.
///
/// Public so the DEBUG walkthrough can frame the sheet from a launch argument — the simulator takes no input,
/// so a sheet the harness cannot name is a sheet nobody can review (`App/HarnaxDebugScreens.swift`).
public struct SkillRepositoryFormSheet: View {
    @ObservedObject private var form: SkillRepositoryFormModel
    private let onSubmit: () async -> Void

    public init(form: SkillRepositoryFormModel, onSubmit: @escaping () async -> Void) {
        self.form = form
        self.onSubmit = onSubmit
    }

    public var body: some View {
        HXSystemFormSheet(
            titleKey: form.isCreate ? "skill.repository.create" : "skill.repository.edit",
            // The console's Submit stays live while a required field is empty and lets `validateFields`
            // reject, so the operator is told which field is missing rather than left with a dead button
            // (`RepositoryForm.tsx:61-67`). Only an in-flight save may stop the tap.
            canSubmit: !form.isSaving,
            isSaving: form.isSaving,
            errorText: form.errorText,
            onSave: { Task { await onSubmit() } }
        ) {
            fields
        }
    }

    private var fields: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(verbatim: hx(form.isCreate ? "skill.repository.subtitle.create" : "skill.repository.subtitle.edit"))
                .font(.footnote)
                .foregroundStyle(Color.hx(.textSecondary))
                .fixedSize(horizontal: false, vertical: true)

            HXSystemFieldLabel("skill.repository.name", hint: form.problem(for: .name))
            field(.name, kind: .text)

            HXSystemFieldLabel("skill.repository.type")
            typeRow

            if !form.credentialFields.isEmpty {
                ForEach(form.credentialFields, id: \.rawValue) { field in
                    HXSystemFieldLabel(field.titleKey, hint: form.problem(for: field))
                    self.field(field, kind: field == .url || field == .registry ? .URL : .text)
                }
            }

            if form.showsStoredArchive {
                HXSystemFieldLabel("skill.repository.zip.file", hint: hx("skill.repository.zip.note"))
                HXValueText(form.storedArchive ?? "", lines: 2)
            }

            HXSystemFieldLabel("skill.repository.version", hint: form.problem(for: .version))
            field(.version, kind: .text)

            HXSystemFieldLabel("skill.repository.description", hint: form.problem(for: .description))
            field(.description, kind: .text)

            HXGroupCard {
                HXRow("skill.repository.status", subtitle: hx("skill.repository.status.hint")) {
                    Toggle(hx("skill.repository.status"), isOn: Binding(
                        get: { form.enabled },
                        set: { form.setEnabled($0) }
                    ))
                    .labelsHidden()
                    .tint(Color.hx(.brand))
                }
                HXRow(
                    "skill.repository.public",
                    subtitle: form.canChangeVisibility
                        ? hx("skill.repository.publicHint")
                        : hx("skill.repository.noPermission"),
                    divider: false
                ) {
                    Toggle(hx("skill.repository.public"), isOn: Binding(
                        get: { form.shared },
                        set: { form.setIsPublic($0) }
                    ))
                    .labelsHidden()
                    .disabled(!form.canChangeVisibility)
                    .tint(Color.hx(.brand))
                }
            }
        }
    }

    /// One text field, bound through the model so the trimming and the required marks stay in one place.
    private func field(_ field: SkillRepositoryFormModel.Field, kind: HXInputKind) -> some View {
        HXField(field.placeholderKey, text: Binding(
            get: { form.text(for: field) },
            set: { form.setValue($0, for: field) }
        ), systemImage: field.systemImage, kind: kind)
    }

    /// A create chooses between the two transports; an edit cannot, because the update request has no type to
    /// carry a switch and the console disables the `Select` for exactly that reason
    /// (`RepositoryForm.tsx:203-206`).
    @ViewBuilder
    private var typeRow: some View {
        if form.isCreate {
            HXFlow(spacing: 8) {
                ForEach(SkillRepositoryFormModel.typeOptions, id: \.rawValue) { type in
                    Button {
                        form.setType(type)
                    } label: {
                        typeChip(type, selected: form.typeChoice == type)
                    }
                    .buttonStyle(.plain)
                }
            }
        } else {
            HXChip(hx(Self.titleKey(for: form.typeChoice)), tone: .brand)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func typeChip(_ type: SkillSourceType, selected: Bool) -> some View {
        Text(verbatim: hx(Self.titleKey(for: type)))
            .font(.caption.weight(.semibold))
            .foregroundStyle(selected ? Color.hx(.onBrand) : Color.hx(.textSecondary))
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(selected ? Color.hx(.brand) : Color.hx(.surfaceAlt), in: Capsule())
            .overlay(
                Capsule().strokeBorder(selected ? Color.clear : Color.hx(.separator), lineWidth: 1)
            )
    }

    /// The type names the list already shows on its rows, so a form and a row never call one source by two
    /// names.
    static func titleKey(for type: SkillSourceType) -> String {
        switch type {
        case .git: "skill.type.git"
        case .npm: "skill.type.npm"
        case .zip: "skill.type.zip"
        case .builtin: "skill.type.builtin"
        case .unknown: "skill.type.other"
        }
    }
}
