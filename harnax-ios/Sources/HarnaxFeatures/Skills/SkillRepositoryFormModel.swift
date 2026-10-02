import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// SKILL-1 and SKILL-2 — the state of the repository form: create a source, or edit one that already exists.
///
/// The type decides the fields, and not only which rows are drawn: it moves the *required* mark too
/// (`harnax-webui/src/pages/skill/components/RepositoryForm.tsx:213-256`). A GIT source needs an address and
/// may leave the branch to `main`; an NPM source needs a package name and may leave the registry empty. So
/// the two never share a field bag — `credentialFields` hands out one pair or the other, and switching the
/// type on a form that was already complete makes it incomplete again.
///
/// `sourceConfig` is the only carrier of that pair on the wire. The legacy `url` / `branch` keys exist on both
/// DTOs and are left off anyway: the console drops them from an update because under NPM they would be empty
/// strings asking to overwrite a real value (`RepositoryForm.tsx:104-107`), and on a create the service
/// rebuilds them from the normalised config itself (`SkillSourceServiceImpl.kt:144-148`), so a caller that
/// sends both can only send two answers to one question.
///
/// What stays on the server is deliberately not guessed at here. The loaders check the shape of what this form
/// fills in — a Git transport prefix and a 500-character ceiling (`GitSkillLoader.kt:113-130`), an npm-compliant
/// package name and an http(s) registry (`NpmSkillLoader.kt:121-138`) — and their sentences are better than
/// anything this file could repeat, so an out-of-shape value goes out and comes back as theirs. The two bounds
/// the *DTO* carries are checked here, because those are the request's own contract: the name at 100 and the
/// version at 100 (`SkillSourceCreateRequest.kt:12,24`, `SkillSourceUpdateRequest.kt:11,20`).
@MainActor
public final class SkillRepositoryFormModel: ObservableObject, Identifiable {
    /// Create, or edit the row as it arrived. The row is the seed and the address at once: its `id` is what
    /// the PUT goes to, and its stored text is what the fields start with (`RepositoryForm.tsx:29-41`).
    public enum Mode: Equatable, Sendable {
        case create
        case edit(SkillSourceSummary)

        public var isCreate: Bool {
            if case .create = self { return true }
            return false
        }

        public var row: SkillSourceSummary? {
            if case let .edit(source) = self { return source }
            return nil
        }
    }

    /// What the write answered. A create installs on the way in, so it owes the operator the graded report
    /// (`SkillSourceInstallResponse.kt:12-17`); an edit stores configuration and pulls nothing, which is why
    /// the console warns instead of congratulating (`RepositoryForm.tsx:115-124`).
    public enum Outcome: Equatable, Sendable {
        case created(SkillInstallReport)
        case updated
    }

    /// The addressable fields of one form. `url` / `branch` and `packageName` / `registry` are separate cases
    /// rather than two slots of a generic pair, because their required marks and their config keys differ.
    public enum Field: String, CaseIterable, Equatable, Sendable {
        case name
        case url
        case branch
        case packageName
        case registry
        case version
        case description

        var titleKey: String {
            switch self {
            case .name: "skill.repository.name"
            case .url: "skill.repository.url"
            case .branch: "skill.repository.branch"
            case .packageName: "skill.repository.package"
            case .registry: "skill.repository.registry"
            case .version: "skill.repository.version"
            case .description: "skill.repository.description"
            }
        }

        /// The console's own placeholders, including the three literal examples it prints
        /// (`RepositoryForm.tsx:221,230,244,252,284`) — an example of the expected value is copy either way.
        var placeholderKey: String {
            switch self {
            case .name: "skill.repository.name.placeholder"
            case .url: "skill.repository.url.placeholder"
            case .branch: "skill.repository.branch.placeholder"
            case .packageName: "skill.repository.package.placeholder"
            case .registry: "skill.repository.registry.placeholder"
            case .version: "skill.repository.version.placeholder"
            case .description: "skill.repository.description.placeholder"
            }
        }

        var systemImage: String {
            switch self {
            case .name: "textformat"
            case .url: "link"
            case .branch: "arrow.triangle.branch"
            case .packageName: "shippingbox"
            case .registry: "square.stack.3d.up"
            case .version: "number"
            case .description: "note.text"
            }
        }
    }

    /// The types this route may name. The console's `Select` also lists ZIP (`RepositoryForm.tsx:207-209`),
    /// but picking it there switches the submit to the upload endpoint
    /// (`RepositoryForm.tsx:71-83`) — and this route refuses ZIP outright, telling the caller to upload
    /// instead (`SkillSourceServiceImpl.kt:119-123`). iOS already has the upload as its own entry, so the
    /// form does not offer a type it cannot write.
    public static let typeOptions: [SkillSourceType] = [.git, .npm]

    /// `SkillSourceCreateRequest.kt:12` / `SkillSourceUpdateRequest.kt:11`.
    static let nameLimit = 100
    /// `SkillSourceCreateRequest.kt:24` / `SkillSourceUpdateRequest.kt:20` — the same ceiling as the column,
    /// because the version is copied onto every skill the source installs.
    static let versionLimit = 100

