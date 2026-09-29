import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// The five fill-in shortcuts under the cron field (`harnax-webui/src/pages/agent-task/components/TaskForm.tsx:99-105`).
///
/// They are not a mode. There is no "preset / custom" switch anywhere in this domain: a tap writes the
/// expression into the same text box, and the body carries one `cronExpression` string whatever the user
/// clicked (`§3.3`). The mutual exclusion is a property of the field, so this type only names the literals and
/// the copy — `TaskFormViewModel.selectedPreset` reads the current string back to decide which chip is lit.
public enum CronPreset: String, CaseIterable, Identifiable, Sendable {
    case every5min
    case everyHour
    case everyDay9
    case everyMonday9
    case everyDay0

    public var id: String { rawValue }

    /// Six-field Quartz, sent verbatim. The day/week pair is exactly as the console writes it — a `?` in the
    /// slot the expression does not constrain.
    public var expression: String {
        switch self {
        case .every5min: "0 */5 * * * ?"
        case .everyHour: "0 0 * * * ?"
        case .everyDay9: "0 0 9 * * ?"
        case .everyMonday9: "0 0 9 ? * MON"
        case .everyDay0: "0 0 0 * * ?"
        }
    }

    public var titleKey: String { "cron.preset.\(rawValue)" }

    /// Which chip, if any, the current text came from. An exact match, not a normalised one: the server stores
    /// the string as it arrived and an extra space would make a hand-typed preset read as custom anyway.
    public static func matching(_ expression: String) -> CronPreset? {
        allCases.first { $0.expression == expression }
    }
}

/// `/agent/task` — create and edit one scheduled task.
///
/// The field set is the two request DTOs and nothing else (`§3.1`): `name`, `agentId`, `prompt`,
/// `cronExpression`, `concurrent`, `timeoutSeconds`, `description`, `isPublic`. Four things the row shows are
/// deliberately absent because the stack owns them — `agentName` (admin resolves it from `agentId` before
/// forwarding, `AgentTaskController.kt:230-239`), and `taskStatus`, `creator`, `active`, `tenantId` (all filled
/// by the service, `AgentTaskCrudServiceImpl.kt:97-105`).
///
/// Two rules outrank everything else here, and both are server behaviour:
///
/// - **a successful edit pauses the task.** `updateById` writes `taskStatus = 0` unconditionally
///   (`:156-157`), so the sheet reports that back through `outcome` and the list re-reads and says so (`§6.4`).
///   The form has no status switch of its own — sending one would be a lie either way.
/// - **40902 is a saved row.** When the reconcile against the shared Quartz store does not converge
///   (`:264-293`) the caller still closes the sheet, with the server's sentence as a warning (`§4.3`).
@MainActor
public final class TaskFormViewModel: ObservableObject {
    /// The two `@Size` ceilings of the request DTOs. The console enforces neither (`TaskForm.tsx:128-134` only
    /// marks the field required) and the backend answers 400 with `name: …` for both, so iOS fails fast.
    public static let nameLimit = 128
    public static let noteLimit = 512
    /// The `InputNumber min=30 max=3600` limits (`TaskForm.tsx:195-202`). Nothing on the server checks them —
    /// the DTO has no annotation on this field at all (`AgentTaskCreateRequest.kt:46-47`) — so an out-of-range
    /// value is a client rule and the only place it can be enforced.
    public static let timeoutLowerBound = 30
    public static let timeoutUpperBound = 3600
    /// Both the DTO and the column default to this (`:46-47`, `V1__init_schema.sql:220`), and the console seeds
    /// the field with it too.
    public static let defaultTimeoutSeconds = 300

    @Published public var name: String
    @Published public var agentId: Int64?
    @Published public var prompt: String
    @Published public var cronExpression: String
    /// Kept as text because the field is a free-form number box: the moment a half-typed `30` would be coerced
    /// to an `Int`, the form starts rewriting what the user is looking at.
    @Published public var timeoutText: String
    @Published public var concurrent: Bool
    @Published public var note: String
    @Published public var isPublic: Bool

    @Published public private(set) var isSaving = false
    @Published public private(set) var errorText: String?
    /// Non-nil exactly when the write counts as done, which is what the sheet watches to dismiss itself.
    @Published public private(set) var outcome: TaskFormSaveOutcome?
    @Published public private(set) var agents: [AgentTaskAgentOption] = []
    @Published public private(set) var isLoadingAgents = false
    /// The picker's own failure line, kept apart from `errorText`: a form that cannot list agents is not a form
    /// that failed to save, and its retry belongs on the field.
    @Published public private(set) var agentErrorText: String?

    /// The row as it arrived, for the partial-body decisions below. `nil` on create.
    public let row: AgentTaskSummary?
    private let catalog: any AgentTaskCataloging

