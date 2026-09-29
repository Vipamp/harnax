import SwiftUI
import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// The provider form, in both modes.
///
/// Three rules the backend imposes and the form has to honour rather than rediscover:
/// - `type` is only writable on create; the update DTO accepts it but the console locks it
///   (`harnax-webui/src/pages/model/components/ProviderForm.tsx:151-164`), so the picker is disabled while
///   editing and the key is left off the body;
/// - an API key never comes back whole — the response is masked — so the field starts empty and empty means
///   "keep what is stored" (`ModelProviderServiceImpl.kt:108-112`);
/// - neither DTO carries a status. A created provider is enabled
///   (`ModelProviderServiceImpl.kt:78`), and turning it over is the card's switch, not the form's.
@MainActor
public final class ModelProviderFormModel: ObservableObject {
    @Published public var typeChoice: ModelProviderType
    @Published public var name: String
    @Published public var detail: String
    /// The secret. Blank means "leave the stored one alone".
    @Published public var secret: String
    @Published public var address: String
    @Published public var isPublic: Bool
    @Published public private(set) var error: String?
    @Published public private(set) var isSaving = false

    public let editing: ModelProviderSummary?
    /// A member may publish their own private provider, may not take a published one back to private and may
    /// not touch a row somebody else created (`permissionUtil.ts:29-75`, one rule for both model forms).
    public let canChangeVisibility: Bool
    private let catalog: any ModelCataloging

    public init(catalog: any ModelCataloging, editing: ModelProviderSummary?, account: AccountSnapshot?) {
        self.catalog = catalog
        self.editing = editing
        self.typeChoice = ModelProviderType.resolve(editing?.type) ?? .dashscope
        self.name = editing?.name ?? ""
        self.detail = editing?.description ?? ""
        self.secret = ""
        self.address = editing?.baseUrl ?? ""
        self.isPublic = editing?.isShared ?? true
        canChangeVisibility = account?.canChangeVisibility(
            creator: editing?.creator,
            currentlyPublic: editing?.isShared ?? false,
            isCreate: editing == nil
        ) ?? true
    }

    public var isEditing: Bool { editing != nil }
    public var hasStoredCredential: Bool { editing?.hasCredential ?? false }
    public var maskedCredential: String? { editing?.maskedCredential }

    /// The local gate. Everything here is also enforced server-side; the point is that a tap on save does
    /// not spend a round trip on a name the create DTO would refuse
    /// (`ModelProviderCreateRequest.kt:17-19,22,26-34`).
    public func validate() -> String? {
        guard let title = hxPresented(name) else { return hx("model.validation.name") }
        if title.count > 100 { return hx("model.validation.name.maxLength", 100) }
        if detail.count > 500 { return hx("model.validation.description.maxLength", 500) }
        if secret.count > 500 { return hx("model.validation.apiKey.maxLength", 500) }
        if let url = hxPresented(address) {
            if url.count > 500 { return hx("model.validation.url.maxLength", 500) }
            if !Self.isPlausibleBaseURL(url) { return hx("model.validation.url") }
        }
        return nil
    }

    /// The create pattern, which the update DTO shares except that it refuses an empty string
    /// (`ModelProviderCreateRequest.kt:30-34` against `ModelProviderUpdateRequest.kt:29-32`). iOS never
    /// sends an empty one: `body()` drops a blank address, which on update means "unchanged".
    static func isPlausibleBaseURL(_ text: String) -> Bool {
        let pattern = #"^(https?://)?([\w.\-]+)(:\d+)?(/[^\s]*)?$"#
        guard let expression = try? NSRegularExpression(pattern: pattern) else { return true }
        let span = NSRange(text.startIndex..., in: text)
        return expression.firstMatch(in: text, range: span) != nil
    }