    public let id = UUID()
    public let mode: Mode

    @Published public private(set) var values: [Field: String]
    @Published public private(set) var typeChoice: SkillSourceType
    /// The `status` switch: 1 is enabled, and a create opens enabled (`RepositoryForm.tsx:44-49,297-309`).
    @Published public private(set) var enabled = true
    /// The `isPublic` switch, sent as the 0/1 the column holds (`RepositoryForm.tsx:99`).
    @Published public private(set) var shared = false
    /// The fields a refused submit marked red, which is how the console reports a form that cannot go out
    /// (`RepositoryForm.tsx:61-67` — `validateFields` rejects, and nothing is requested).
    @Published public private(set) var invalid: Set<Field> = []
    @Published public private(set) var isSaving = false
    @Published public private(set) var saved = false
    @Published public private(set) var errorText: String?

    private let catalog: any SkillCataloging
    private let account: AccountSnapshot?

    public init(mode: Mode, catalog: any SkillCataloging, account: AccountSnapshot? = nil) {
        self.mode = mode
        self.catalog = catalog
        self.account = account

        let row = mode.row
        typeChoice = row?.type ?? .git
        // The row's own text for one setting: `sourceConfig` is the live carrier and the plain column only the
        // legacy fallback, exactly as `SkillSourceSummary.endpoint` reads it
        // (`SkillSourceConfigs.kt:22-35`).
        func stored(config: String?, column: String?) -> String? {
            hxPresented(config) ?? hxPresented(column)
        }

        func storedText(config: String?, column: String?) -> String {
            stored(config: config, column: column) ?? ""
        }

        // `values.branch || 'main'` (`RepositoryForm.tsx:37`): a row with no branch is edited as `main`, which
        // is also what the server stores when the config leaves it out (`SkillSourceServiceImpl.kt:147`).
        let storedBranch = row.flatMap { stored(config: $0.sourceConfig?.branch, column: $0.branch) }
        values = [
            .name: row?.name ?? "",
            .url: row.map { storedText(config: $0.sourceConfig?.url, column: $0.url) } ?? "",
            .branch: storedBranch ?? "main",
            // The NPM pair only ever lives in the config — the legacy columns hold nothing for it
            // (`RepositoryForm.tsx:39-40`).
            .packageName: row.map { storedText(config: $0.sourceConfig?.packageName, column: nil) } ?? "",
            .registry: row.map { storedText(config: $0.sourceConfig?.registry, column: nil) } ?? "",
            .version: row?.version ?? "",
            .description: row?.description ?? "",
        ]
        enabled = row.map { $0.isEnabled } ?? true
        shared = row.map { $0.isShared } ?? false
    }

    public var isCreate: Bool { mode.isCreate }

    /// The pair this type owns, in the console's order. Empty for a ZIP row: its archive is not editable
    /// here, only the name, version, description and the two switches (`SkillSourceServiceImpl.kt:236-241`).
    public var credentialFields: [Field] {
        switch typeChoice {
        case .git: [.url, .branch]
        case .npm: [.packageName, .registry]
        case .zip, .builtin, .unknown: []
        }
    }

    /// Every field the form draws as text, in order. The two switches and the type sit outside it because
    /// they are not text.
    public var textFields: [Field] {
        [Field.name] + credentialFields + [.version, .description]
    }

    /// `RepositoryForm.tsx:191,218,241` — the three `required: true` rules, and nothing else. `branch` and
    /// `registry` are optional by name, and version and description are never required.
    public func isRequired(_ field: Field) -> Bool {
        switch field {
        case .name: true
        case .url: typeChoice == .git
        case .packageName: typeChoice == .npm
        case .branch, .registry, .version, .description: false
        }
    }

    public func text(for field: Field) -> String { values[field] ?? "" }