    public init(row: AgentTaskSummary?, catalog: any AgentTaskCataloging) {
        self.row = row
        self.catalog = catalog
        name = row?.name ?? ""
        agentId = row?.agentId
        prompt = row?.prompt ?? ""
        cronExpression = row?.cronExpression ?? ""
        timeoutText = row.map { String($0.timeoutSeconds) } ?? String(Self.defaultTimeoutSeconds)
        // The two switches are 0/1 columns on the wire and booleans in the form (`TaskForm.tsx:29-33`).
        concurrent = row?.allowsConcurrentRun ?? false
        note = row?.description ?? ""
        isPublic = row?.isShared ?? false
    }

    public var isEditing: Bool { row != nil }
    public var saved: Bool { outcome != nil }

    /// Whether an edit has anything to send. An untouched form still saves — the server reads a body of no keys
    /// as "keep everything" and answers 200 — but it pauses the task while doing it, so the screen warns first.
    ///
    /// Read off the per-field decisions rather than off `change`, because `change` is `nil` while the form is
    /// invalid and an unsendable form is not the same thing as an unchanged one.
    public var hasChanges: Bool {
        guard isEditing else { return true }
        return submittedName != nil || submittedAgentId != nil || submittedPrompt != nil
            || submittedCron != nil || submittedConcurrent != nil || submittedTimeout != nil
            || submittedNote != nil || submittedPublic != nil
    }

    public var selectedPreset: CronPreset? { CronPreset.matching(cronExpression) }

    /// A preset is a fill helper, not a second value: writing it overwrites whatever was typed, and editing the
    /// text afterwards simply unlights the chip (`§3.3`).
    public func choose(_ preset: CronPreset) {
        cronExpression = preset.expression
    }

    // MARK: - what goes on the wire

