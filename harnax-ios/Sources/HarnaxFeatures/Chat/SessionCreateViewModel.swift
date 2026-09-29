import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// Which half of the create form's duplicate check the title field is in.
///
/// The route behind it answers a bare `ResultVo<Boolean>` (`SessionController.kt:68-78`), so the only states
/// worth naming are "asked and it is free", "asked and it is not", and "asking failed". The last one is not
/// the same as a refusal: the check cannot clear a name, and per `SessionCreating`'s own rule a check that
/// failed blocks the submit rather than guessing that the name is free.
public enum SessionTitleVerdict: Equatable, Sendable {
    case unchecked
    case checking
    case available
    case taken
    case checkFailed(String)
}

/// The console's `SettingsModal` (`harnax-webui/src/pages/session/components/SettingsModal.tsx`) — the sheet
/// that creates one conversation.
///
/// Three things about this form are server facts rather than design choices, and each decides behaviour here:
///
/// - **the duplicate check is global.** Its SQL is `WHERE title = ? AND active = 1` with no tenant and no
///   creator condition (`harnax-entity/src/main/resources/mapper/SessionMapper.xml:44-46`), so a hit can be
///   somebody else's conversation in another tenant that this account will never see in its own list. The copy
///   therefore says the name is taken and never claims the user already owns it.
/// - **the reply to a create carries no id.** `POST /api/admin/sessions` answers `ResultVo<Void>`
///   (`SessionController.kt:80-89`), so there is nothing to navigate to: the sheet hands `onCreated` to the
///   list, which re-reads (`§` item 15).
/// - **`agentId` and `teamId` are one choice, not two fields.** The submit body splits `executor` on `':'`
///   and sends exactly one of them (`SettingsModal.tsx:92-100`), and the service then leaves `agent_id` NULL
///   for a team conversation on purpose. `SessionExecutorOption.selectionKey` is that encoding, and
///   `SessionCreateDraft` does the splitting, so this file never parses a string back.
///
/// The title check is debounced rather than fired per keystroke (`§` notes the console defines a
/// `debouncedValidateTitle` at `SettingsModal.tsx:54` but never hangs it on the `Form`): one request per
/// letter is a queue of superseded answers over a `COUNT(*)`.
@MainActor
public final class SessionCreateViewModel: ObservableObject {
    /// The two ceilings, read off the contract type so they cannot drift from what actually goes on the wire.
    /// `title` is the DTO's own `@Size(max = 100)` (`SessionCreateRequest.kt:14`); the 500 on the description
    /// is *not* a server rule — the DTO declares that field plain (`:17`) — it is the console's
    /// `maxLength={500}` (`SettingsModal.tsx:174`), which only this client can enforce.
    ///
    /// `nonisolated` because both are read outside the main actor: the sheet's field labels quote them, and a
    /// limit that is only reachable from the actor is a limit the copy cannot state.
    nonisolated public static let titleLimit = SessionCreateDraft.titleLimit
    nonisolated public static let descriptionLimit = SessionCreateDraft.descriptionLimit

    /// The console's intent, which its `Form` never actually wired up (`SettingsModal.tsx:33-51`, `§` item on
    /// the debounce). Long enough that a person keeps typing through it, short enough that the verdict lands
    /// before they reach for the save item.
    nonisolated public static let defaultTitleCheckDelay = Duration.milliseconds(300)

    @Published public var title: String {
        didSet { if title != oldValue { scheduleTitleCheck() } }
    }

    @Published public var note: String

    /// The picker's value, spelled the way the option spells itself (`"agent:7"` / `"team:4"`), because an
    /// agent and a team may share a numeric id (`SettingsModal.tsx:92-94`).
    @Published public var selectionKey: String?

    /// The verdict for `title` as it stands: `.checking` from the moment a check is queued, and any edit
    /// drops it back to `.unchecked`, so a name that was taken a second ago cannot stay lit on a corrected
    /// one — and a corrected name cannot submit before it is asked.
    @Published public private(set) var titleVerdict: SessionTitleVerdict = .unchecked