    public func body() -> ModelProviderSaveRequest {
        ModelProviderSaveRequest(
            type: isEditing ? nil : typeChoice.rawValue,
            name: name.trimmingCharacters(in: .whitespacesAndNewlines),
            description: detail,
            apiKey: hxPresented(secret),
            baseUrl: hxPresented(address),
            isPublic: isPublic
        )
    }

    /// `true` once the stack accepted the write. A refusal stays on the form rather than dismissing the
    /// sheet, because the server's sentence — "a provider with this name already exists" — is about the text
    /// still in the field (`ModelProviderServiceImpl.kt:63-66,96-101`).
    @discardableResult
    public func submit() async -> Bool {
        guard !isSaving else { return false }
        if let problem = validate() {
            error = problem
            return false
        }
        error = nil
        isSaving = true
        defer { isSaving = false }
        switch await catalog.saveProvider(id: editing?.providerID, request: body()) {
        case .success:
            return true
        case let .failure(failure):
            error = ErrorMessage.text(for: failure)
            return false
        }
    }
}

/// The provider form sheet, presented over either level of the screen.
struct ModelProviderFormSheet: View {
    @StateObject private var vm: ModelProviderFormModel
    @Environment(\.dismiss) private var dismiss
    private let onSaved: () -> Void

    init(
        catalog: any ModelCataloging,
        editing: ModelProviderSummary?,
        account: AccountSnapshot?,
        onSaved: @escaping () -> Void
    ) {
        _vm = StateObject(wrappedValue: ModelProviderFormModel(
            catalog: catalog,
            editing: editing,
            account: account
        ))
        self.onSaved = onSaved
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if let error = vm.error {
                        HXBanner(
                            "state.error.title",
                            message: error,
                            systemImage: "exclamationmark.triangle",
                            tone: .danger
                        )
                    }
                    HXSectionHeader("model.section.identity")
                    typePicker
                    HXField("model.field.providerName", text: $vm.name, systemImage: "textformat")
                    HXField("model.field.baseUrl", text: $vm.address, systemImage: "link.badge.plus", kind: .URL)

                    HXSectionHeader("model.section.credential")
                    HXField("model.field.apiKey", text: $vm.secret, systemImage: "key", secure: true)
                    if vm.hasStoredCredential, let masked = vm.maskedCredential {
                        HXText("model.field.apiKeyKept", masked)
                            .font(.caption)
                            .foregroundStyle(Color.hx(.textTertiary))
                    }

                    HXSectionHeader("model.section.sharing")
                    HXField("model.field.description", text: $vm.detail, systemImage: "text.justify")
                    HXGroupCard {
                        HXRow("model.field.public", divider: false) {
                            Toggle(isOn: $vm.isPublic) {
                                EmptyView()
                            }
                            .labelsHidden()
                            .disabled(!vm.canChangeVisibility)
                            .tint(Color.hx(.brand))
                        }
                    }
                }
                .padding(16)
            }
            .navigationTitle(hx(vm.isEditing ? "model.provider.edit" : "model.provider.create"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { HXText("common.cancel") }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        Task {
                            if await vm.submit() {
                                onSaved()
                                dismiss()
                            }
                        }
                    } label: {
                        HXText("common.save")
                    }
                    .disabled(vm.isSaving)
                }
            }
        }
    }

    /// The type is a closed list, so it is picked rather than typed: the create DTO pins it to
    /// `^[a-z0-9_]+$`.
    private var typePicker: some View {
        Picker(selection: $vm.typeChoice) {
            ForEach(ModelProviderType.allCases) { choice in
                HXText(choice.titleKey).tag(choice)
            }
        } label: {
            HXText("model.field.providerType")
        }
        .disabled(vm.isEditing)
        .padding(.horizontal, 13)
        .padding(.vertical, 8)
        .background(Color.hx(.surface), in: RoundedRectangle(cornerRadius: 13, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 13, style: .continuous)
                .strokeBorder(Color.hx(.separator), lineWidth: 1)
        )
    }
}