    private var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var trimmedCron: String { cronExpression.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var trimmedTimeout: String { timeoutText.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// `nil` when the box is empty (which lets the server default stand) or holds something that is not a
    /// number in range.
    public var parsedTimeout: Int? {
        guard let value = Int(trimmedTimeout), Self.isAllowedTimeout(value) else { return nil }
        return value
    }

    public static func isAllowedTimeout(_ value: Int) -> Bool {
        (timeoutLowerBound...timeoutUpperBound).contains(value)
    }

    /// The target, or `nil` when it still reads as the stored one.
    ///
    /// Sending an unchanged `agentId` is not merely redundant: admin resolves it and overwrites the row's
    /// `agentName` snapshot on every body that names one (`AgentTaskController.kt:100,230-239`), so an
    /// unchanged id would re-stamp a name the task already carries.
    public var submittedAgentId: Int64? {
        guard let agentId else { return nil }
        guard let row else { return agentId }
        return agentId == row.agentId ? nil : agentId
    }

    /// The rename, or `nil` when the name is untouched — the only way a name edit can collide (`:122-128`).
    public var submittedName: String? {
        guard let row else { return trimmedName }
        return trimmedName == row.name ? nil : trimmedName
    }

    public var submittedPrompt: String? {
        guard let row else { return prompt }
        return prompt == row.prompt ? nil : prompt
    }

    public var submittedCron: String? {
        guard let row else { return trimmedCron }
        return trimmedCron == row.cronExpression ? nil : trimmedCron
    }

    public var submittedNote: String? {
        guard let row else { return note.isEmpty ? nil : note }
        return note == row.description ? nil : note
    }

    public var submittedConcurrent: Int? {
        guard let row else { return concurrent.hxInt }
        return concurrent == row.allowsConcurrentRun ? nil : concurrent.hxInt
    }

    public var submittedPublic: Int? {
        guard let row else { return isPublic.hxInt }
        return isPublic == row.isShared ? nil : isPublic.hxInt
    }

    /// A blank box on create sends nothing at all, which is how the DTO's own 300 applies; on edit it means
    /// "keep what is stored".
    public var submittedTimeout: Int? {
        guard let parsedTimeout else { return nil }
        guard let row else { return parsedTimeout }
        return parsedTimeout == row.timeoutSeconds ? nil : parsedTimeout
    }

    public var draft: AgentTaskDraft? {
        guard !isEditing, validationErrorKey == nil, let agentId else { return nil }
        return AgentTaskDraft(
            name: trimmedName,
            agentId: agentId,
            prompt: prompt,
            cronExpression: trimmedCron,
            concurrent: concurrent.hxInt,
            timeoutSeconds: submittedTimeout,
            description: note.isEmpty ? nil : note,
            isPublic: isPublic.hxInt
        )
    }

    /// The whole edit body. Its `nil` fields are left off the JSON, so an untouched form posts `{}` and changes
    /// nothing except the task's schedule.
    public var change: AgentTaskChange? {
        guard isEditing, validationErrorKey == nil else { return nil }
        return AgentTaskChange(
            name: submittedName,
            agentId: submittedAgentId,
            prompt: submittedPrompt,
            cronExpression: submittedCron,
            concurrent: submittedConcurrent,
            timeoutSeconds: submittedTimeout,
            description: submittedNote,
            isPublic: submittedPublic
        )
    }

    // MARK: - validation

    /// The first reason this form cannot be posted, as a copy key; `nil` means it is ready.
    ///
    /// On the edit path a field that still reads like the row's own is skipped, because it is not going out and
    /// the server will not judge it. A field that *is* going out is judged here even when the update DTO would
    /// have taken it: `AgentTaskUpdateRequest` declares every property nullable with no `@NotBlank`
    /// (`:16-45`), so a cleared name or cron would not be refused by the backend at all — it would be
    /// **stored**, and the scheduler would then fail to build a trigger for it.
    public var validationErrorKey: String? {
        if trimmedName.isEmpty { return "task.form.name.required" }
        if trimmedName.count > Self.nameLimit { return "task.form.name.long" }
        if agentId == nil { return "task.form.agent.required" }
        if prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return "task.form.prompt.required" }
        if trimmedCron.isEmpty { return "task.form.cron.required" }
        // The backend's own arithmetic (`AgentTaskCrudServiceImpl.kt:222-225`), reproduced so the refusal is a
        // sentence rather than a round trip.
        if !QuartzCron.hasServerAcceptedArity(trimmedCron) { return "task.form.cron.arity" }
        if !QuartzCron.hasConflictFreeDayFields(trimmedCron) { return "task.form.cron.dayFields" }
        if !trimmedTimeout.isEmpty {
            guard parsedTimeout != nil else { return "task.form.timeout.range" }
        }
        if note.count > Self.noteLimit { return "task.form.note.long" }
        return nil
    }

    public var canSubmit: Bool { validationErrorKey == nil && !isSaving }

    // MARK: - the picker's source

    /// `GET /agent-tasks/agents` is admin's own projection of the *live* agents, so a task bound to an agent
    /// that has since been stopped would otherwise open with an empty target field and post a change the user
    /// never asked for. `ensureStoredAgentListed` below puts the stored row back in.
    public func loadAgents() async {
        guard agents.isEmpty, !isLoadingAgents else { return }
        isLoadingAgents = true
        agentErrorText = nil
        defer { isLoadingAgents = false }
        switch await catalog.agentTaskAgents() {
        case let .success(options):
            agents = options.filter { $0.id != nil }
            ensureStoredAgentListed()
        case let .failure(error):
            agentErrorText = ErrorMessage.text(for: error)
        }
    }

    private func ensureStoredAgentListed() {
        guard let stored = row?.agentId, !agents.contains(where: { $0.id == stored }) else { return }
        agents.append(
            AgentTaskAgentOption(id: stored, name: hxPresented(row?.agentName) ?? "#\(stored)")
        )
    }

    public var agentTitle: String? {
        agents.first(where: { $0.id == agentId })?.displayName
    }

    // MARK: - save

    public func save() async {
        guard !isSaving else { return }
        if let reason = validationErrorKey {
            outcome = nil
            errorText = hx(reason)
            return
        }
        isSaving = true
        errorText = nil
        let result: Result<EmptyResponse, APIError>
        if let row, let id = row.id, let change {
            result = await catalog.updateAgentTask(id: id, change)
        } else if row != nil {
            // A row the wire gave no id for cannot be written: the id is this route's only address.
            isSaving = false
            errorText = ErrorMessage.text(for: .unpackable)
            return
        } else if let draft {
            result = await catalog.createAgentTask(draft)
        } else {
            isSaving = false
            errorText = ErrorMessage.text(for: .decoding)
            return
        }
        isSaving = false
        switch result {
        case .success:
            // Create cannot answer 40902 — the reconcile that raises it only runs on update and delete
            // (`§4.2`) — and a create leaves the task paused by the server's own hand
            // (`AgentTaskCrudServiceImpl.kt:97`), which is not news the user needs a banner about.
            outcome = TaskFormSaveOutcome(didPauseScheduledTask: isEditing)
        case let .failure(error):
            if AgentTaskCode.isSchedulerOutOfSync(error), isEditing {
                // 40902: written, not scheduled. Close the sheet and carry the sentence to the list as a
                // warning, exactly as the console does (`TaskForm.tsx:85-91`, `§4.3`).
                outcome = TaskFormSaveOutcome(
                    didPauseScheduledTask: true,
                    schedulerSyncWarning: ErrorMessage.text(for: error)
                )
            } else {
                // Includes a create's business refusal, which arrives as **500** while the same class of
                // rejection on update and delete arrives as 400 (`§4.2`). Nothing here branches on that: the
                // verdict is `code != 200` and the text is the server's own English (`§6.1`).
                outcome = nil
                errorText = ErrorMessage.text(for: error)
            }
        }
    }
}
