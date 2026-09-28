import SwiftUI
import HarnaxCore
import HarnaxKit

/// S4 — the create/edit sheet for one API Key.
///
/// The field set is `ApiKeyCreateRequest` and `ApiKeyUpdateRequest`, and the two differ: the update DTO has
/// no `name`, so an edit shows the stored name as a fact rather than as a field, and `enabled` exists only
/// on the update DTO, so the switch appears only while editing.
public struct ApiKeyFormView: View {
    @StateObject private var vm: ApiKeyFormViewModel

    /// Called once the write has landed and this sheet is finished with the key. It carries no value on
    /// purpose: the raw secret is presented inside this sheet and never travels to the list screen at all.
    private let onSaved: () -> Void

    /// Whether the one-time key surface is up. Only a create can raise it.
    @State private var showsPublished = false

    public init(
        row: ApiKeySummary?,
        account: AccountSnapshot?,
        catalog: any ApiKeyCataloging,
        onSaved: @escaping () -> Void
    ) {
        _vm = StateObject(wrappedValue: ApiKeyFormViewModel(row: row, account: account, catalog: catalog))
        self.onSaved = onSaved
    }

    public var body: some View {
        HXSystemFormSheet(
            titleKey: vm.isEditing ? "apikey.edit.title" : "apikey.create.title",
            canSubmit: vm.canSubmit,
            isSaving: vm.isSaving,
            errorText: vm.errorText,
            onSave: { Task { await vm.save() } }
        ) {
            fields
        }
        .onChange(of: vm.saved) { _, saved in
            guard saved else { return }
            if vm.published != nil {
                showsPublished = true
            } else {
                onSaved()
            }
        }
        .sheet(isPresented: $showsPublished) {
            if let created = vm.published {
                ApiKeyRawKeySheet(created: created, onClose: onSaved)
            }
        }
    }

    private var fields: some View {
        VStack(alignment: .leading, spacing: 14) {
            nameBlock

            HXSystemFieldLabel("apikey.scopes.label", hint: hx("apikey.scopes.hint"))
            HXGroupCard {
                ForEach(Array(ApiKeyScope.allCases.enumerated()), id: \.offset) { index, scope in
                    toggleRow(
                        titleKey: scope.titleKey,
                        hintKey: nil,
                        isOn: Binding(
                            get: { vm.selectedScopes.contains(scope) },
                            set: { on in
                                if on { vm.selectedScopes.insert(scope) } else { vm.selectedScopes.remove(scope) }
                            }
                        ),
                        divider: index < ApiKeyScope.allCases.count - 1
                    )
                }
            }

            HXSystemFieldLabel("apikey.rate.label", hint: hx("apikey.rate.hint"))
            HXField("apikey.rate.placeholder", text: $vm.rateLimitText, systemImage: "gauge", kind: .number)

            if vm.showsTenantField {
                HXSystemFieldLabel("apikey.tenant.label", hint: hx("apikey.tenant.hint"))
                HXField("apikey.tenant.placeholder", text: $vm.tenantText, systemImage: "building.2", kind: .number)
            }

            HXSystemFieldLabel("apikey.expires.label", hint: hx("apikey.expires.hint"))
            HXGroupCard {
                toggleRow(titleKey: "apikey.expires.label", hintKey: nil, isOn: $vm.hasExpiry, divider: vm.hasExpiry)
                if vm.hasExpiry {
                    DatePicker(
                        hx("apikey.expires.at"),
                        selection: $vm.expiryDate,
                        displayedComponents: [.date, .hourAndMinute]
                    )
                    .datePickerStyle(.compact)
                    .tint(Color.hx(.brand))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                }
            }

            if vm.isEditing {
                HXGroupCard {
                    toggleRow(titleKey: "state.action.enable", hintKey: nil, isOn: $vm.enabled, divider: false)
                }
            }
        }
    }

    /// On create a field; on edit a line of copy, because `PUT /update/{id}` has nowhere to put a name
    /// (`ApiKeyUpdateRequest.kt:6-21`).
    @ViewBuilder
    private var nameBlock: some View {
        if vm.isEditing {
            HXSystemFieldLabel("apikey.name.label", hint: hx("apikey.name.readonly"))
            HXRow(text: vm.name) { EmptyView() }
                .background(Color.hx(.surface), in: RoundedRectangle(cornerRadius: 13, style: .continuous))
        } else {
            HXSystemFieldLabel("apikey.name.label")
            HXField("apikey.name.placeholder", text: $vm.name, systemImage: "tag")
        }
    }

    private func toggleRow(
        titleKey: String,
        hintKey: String?,
        isOn: Binding<Bool>,
        divider: Bool = true
    ) -> some View {
        HXRow(text: hx(titleKey), subtitle: hintKey.map { hx($0) }, divider: divider) {
            Toggle(hx(titleKey), isOn: isOn)
                .labelsHidden()
                .tint(Color.hx(.brand))
        }
    }
}