    /// The text as it will go out. Trimming is not cosmetic here: the name participates in a unique index and
    /// is compared against the stored value to decide whether a rename happened at all
    /// (`SkillSourcePolicy.requireUsableText` → `SkillSourceConfigs.normalized:77-79`).
    public func trimmedText(for field: Field) -> String {
        text(for: field).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    public func setValue(_ text: String, for field: Field) {
        values[field] = text
        invalid.remove(field)
        errorText = nil
    }

    /// A create may choose its type; an edit cannot, because the update DTO has no field to carry a switch and
    /// the console disables the `Select` on exactly that reason (`RepositoryForm.tsx:205`).
    public func setType(_ type: SkillSourceType) {
        guard isCreate, Self.typeOptions.contains(type) else { return }
        typeChoice = type
    }

    public func setEnabled(_ enabled: Bool) {
        self.enabled = enabled
    }

    /// Ignored while the switch is locked, so a locked control cannot change what goes out even if the view
    /// forgets to disable it.
    public func setIsPublic(_ shared: Bool) {
        guard canChangeVisibility else { return }
        self.shared = shared
    }

    /// The required fields this type asks for and the form does not have.
    public var missingRequired: [Field] { textFields.filter { isRequired($0) && trimmedText(for: $0).isEmpty } }

    /// Text over the bound the DTO declares.
    public var tooLong: Set<Field> {
        var out: Set<Field> = []
        if trimmedText(for: .name).count > Self.nameLimit { out.insert(.name) }
        if trimmedText(for: .version).count > Self.versionLimit { out.insert(.version) }
        return out
    }

    public var canSubmit: Bool {
        !isSaving && !saved && missingRequired.isEmpty && tooLong.isEmpty
    }

    /// The sentence to show under a field the operator still owes, the console's red rule translated into a
    /// line of helper text. `nil` while the field is satisfied.
    public func problem(for field: Field) -> String? {
        guard invalid.contains(field) else { return nil }
        if tooLong.contains(field) {
            return hx(field == .name ? "skill.repository.nameTooLong" : "skill.repository.versionTooLong")
        }
        switch field {
        case .name: return hx("skill.repository.needName")
        case .url: return hx("skill.repository.needUrl")
        case .packageName: return hx("skill.repository.needPackage")
        default: return nil
        }
    }

    /// The config map for the chosen type, or nothing for a type whose configuration this form cannot own.
    /// GIT fills `branch` with `main` rather than leaving the key out (`RepositoryForm.tsx:87`), and NPM keeps
    /// an empty `registry` as a present key (`:89`).
    public var sourceConfig: [String: String]? {
        switch typeChoice {
        case .git:
            let branch = trimmedText(for: .branch)
            return ["url": trimmedText(for: .url), "branch": branch.isEmpty ? "main" : branch]
        case .npm:
            return ["packageName": trimmedText(for: .packageName), "registry": trimmedText(for: .registry)]
        case .zip, .builtin, .unknown:
            return nil
        }
    }

    /// A ZIP row keeps the name of the archive it was built from, which is the one thing about its source it
    /// can still show (`RepositoryList.tsx:422-426`).
    public var showsStoredArchive: Bool { mode.row?.type == .zip }

    public var storedArchive: String? { hxPresented(mode.row?.sourceConfig?.originalFilename) }

    /// `isPublicSwitchDisabled` (`harnax-webui/src/utils/permissionUtil.ts:57-75`): an administrator and any
    /// create always may, anyone else may only pull their own private row out to public.
    public var canChangeVisibility: Bool {
        account?.canChangeVisibility(
            creator: mode.row?.creator,
            currentlyPublic: mode.row?.isShared ?? false,
            isCreate: mode.isCreate
        ) ?? mode.isCreate
    }

    /// The body of `POST /api/admin/skill-sources` (`SkillSourceCreateRequest.kt:7-41`). `url` / `branch` stay
    /// unset — see the type's note on why the config is the only carrier.
    public func createPayload() -> SkillSourceCreatePayload {
        SkillSourceCreatePayload(
            name: trimmedText(for: .name),
            sourceType: typeChoice.rawValue,
            sourceConfig: sourceConfig ?? [:],
            // The console sends both as empty strings rather than omitting them
            // (`RepositoryForm.tsx:96-97`), and the service stores `version ?: ""` either way
            // (`SkillSourceServiceImpl.kt:140`).
            version: trimmedText(for: .version),
            description: trimmedText(for: .description),
            status: enabled.hxInt,
            isPublic: shared.hxInt
        )
    }

    /// The body of `PUT /api/admin/skill-sources/{id}` (`SkillSourceUpdateRequest.kt:6-37`): five fields plus
    /// the config, no `sourceType` (the DTO has none) and no legacy `url` / `branch`.
    public func updatePayload() -> SkillSourceUpdatePayload {
        SkillSourceUpdatePayload(
            name: trimmedText(for: .name),
            sourceConfig: sourceConfig,
            version: trimmedText(for: .version),
            description: trimmedText(for: .description),
            status: enabled.hxInt,
            isPublic: shared.hxInt
        )
    }

    /// Runs the write this mode says it is, and answers `nil` when nothing went out — a form with an unmet
    /// requirement, a save already on the wire, or a refusal. `invalid` is what the first of those leaves
    /// behind for the sheet to show, and `errorText` what the last one does.
    public func save() async -> Outcome? {
        guard !isSaving, !saved else { return nil }
        let problems = Set(missingRequired).union(tooLong)
        invalid = problems
        guard problems.isEmpty else { return nil }

        isSaving = true
        errorText = nil
        defer { isSaving = false }

        switch mode {
        case .create:
            switch await catalog.createSource(createPayload()) {
            case let .success(result):
                saved = true
                return .created(SkillInstallReport.describe(result.install))
            case let .failure(error):
                errorText = ErrorMessage.text(for: error)
                return nil
            }
        case let .edit(row):
            switch await catalog.updateSource(id: row.id, updatePayload()) {
            case .success:
                saved = true
                return .updated
            case let .failure(error):
                errorText = ErrorMessage.text(for: error)
                return nil
            }
        }
    }
}