    @Published public private(set) var choices: SessionExecutorChoices?
    @Published public private(set) var isLoadingChoices = false
    /// The picker's own failure line, kept apart from `errorText` the same way the task form keeps its agent
    /// read apart: a form that cannot list executors has not failed to save.
    @Published public private(set) var choicesErrorText: String?

    @Published public private(set) var isSaving = false
    @Published public private(set) var errorText: String?
    /// Flipped exactly once, and only by a create the server accepted.
    @Published public private(set) var created = false

    private let creating: any SessionCreating
    private let onCreated: () -> Void
    private let titleCheckDelay: Duration
    private var titleCheckTask: Task<Void, Never>?

    public init(
        creating: any SessionCreating,
        titleCheckDelay: Duration = SessionCreateViewModel.defaultTitleCheckDelay,
        onCreated: @escaping () -> Void = {}
    ) {
        self.creating = creating
        self.titleCheckDelay = titleCheckDelay
        self.onCreated = onCreated
        title = ""
        note = ""
    }

    // MARK: - the executor picker

    /// The agents, or the empty list before the read lands. Kept as two arrays so the picker can still show a
    /// group that answered while the other one did not.
    public var agents: [SessionExecutorOption] { choices?.agents ?? [] }
    public var teams: [SessionExecutorOption] { choices?.teams ?? [] }

    /// The option the picker points at, or `nil` for a key that is not in the loaded set — a group that
    /// failed to load after a selection cannot leave a phantom executor in the body.
    public var executor: SessionExecutorOption? {
        guard let selectionKey, let choices else { return nil }
        return choices.options.first { $0.selectionKey == selectionKey }
    }

    /// `GET` the two page routes the console opens the modal with. Nothing here waits on it: the title box is
    /// live from the first frame, and its check runs on its own task, so a slow team route cannot hold up
    /// typing (`§` item on not blocking the form).
    public func loadChoices() async {
        guard choices == nil, !isLoadingChoices else { return }
        isLoadingChoices = true
        choicesErrorText = nil
        defer { isLoadingChoices = false }
        switch await creating.executorChoices() {
        case let .success(loaded):
            choices = loaded
        case let .failure(error):
            choicesErrorText = ErrorMessage.text(for: error)
        }
    }

    /// Which group the read dropped, said out loud. An empty group and an unloadable one read the same in a
    /// picker, and only one of them is the user's problem to solve.
    public var unavailableNotice: String? {
        guard let kinds = choices?.unavailableKinds, !kinds.isEmpty else { return nil }
        let names = kinds.map { hx(Self.titleKey(for: $0)) }.joined(separator: hx("session.create.unavailable.separator"))
        return hx("session.create.unavailable", names)
    }

    /// The two group headers, keyed off the contract's own enum rather than a copy of it.
    public static func titleKey(for kind: SessionExecutorKind) -> String {
        switch kind {
        case .agent: "session.create.kind.agent"
        case .team: "session.create.kind.team"
        }
    }

    /// Whether anything can be created at all: the console loads its two lists independently
    /// (`SettingsModal.tsx:64-86`), so one broken route still leaves a usable picker.
    public var executorSourceIsUsable: Bool { choices?.isUsable == true }

    /// Why the picker is holding the form back, for the save item's own refusal.
    private var executorSourceRefusal: String {
        if let notice = unavailableNotice, !executorSourceIsUsable { return notice }
        if let choicesErrorText { return choicesErrorText }
        return hx("session.create.executor.none")
    }

    // MARK: - what goes on the wire

