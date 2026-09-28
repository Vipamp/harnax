import SwiftUI
import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// The model form, in both modes.
///
/// The two coupled fields are the reason this form has a view model at all. `thinkingMode` is a three-value
/// column (`0` none, `1` optional, `2` required — the DDL comment is the definition, carried here by
/// `ThinkingMode`), and `supportReasoning` is its projection: reading a row falls back to the reasoning bit
/// when the mode is missing, and writing one sets the bit from the mode
/// (`ModelServiceImpl.kt:158-171`, `harnax-webui/src/pages/model/components/ModelForm.tsx:33-99`). Both
/// directions live in the contract, so the form cannot invent a third answer.
///
/// Capability switches only exist for a chat model: moving the type off `chat` zeroes all five bits, which
/// is what the console does and what the row's own `tags` then say.
@MainActor
public final class ModelFormModel: ObservableObject {
    @Published public var name: String
    @Published public var technicalName: String
    @Published public var detail: String
    @Published public var modelType: String {
        didSet { if modelType != oldValue && !isChat { zeroCapabilities() } }
    }
    /// The price as typed, kept as text because a half-typed "1." is not a number yet but is not an error
    /// worth complaining about on every keystroke.
    @Published public var priceText: String
    @Published public var thinking: ThinkingMode
    @Published public var supportsInternet = false
    @Published public var supportsTool = false
    @Published public var supportsMcp = false
    @Published public var supportsVision = false
    @Published public var isPublic: Bool {
        didSet { if isPublic != oldValue && !canNarrowVisibility { isPublic = true } }
    }
    @Published public private(set) var error: String?
    @Published public private(set) var isSaving = false

    public let editing: ModelSummary?
    public let providerID: Int64
    public let canNarrowVisibility: Bool
    private let catalog: any ModelCataloging

    public init(catalog: any ModelCataloging, providerID: Int64, editing: ModelSummary?, account: AccountSnapshot?) {
        self.catalog = catalog
        self.providerID = providerID
        self.editing = editing
        name = editing?.name ?? ""
        technicalName = editing?.modelName ?? ""
        detail = editing?.description ?? ""
        modelType = editing?.modelType ?? ModelType.defaultValue.rawValue
        priceText = ModelPresenter.priceText(editing?.price) ?? ""
        thinking = editing?.thinking ?? .off
        supportsInternet = editing?.supportsInternet ?? false
        supportsTool = editing?.supportsTool ?? false
        supportsMcp = editing?.supportsMcp ?? false
        supportsVision = editing?.supportsVision ?? false
        isPublic = editing?.isShared ?? true
        if account?.isAdministrator == true {
            canNarrowVisibility = true
        } else {
            canNarrowVisibility = editing.map { !$0.isShared } ?? true
        }
    }

    public var isEditing: Bool { editing != nil }
    public var isChat: Bool { modelType == ModelType.chat.rawValue }

    /// The picker's choices, plus a stored type the list does not name — a row created elsewhere still has
    /// to show what it is rather than be quietly rewritten.
    public var typeOptions: [String] {
        var options = ModelType.allCases.map(\.rawValue)
        if !options.contains(modelType) { options.insert(modelType, at: 0) }
        return options
    }

    public func typeLabel(_ raw: String) -> String {
        guard let known = ModelType(rawValue: raw) else { return raw }
        return hx(known.titleKey)
    }

    /// The label the row gets even when `thinking` is `off`: only `2` is worth a chip on a list, and `1` is
    /// already covered by the reasoning tag.
    public func thinkingLabel(_ mode: ThinkingMode) -> String {
        ModelPresenter.thinkingLabel(mode)
    }

    public func zeroCapabilities() {
        thinking = .off
        supportsInternet = false
        supportsTool = false
        supportsMcp = false
        supportsVision = false
    }

