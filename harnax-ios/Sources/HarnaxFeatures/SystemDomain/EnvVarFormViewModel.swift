import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// S3 — create and edit one environment variable.
///
/// The field set is the two request DTOs, nothing else: `envKey`, `envValue`, `description`, `sensitive`
/// on both, plus `enabled` on create only, because `EnvVariableUpdateRequest` has no such property and the
/// switch lives on the row's own `PUT /{id}/toggle`
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/EnvVariableUpdateRequest.kt:8-24`).
///
/// One rule outranks every other decision here: **what the list showed is not always what is stored**. A
/// sensitive row's value column is a mask computed from the plaintext
/// (`EnvVariableServiceImpl.kt:221-243`, `:274-278`), so re-posting it would either be swallowed by the
/// server's `isUnchangedMask` check or — on a row that has since been switched to non-sensitive, where that
/// check never runs — store the asterisks as the value (`:137-161`, `:288-291`). `submittedValue` is the
/// one place that decides what goes on the wire.
@MainActor
public final class EnvVarFormViewModel: ObservableObject {
    /// The `@Size` ceilings of the two request DTOs.
    public static let keyLimit = EnvVarKeyPattern.lengthLimit
    public static let valueLimit = 8192
    public static let noteLimit = 500

    @Published public var key: String
    @Published public var value: String
    @Published public var note: String
    @Published public var sensitive: Bool
    /// The create form's own switch; while editing it is not even shown, because the update DTO has no
    /// field for it.
    @Published public var enabled: Bool

    @Published public private(set) var isSaving = false
    @Published public private(set) var errorText: String?
    @Published public private(set) var saved = false

    private let row: EnvVarSummary?
    private let catalog: any EnvVarCataloging

    public init(row: EnvVarSummary?, catalog: any EnvVarCataloging) {
        self.row = row
        self.catalog = catalog
        key = row?.envKey ?? ""
        // A sensitive row starts empty: this field holds a new value or nothing, never the mask the list
        // showed. `submittedValue` still catches the case where someone types that mask back in.
        value = (row?.isSensitive ?? false) ? "" : (row?.envValue ?? "")
        note = row?.description ?? ""
        sensitive = row?.isSensitive ?? false
        enabled = row?.isEnabled ?? true
    }

    public var isEditing: Bool { row != nil }

    /// The mask this row was read with, printed next to the value field so the user can see that something
    /// is stored here without this app ever holding it.
    public var maskedDisplay: String? {
        guard let row, row.isSensitive else { return nil }
        return row.displayValue
    }

    /// Whether the value field is empty because the row is sensitive — the case that needs explaining.
    public var isValueBlankBecauseMasked: Bool { maskedDisplay != nil && value.isEmpty }

    // MARK: - what goes on the wire

    /// The value to post, or `nil` for “keep what is stored”.
    ///
    /// While editing, blank means unchanged, and so does text identical to what the row arrived with — the
    /// mask on a sensitive row, the stored text on any other. That is the iOS half of `isUnchangedMask`,
    /// applied whether or not the server would notice.
    public var submittedValue: String? {
        if !value.isEmpty, value != row?.envValue { return value }
        return row == nil ? value : nil
    }

    /// The rename, or `nil` when the key column is untouched. A rename is the only way a key edit can
    /// collide with another row (`EnvVariableServiceImpl.kt:127-135`).
    public var submittedKey: String? {
        guard let row else { return trimmedKey.isEmpty ? nil : trimmedKey }
        return trimmedKey == (row.envKey ?? "") ? nil : trimmedKey
    }

    public var submittedNote: String? {
        guard let row else { return note.isEmpty ? nil : note }
        return note == (row.description ?? "") ? nil : note
    }

    /// The flag, and only when it actually moved — a lone flip makes the server re-encode the stored text
    /// in the other form (`EnvVariableServiceImpl.kt:156-161`, `:326-330`).
    public var submittedSensitive: Int? {
        guard let row else { return sensitive.hxInt }
        return sensitive == row.isSensitive ? nil : sensitive.hxInt
    }

    public var draft: EnvVarDraft? {
        guard !isEditing, validationErrorKey == nil else { return nil }
        return EnvVarDraft(
            envKey: trimmedKey,
            envValue: value,
            description: note.isEmpty ? nil : note,
            sensitive: sensitive.hxInt,
            enabled: enabled.hxInt
        )
    }

    /// The whole edit body. Its `nil` fields are left off the JSON, so an untouched form posts `{}` and
    /// changes nothing at all.
    public var change: EnvVarChange? {
        guard isEditing, validationErrorKey == nil else { return nil }
        return EnvVarChange(
            envKey: submittedKey,
            envValue: submittedValue,
            description: submittedNote,
            sensitive: submittedSensitive
        )
    }

    /// Whether an edit has anything to send. A form that changed nothing still saves — the server reads an
    /// empty body as “keep everything” and answers success — but the screen says so rather than pretending
    /// to have written something.
    public var hasChanges: Bool {
        guard isEditing else { return true }
        return submittedKey != nil || submittedValue != nil || submittedNote != nil || submittedSensitive != nil
    }

    // MARK: - validation

    private var trimmedKey: String { key.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// The first reason this form cannot be posted, as a copy key; `nil` means it is ready.
    ///
    /// The key pattern is enforced on both paths: create because `@NotBlank @Pattern` says so, edit because
    /// the update DTO carries the same `@Pattern` even though the console's rename form never checks it
    /// (`harnax-webui/src/pages/env-variable/components/UpdateForm.tsx:68-71`). Skipping it there would
    /// just move the refusal to the server.
    public var validationErrorKey: String? {
        if isEditing {
            if submittedKey != nil {
                if trimmedKey.count > Self.keyLimit { return "env.var.key.long" }
                if !EnvVarKeyPattern.isValid(trimmedKey) { return "env.var.key.invalid" }
            }
        } else {
            if trimmedKey.isEmpty { return "env.var.key.required" }
            if trimmedKey.count > Self.keyLimit { return "env.var.key.long" }
            if !EnvVarKeyPattern.isValid(trimmedKey) { return "env.var.key.invalid" }
            if value.isEmpty { return "env.var.value.required" }
        }
        if !value.isEmpty, value.count > Self.valueLimit { return "env.var.value.long" }
        if note.count > Self.noteLimit { return "env.var.note.long" }
        return nil
    }

    public var canSubmit: Bool { validationErrorKey == nil && !isSaving }

    // MARK: - save

    /// True once the stack has taken the write, which is what the sheet watches in order to dismiss itself.
    public func save() async {
        guard !isSaving else { return }
        if let reason = validationErrorKey {
            saved = false
            errorText = hx(reason)
            return
        }
        isSaving = true
        errorText = nil
        let result: Result<EmptyResponse, APIError>
        if let row, let id = row.id, let change {
            result = await catalog.updateEnvVar(id: id, change)
        } else if row != nil {
            // A row the wire gave no id for cannot be written: the id is this route's only address.
            isSaving = false
            saved = false
            errorText = ErrorMessage.text(for: .unpackable)
            return
        } else if let draft {
            result = await catalog.createEnvVar(draft)
        } else {
            isSaving = false
            saved = false
            errorText = ErrorMessage.text(for: .decoding)
            return
        }
        isSaving = false
        switch result {
        case .success:
            saved = true
            errorText = nil
        case let .failure(error):
            // The server's own sentence wins: a key clash names the key that clashed
            // (`EnvVariableServiceImpl.kt:108-117`).
            saved = false
            errorText = ErrorMessage.text(for: error)
        }
    }
}