    private var trimmedTitle: String { title.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// The first field rule this form breaks, as a copy key; `nil` means the fields are ready.
    ///
    /// The title's two rules are the route's own `@Validated` pair (`SessionController.kt:83` against
    /// `SessionCreateRequest.kt:13-15`), reproduced here because that refusal costs a round trip and arrives
    /// as a `@Valid` message rather than a sentence. The description's is the console's, kept because a
    /// column this wide has no reason to be filled past what the form shows.
    public var validationErrorKey: String? {
        if trimmedTitle.isEmpty { return "session.create.title.required" }
        if trimmedTitle.count > Self.titleLimit { return "session.create.title.long" }
        if note.count > Self.descriptionLimit { return "session.create.note.long" }
        if selectionKey == nil { return "session.create.executor.required" }
        return nil
    }

    /// A taken name keeps the save item off, and so does a name nobody has cleared yet — the order the
    /// contract states (`SessionCreating`: check first, then POST, and a failed check blocks rather than
    /// guesses).
    public var canSubmit: Bool {
        validationErrorKey == nil
            && executorSourceIsUsable
            && titleVerdict == .available
            && !isSaving
            && !created
    }

    /// The body, or `nil` while any of the three gates above is shut. Built through the validating
    /// initialiser, which is the one place the title rules and the `agentId`/`teamId` split both live.
    public var draft: SessionCreateDraft? {
        guard canSubmit, let executor else { return nil }
        return SessionCreateDraft(validating: trimmedTitle, sessionDescription: note, executor: executor)
    }

    // MARK: - the debounced check

    private func scheduleTitleCheck() {
        titleCheckTask?.cancel()
        titleCheckTask = nil
        titleVerdict = .unchecked
        let trimmed = trimmedTitle
        // A blank or over-long box is already refused by `validationErrorKey`; asking the route about it
        // spends a `COUNT(*)` on a name that cannot be submitted either way.
        guard hxPresented(trimmed) != nil, trimmed.count <= Self.titleLimit else { return }
        titleVerdict = .checking
        titleCheckTask = Task { [weak self] in
            guard let self else { return }
            try? await Task.sleep(for: self.titleCheckDelay)
            guard !Task.isCancelled else { return }
            await self.checkTitle(trimmed)
        }
    }

    /// The answer only applies while the box still holds the text it was asked about. Cancellation stops the
    /// sleep, not a `sessionTitleTaken` already in flight, so a slow reply to a name the user has since
    /// edited must not stamp a verdict onto the new one.
    private func checkTitle(_ checked: String) async {
        let taken = await creating.sessionTitleTaken(checked)
        guard trimmedTitle == checked else { return }
        switch taken {
        case .success(true):
            titleVerdict = .taken
        case .success(false):
            titleVerdict = .available
        case let .failure(error):
            titleVerdict = .checkFailed(ErrorMessage.text(for: error))
        }
    }

    // MARK: - create

    /// `POST /api/admin/sessions`, once.
    ///
    /// Every refusal below is stated rather than swallowed, and none of them clears the fields: the form is
    /// the only place the title and the description exist, so a banner that costs the user their typing turns
    /// one server complaint into two problems.
    public func save() async {
        guard !isSaving, !created else { return }
        // Before the field rules: when the picker answered and said nothing is pickable, no amount of typing
        // fixes the form, so naming the dead group is the useful sentence rather than "choose an executor".
        // A picker that has not answered yet is not the same claim, so this only fires once `choices` exists.
        if let choices, !choices.isUsable {
            errorText = executorSourceRefusal
            return
        }
        if let reason = validationErrorKey {
            errorText = hx(reason)
            return
        }
        // Past here `choices` is either usable or absent, and absent means the read is still out or it
        // failed — either way the reason belongs on the picker, not on the save item.
        guard executorSourceIsUsable else {
            errorText = executorSourceRefusal
            return
        }
        switch titleVerdict {
        case .taken:
            errorText = hx("session.create.title.taken")
            return
        case let .checkFailed(message):
            errorText = message
            return
        case .checking, .unchecked:
            errorText = hx("session.create.title.pending")
            return
        case .available:
            break
        }
        guard let executor else {
            errorText = hx("session.create.executor.required")
            return
        }
        guard let draft = SessionCreateDraft(
            validating: trimmedTitle,
            sessionDescription: note,
            executor: executor
        ) else {
            // Unreachable while `validationErrorKey` is clean — the two rules are the same pair — but a draft
            // that cannot be built must never be sent by guessing at a body.
            errorText = hx(validationErrorKey ?? "session.create.invalid")
            return
        }
        isSaving = true
        errorText = nil
        let result = await creating.createSession(draft)
        isSaving = false
        switch result {
        case .success:
            // No id comes back (`SessionController.kt:80-89`), so there is no conversation to open: the list
            // re-reads and the sheet goes away.
            created = true
            onCreated()
        case let .failure(error):
            errorText = ErrorMessage.text(for: error)
        }
    }
}
