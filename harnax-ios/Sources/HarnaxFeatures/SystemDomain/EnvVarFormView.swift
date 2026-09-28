import SwiftUI
import HarnaxCore
import HarnaxKit

/// S3 — the create/edit sheet for one environment variable.
///
/// Fields are the request DTO's fields and nothing else. `enabled` appears on the create path only, because
/// `EnvVariableUpdateRequest` has no such property (`EnvVariableUpdateRequest.kt:8-24`); while editing, the
/// switch lives on the row's own `PUT /{id}/toggle`.
///
/// The value field is the delicate one. A sensitive row's stored text never reaches this device — the list
/// carried a mask — so the field starts empty, the mask is printed as a hint, and an empty field means
/// “keep what is stored” (`EnvVarFormViewModel.submittedValue`).
public struct EnvVarFormView: View {
    @StateObject private var vm: EnvVarFormViewModel

    private let onSaved: () -> Void

    public init(row: EnvVarSummary?, catalog: any EnvVarCataloging, onSaved: @escaping () -> Void) {
        _vm = StateObject(wrappedValue: EnvVarFormViewModel(row: row, catalog: catalog))
        self.onSaved = onSaved
    }

    public var body: some View {
        HXSystemFormSheet(
            titleKey: vm.isEditing ? "env.var.edit" : "env.var.create",
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
    }

    private var fields: some View {
        VStack(alignment: .leading, spacing: 14) {
            HXSystemFieldLabel("env.var.key.label", hint: vm.isEditing ? nil : hx("env.var.key.hint"))
            HXField("env.var.key.placeholder", text: $vm.key, systemImage: "textformat")

            HXSystemFieldLabel("env.var.value.label", hint: valueHint)
            HXField(
                "env.var.value.placeholder",
                text: $vm.value,
                systemImage: vm.sensitive ? "lock" : "textformat"
            )

            HXSystemFieldLabel("env.var.note.label")
            HXField("env.var.note.placeholder", text: $vm.note, systemImage: "note.text")

            HXGroupCard {
                toggleRow(titleKey: "env.sensitive", hintKey: "env.var.sensitive.hint", isOn: $vm.sensitive)
                if !vm.isEditing {
                    toggleRow(titleKey: "state.action.enable", hintKey: nil, isOn: $vm.enabled, divider: false)
                }
            }

            if vm.isEditing && !vm.hasChanges {
                HXText("env.var.unchanged")
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.textTertiary))
            }
        }
    }

    /// The value field's helper line. A sensitive row gets the mask itself, so the person editing can see
    /// that something is stored here without this device ever holding the plaintext.
    private var valueHint: String? {
        if vm.isValueBlankBecauseMasked, let masked = vm.maskedDisplay {
            return hx("env.var.value.masked", masked)
        }
        return vm.isEditing ? hx("env.var.value.keep") : nil
    }

    private func toggleRow(
        titleKey: String,
        hintKey: String?,
        isOn: Binding<Bool>,
        divider: Bool = true
    ) -> some View {
        HXRow(text: hx(titleKey), subtitle: hintKey.map { hx($0) }, systemImage: nil, divider: divider) {
            Toggle(hx(titleKey), isOn: isOn)
                .labelsHidden()
                .tint(Color.hx(.brand))
        }
    }
}