    public var parsedPrice: Double? {
        let text = priceText.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return 0 }
        if let direct = Double(text) { return direct }
        return Double(text.replacingOccurrences(of: ",", with: "."))
    }

    /// The local half of the create DTO's constraints: both names `@NotBlank @Size 1-100`, the price
    /// `@DecimalMin 0.0`, the description capped by the column
    /// (`ModelCreateRequest.kt:11-22,52-54`).
    public func validate() -> String? {
        guard let title = hxPresented(name) else { return hx("model.validation.name") }
        if title.count > 100 { return hx("model.validation.name.maxLength", 100) }
        guard let technical = hxPresented(technicalName) else { return hx("model.validation.technicalName") }
        if technical.count > 100 { return hx("model.validation.technicalName.maxLength", 100) }
        guard let price = parsedPrice else { return hx("model.validation.price") }
        if price < 0 { return hx("model.validation.price.negative") }
        if detail.count > 500 { return hx("model.validation.description.maxLength", 500) }
        return nil
    }

    /// The write body. `thinking` is resolved through `ThinkingMode`, so the reasoning bit that travels with
    /// it is the same projection the server applies.
    public func body() -> ModelSaveRequest {
        ModelSaveRequest(
            name: name.trimmingCharacters(in: .whitespacesAndNewlines),
            modelName: technicalName.trimmingCharacters(in: .whitespacesAndNewlines),
            providerId: providerID,
            description: detail,
            modelType: modelType,
            thinking: thinking,
            supportsInternet: supportsInternet,
            supportsTool: supportsTool,
            supportsMcp: supportsMcp,
            supportsVision: supportsVision,
            price: parsedPrice ?? 0,
            isPublic: isPublic
        )
    }

    @discardableResult
    public func submit() async -> Bool {
        if let problem = validate() {
            error = problem
            return false
        }
        error = nil
        isSaving = true
        defer { isSaving = false }
        switch await catalog.saveModel(id: editing?.id, request: body()) {
        case .success:
            return true
        case let .failure(failure):
            // Duplicate names inside one provider are the ordinary failure here, and the server's sentence
            // says which one collided (`ModelServiceImpl.kt:96-110`).
            error = ErrorMessage.text(for: failure)
            return false
        }
    }
}

/// The model form sheet, presented from the second level.
struct ModelFormSheet: View {
    @StateObject private var vm: ModelFormModel
    @Environment(\.dismiss) private var dismiss
    private let onSaved: () -> Void

    init(
        catalog: any ModelCataloging,
        providerID: Int64,
        editing: ModelSummary?,
        account: AccountSnapshot?,
        onSaved: @escaping () -> Void
    ) {
        _vm = StateObject(wrappedValue: ModelFormModel(
            catalog: catalog,
            providerID: providerID,
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
                    HXField("model.field.name", text: $vm.name, systemImage: "textformat")
                    HXField("model.field.technicalName", text: $vm.technicalName, systemImage: "cpu")
                    typePicker
                    HXField("model.field.price", text: $vm.priceText, systemImage: "number", kind: .number)
                    HXField("model.field.description", text: $vm.detail, systemImage: "text.justify")

                    HXSectionHeader("model.section.capabilities")
                    if vm.isChat {
                        capabilityRows
                    } else {
                        HXText("model.capabilities.chatOnly")
                            .font(.footnote)
                            .foregroundStyle(Color.hx(.textTertiary))
                    }

                    HXSectionHeader("model.section.sharing")
                    HXGroupCard {
                        HXRow("model.field.public", divider: false) {
                            Toggle(isOn: $vm.isPublic) {
                                EmptyView()
                            }
                            .labelsHidden()
                            .disabled(!vm.canNarrowVisibility && vm.isPublic)
                            .tint(Color.hx(.brand))
                        }
                    }
                }
                .padding(16)
            }
            .navigationTitle(hx(vm.isEditing ? "model.edit" : "model.create"))
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

    private var typePicker: some View {
        Picker(selection: $vm.modelType) {
            ForEach(vm.typeOptions, id: \.self) { option in
                Text(verbatim: vm.typeLabel(option)).tag(option)
            }
        } label: {
            HXText("model.field.modelType")
        }
        .padding(.horizontal, 13)
        .padding(.vertical, 8)
        .background(Color.hx(.surface), in: RoundedRectangle(cornerRadius: 13, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 13, style: .continuous)
                .strokeBorder(Color.hx(.separator), lineWidth: 1)
        )
    }

    /// The reasoning bit has no row of its own — it is whatever `thinking` says it is, and the picker's
    /// footnote is where that coupling is admitted to the operator.
    private var capabilityRows: some View {
        HXGroupCard {
            HXRow("model.thinkingMode", subtitle: vm.thinkingLabel(vm.thinking), divider: true) {
                Menu {
                    ForEach(ThinkingMode.allCases, id: \.rawValue) { mode in
                        Button {
                            vm.thinking = mode
                        } label: {
                            HStack {
                                HXText(mode.titleKey)
                                if vm.thinking == mode { Image(systemName: "checkmark") }
                            }
                        }
                    }
                } label: {
                    Text(verbatim: vm.thinkingLabel(vm.thinking))
                        .font(.footnote)
                        .foregroundStyle(Color.hx(.brand))
                }
            }
            switchRow("model.capability.internet", isOn: $vm.supportsInternet)
            switchRow("model.capability.tool", isOn: $vm.supportsTool)
            switchRow("model.capability.mcp", isOn: $vm.supportsMcp)
            switchRow("model.capability.vision", isOn: $vm.supportsVision, divider: false)
        }
    }

    private func switchRow(_ titleKey: String, isOn: Binding<Bool>, divider: Bool = true) -> some View {
        HXRow(titleKey, divider: divider) {
            Toggle(isOn: isOn) {
                EmptyView()
            }
            .labelsHidden()
            .tint(Color.hx(.brand))
        }
    }
}
