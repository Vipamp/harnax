import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// S4 — create and edit one API Key.
///
/// The field set is `ApiKeyCreateRequest` and `ApiKeyUpdateRequest`
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ApiKeyCreateRequest.kt:8-26`,
/// `ApiKeyUpdateRequest.kt:6-21`), and the two are not the same shape: there is no `name` on the update
/// DTO, so the console disables that field (`harnax-webui/src/pages/api-key/components/UpdateForm.tsx:83`)
/// and so does this form. `enabled`, on the other hand, *is* on the update DTO, which is why this screen
/// carries a switch where the environment-variable form does not.
///
/// Scopes are the two labels the console offers and nothing more
/// (`harnax-webui/src/pages/api-key/components/CreateForm.tsx:13-16`); they carry no permission
/// (`harnax-auth/src/main/kotlin/com/agnetix/harnax/auth/AuthContext.kt:15-16`), so this form never reads
/// them back to decide what the user may do.
@MainActor
public final class ApiKeyFormViewModel: ObservableObject {
    /// `@Size(128)` on the name, and the console's own ceiling on the rate-limit input (`1...10000`).
    public static let nameLimit = 128
    public static let rateLimitFloor = 1
    public static let rateLimitCeiling = 10_000
    public static let defaultRateLimit = 60

    @Published public var name: String
    @Published public var selectedScopes: Set<ApiKeyScope>
    @Published public var rateLimitText: String
    @Published public var tenantText: String
    @Published public var enabled: Bool
    @Published public var hasExpiry: Bool
    @Published public var expiryDate: Date

    @Published public private(set) var isSaving = false
    @Published public private(set) var errorText: String?
    @Published public private(set) var saved = false
    /// Non-nil only after a create the stack accepted. Nothing on the read path can fill it, and an edit
    /// leaves it `nil`, because `PUT /update/{id}` answers `ResultVo<Void>` — there is no second copy of
    /// the key to hand out.
    @Published public private(set) var published: ApiKeyCreatedSummary?

    private let row: ApiKeySummary?
    private let catalog: any ApiKeyCataloging
    /// Tokens the two switches cannot represent, kept out of `selectedScopes` and re-joined on write, so
    /// editing a row whose scope string came from elsewhere cannot quietly drop one.
    private let remainder: [String]

    /// The tenant column is only editable by an administrator: the service ignores what anyone else sends
    /// on create (`ApiKeyServiceImpl.kt:72-78`) and refuses the change outright on update (`:122-127`).
    public let showsTenantField: Bool

    public init(row: ApiKeySummary?, account: AccountSnapshot?, catalog: any ApiKeyCataloging) {
        self.row = row
        self.catalog = catalog
        showsTenantField = account?.isAdministrator ?? false
        name = row?.name ?? ""
        let split = row?.scopeSelection ?? ApiKeyScopeSplit(scopes: nil)
        selectedScopes = split.known
        remainder = split.remainder
        rateLimitText = row.map { $0.rateLimit.map(String.init) ?? "" } ?? String(Self.defaultRateLimit)
        tenantText = row?.tenantId.map(String.init) ?? ""
        enabled = row?.isEnabled ?? true
        hasExpiry = row?.expiryDate != nil
        expiryDate = row?.expiryDate ?? Date()
    }

    public var isEditing: Bool { row != nil }

    /// The scopes string that goes on the wire: the known labels in canonical order, then whatever the row
    /// already carried that this form has no switch for.
    public var submittedScopes: String {
        (ApiKeyScope.allCases.filter { selectedScopes.contains($0) }.map(\.rawValue) + remainder)
            .joined(separator: ",")
    }

    // MARK: - what goes on the wire

    public var submittedRateLimit: Int? {
        guard let value = Int(rateLimitText.trimmed) else { return nil }
        guard isEditing else { return value }
        return value == row?.rateLimit ? nil : value
    }

    public var submittedTenantId: Int64? {
        guard let value = Int64(tenantText.trimmed), !tenantText.trimmed.isEmpty else { return nil }
        guard isEditing else { return value }
        return value == row?.tenantId ? nil : value
    }

    /// `nil` leaves the column alone, `""` clears it — the server branches on `isBlank()`
    /// (`ApiKeyServiceImpl.kt:130-132`), and that is the only route back to “never expires”.
    public var submittedExpiry: String? {
        let stamp = hxServerDateTimeString(expiryDate)
        if hasExpiry {
            guard isEditing, let stored = row?.expiryDate else { return stamp }
            return stamp == hxServerDateTimeString(stored) ? nil : stamp
        }
        guard isEditing else { return nil }
        return row?.expiryDate == nil ? nil : ""
    }

    public var submittedEnabled: Int? {
        guard isEditing else { return nil }
        return enabled == (row?.isEnabled ?? true) ? nil : enabled.hxInt
    }

    public var draft: ApiKeyDraft? {
        guard !isEditing, validationErrorKey == nil else { return nil }
        return ApiKeyDraft(
            name: name.trimmed,
            scopes: submittedScopes,
            tenantId: submittedTenantId,
            rateLimit: submittedRateLimit,
            expiresAt: submittedExpiry
        )
    }

    public var change: ApiKeyChange? {
        guard isEditing, validationErrorKey == nil else { return nil }
        return ApiKeyChange(
            scopes: selectedScopes == row?.scopeSelection.known ? nil : submittedScopes,
            tenantId: submittedTenantId,
            rateLimit: submittedRateLimit,
            enabled: submittedEnabled,
            expiresAt: submittedExpiry
        )
    }

    // MARK: - validation

    /// The first reason this form cannot be posted, as a copy key; `nil` means it is ready.
    ///
    /// At least one scope is required because the DTO marks the field `@NotBlank`
    /// (`ApiKeyCreateRequest.kt:14-16`) — not because the value gates anything. A row that came in carrying
    /// only foreign tokens still satisfies it, through `submittedScopes`.
    public var validationErrorKey: String? {
        if !isEditing {
            if name.trimmed.isEmpty { return "apikey.name.required" }
            if name.trimmed.count > Self.nameLimit { return "apikey.name.long" }
        }
        if submittedScopes.isEmpty { return "apikey.scopes.required" }
        if let reason = numberReason { return reason }
        return nil
    }

    private var numberReason: String? {
        let text = rateLimitText.trimmed
        if !text.isEmpty {
            guard let value = Int(text) else { return "apikey.rate.invalid" }
            if value < Self.rateLimitFloor || value > Self.rateLimitCeiling { return "apikey.rate.range" }
        }
        let tenant = tenantText.trimmed
        if !tenant.isEmpty {
            guard let value = Int64(tenant), value >= 1 else { return "apikey.tenant.invalid" }
        }
        return nil
    }

    public var canSubmit: Bool { validationErrorKey == nil && !isSaving }

    // MARK: - save

    public func save() async {
        guard !isSaving else { return }
        if let reason = validationErrorKey {
            saved = false
            errorText = hx(reason)
            return
        }
        isSaving = true
        errorText = nil
        if let row {
            guard let id = row.id else {
                // A row the wire gave no id for cannot be written: the id is this route's only address, and
                // falling through to create would mint a second key.
                isSaving = false
                saved = false
                errorText = ErrorMessage.text(for: .unpackable)
                return
            }
            let body = change ?? ApiKeyChange()
            switch await catalog.updateApiKey(id: id, body) {
            case .success:
                // No key comes back from this route, so nothing is published and the sheet simply closes.
                finish(created: nil, error: nil)
            case let .failure(error):
                finish(created: nil, error: error)
            }
            return
        }
        let body = draft ?? ApiKeyDraft(name: name.trimmed, scopes: submittedScopes)
        switch await catalog.createApiKey(body) {
        case let .success(created):
            finish(created: created, error: nil)
        case let .failure(error):
            finish(created: nil, error: error)
        }
    }

    /// A duplicated name is the ordinary failure on create, and the server's sentence names the key it
    /// clashed with (`ApiKeyServiceImpl.kt:65-67`), so it is shown as it arrived.
    private func finish(created: ApiKeyCreatedSummary?, error: APIError?) {
        // After the reply, not before: clearing it earlier reopens save()'s re-entrancy guard mid-flight,
        // and on create the response is the only place the raw key ever appears.
        isSaving = false
        if let error {
            saved = false
            published = nil
            errorText = ErrorMessage.text(for: error)
            return
        }
        saved = true
        published = created
        errorText = nil
    }
}

private extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}
