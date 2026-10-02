import SwiftUI
import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// The three steps of the team wizard: 基本信息 → 主管技能 → 成员智能体.
///
/// Mirror: `harnax-webui/src/pages/team/components/TeamWizard.tsx:261`-`:269`. The product ruling said two
/// steps and the code has three; the spec settled it in favour of the code (`specs/01-agent-team.md:133`,
/// 「未确认」 1 at `:341`), so this enum has three cases.
///
/// A team has no tool, MCP or CLI step at all: the lead carries skills and nothing else, and the tools its
/// work needs belong to the member agents on their own pages
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/TeamCreateRequest.kt:10`-`:35`).
public enum TeamWizardStep: Int, CaseIterable, Identifiable, Sendable {
    case basic
    case skill
    case member

    public var id: Int { rawValue }

    public var titleKey: String {
        switch self {
        case .basic: return "team.wizard.step.basic"
        case .skill: return "team.wizard.step.skill"
        case .member: return "team.wizard.step.member"
        }
    }

    /// Only step 2 may be walked out of without meeting its own rules: step 1's four fields are what
    /// `handleNext` demands (`TeamWizard.tsx:230`-`:235`), and step 3 is where the save lives and 「至少需要一个
    /// 成员」 is refused there (`:192`-`:198`), so a team can never be completed without a member.
    ///
    /// The console has no 跳过 button at all — its footer is Previous plus Next/Create
    /// (`TeamWizard.tsx:437`-`:447`) — and an untouched skill step gets through `handleNext` only because a
    /// blank row is not an issue (`configValidation.ts:140`). This is the iOS affordance for that same walk,
    /// and a step 2 left half-filled still bounces back to step 2 at save (`validateAll` runs the skill rules
    /// first).
    public var isSkippable: Bool { self == .skill }
    public var isLast: Bool { self == .member }
    public var following: TeamWizardStep? { TeamWizardStep(rawValue: rawValue + 1) }
    public var preceding: TeamWizardStep? { rawValue > 0 ? TeamWizardStep(rawValue: rawValue - 1) : nil }
}

/// Which control an issue belongs to, so the view can focus the offending field once a step refuses.
public enum TeamWizardField: String, Equatable, Sendable {
    case name
    case detail
    case prompt
    case model
    case skill
    case member
    case delegation
}

/// One refusal, already carrying rendered copy: the view can list issues that came from different steps
/// without knowing which catalogue each came from.
public struct TeamWizardIssue: Equatable, Sendable {
    public let field: TeamWizardField
    public let step: TeamWizardStep
    public let message: String

    public init(field: TeamWizardField, step: TeamWizardStep, message: String) {
        self.field = field
        self.step = step
        self.message = message
    }
}

/// What a save attempt ended in.
public enum TeamWizardResult: Equatable, Sendable {
    case created
    /// The id and the name the row now has. Only an update offers the refresh panel: a create has no team
    /// session yet (`harnax-webui/src/pages/team/index.tsx:394`-`:399` sets it on the update leg alone).
    case updated(id: Int64, name: String)
    case invalid(issues: [TeamWizardIssue], step: TeamWizardStep)
    case failed(message: String)
}

/// A row of the lead-model dropdown.
///
/// `isSelectable` is false for exactly one row: the model the stored team points at which has fallen out of
/// the candidate page. It stays listed so the form can still name what the row holds, and it refuses a tap,
/// which is what the console's `disabled: true` option means (`TeamWizard.tsx:49`-`:68`).
public struct TeamModelChoice: Identifiable, Equatable, Sendable {
    public let id: Int64
    public let title: String
    public let isSelectable: Bool

    public init(id: Int64, title: String, isSelectable: Bool = true) {
        self.id = id
        self.title = title
        self.isSelectable = isSelectable
    }

    /// The picker shape, for a view that hands the selectable rows to `HXEntityPicker`.
    public var option: HXEntityPickerOption { HXEntityPickerOption(id: id, title: title) }
}

/// One lead-skill row — the agent wizard's step 4 row, reused whole: repository and skill, and no
/// environment dimension anywhere in either (`TeamWizard.tsx:412`-`:418` renders the very same
/// `SkillConfigPanel`; `harnax-admin/.../AgentServiceImpl.kt:530`-`:533` has no env column for a skill).
public struct TeamSkillRow: Identifiable, Equatable, Sendable {
    public let id: UUID
    public var repositoryID: Int64?
    public var repositoryName: String?
    public var skillID: Int64?
    public var skillName: String?
    /// Seeded from `skillAvailable == false` on the row this wizard opened with: the bound skill no longer
    /// resolves, and the runtime will not load it (`TeamServiceImpl.kt:182`-`:192`). The flag is the stored
    /// evidence, never a guess from the current page.
    public var storedReferenceBroken: Bool

    public init(
        id: UUID = UUID(),
        repositoryID: Int64? = nil,
        repositoryName: String? = nil,
        skillID: Int64? = nil,
        skillName: String? = nil,
        storedReferenceBroken: Bool = false
    ) {
        self.id = id
        self.repositoryID = repositoryID
        self.repositoryName = repositoryName
        self.skillID = skillID
        self.skillName = skillName
        self.storedReferenceBroken = storedReferenceBroken
    }

    /// Nothing chosen: the row is a slot the 「添加技能」 button left behind, and
    /// `findSkillIssue` skips it outright (`configValidation.ts:140`).
    public var isEmpty: Bool { repositoryID == nil && skillID == nil }
}

/// One member row: an existing agent plus what this team asks of it. Order is not decoration — the lead
/// reads its delegates in array order (`MembersField.tsx:192`-`:197`, `TeamServiceImpl.kt:298` insertion
/// order read back by id).
public struct TeamMemberRow: Identifiable, Equatable, Sendable {
    public let id: UUID
    public var agentID: Int64?
    public var shownName: String?
    public var delegationDescription: String
    /// Seeded from `agentAvailable == false` or `agentStatus != 1` on the row this wizard opened with —
    /// the two flags admin computed per member, which is what answers 「deleted or disabled」 as opposed to
    /// 「merely off this page」 (`MembersField.tsx:65`-`:81`, `TeamServiceImpl.kt:194`-`:202`).
    public var storedReferenceBroken: Bool

    public init(
        id: UUID = UUID(),
        agentID: Int64? = nil,
        shownName: String? = nil,
        delegationDescription: String = "",
        storedReferenceBroken: Bool = false
    ) {
        self.agentID = agentID
        self.id = id
        self.shownName = shownName
        self.delegationDescription = delegationDescription
        self.storedReferenceBroken = storedReferenceBroken
    }
}

/// C4 — the three-step create/edit form for a 多智能体团队.
///
/// One type serves both modes because the console serves both from one component: 「新建与编辑共用一个向导：
/// 两者只差初始值、提交调用和这一步的按钮文案」 (`TeamWizard.tsx:18`-`:37`). Edit seeds from the **list row**,
/// not from a detail request — `GET /api/admin/teams/page` already fills `systemPrompt`, `modelId`, the
/// lead's `skillList` and the `memberList` per row, and `team/index.tsx:382`-`:386` hands the row straight to
/// the wizard (`specs/01-agent-team.md:253`-`:257`).
///
/// The seven differences from the agent wizard are implemented as the code has them
/// (`specs/01-agent-team.md:146`-`:154`): three steps and no tool/MCP/CLI dimension, no `owner` field at all,
/// a member section with a remote search, no environment parameter anywhere, invalid references that stay
/// visible and flagged, skills sent as a *set* that may be left off, and — the one that has no agent-side
/// counterpart at all — no permission gate on 是否公开.
@MainActor
public final class TeamFormViewModel: ObservableObject {
    /// Which team the wizard is about, and where its initial values come from.
    public enum Mode: Sendable {
        case create
        case edit(TeamSummary)

        public var isCreate: Bool {
            if case .create = self { return true }
            return false
        }
    }

    // MARK: - Step 1

    /// Current step. Writing it is how a step chip jumps about; `next()`/`back()`/`skip()` are the walk.
    @Published public var step: TeamWizardStep = .basic
    @Published public var name: String = ""
    /// The team description — the same word the agent wizard's step 1 uses for its own second field.
    @Published public var detail: String = ""
    /// The lead's own prompt. There is no agent behind a team to inherit one from
    /// (`TeamResponse.kt:9`-`:12`, `TeamWizard.tsx:341`-`:343`).
    @Published public var systemPrompt: String = ""
    @Published public var modelID: Int64?
    @Published public var isPublic: Bool = false

    // MARK: - Steps 2 and 3

    @Published public var skillRows: [TeamSkillRow] = []
    @Published public var memberRows: [TeamMemberRow] = []

    // MARK: - Candidates

    @Published public private(set) var modelCandidates: [ModelSummary] = []
    /// Whether the model page has answered at all. A lead model has no `…Available` flag in the team DTO, so
    /// the candidate page is the only evidence that a stored `modelId` is gone — and before it answers, or
    /// when it fails, "gone" is a claim the form has no right to make.
    @Published public private(set) var modelPageHasArrived = false
    @Published public private(set) var repositories: [SkillSourceSummary] = []
    /// Skills keyed by repository: step 2 reads one page per repository the operator opens
    /// (`TeamWizard.tsx:120`-`:123`, `:152`-`:164`).
    @Published public private(set) var skillsByRepository: [Int64: [SkillItem]] = [:]
    /// The seed pool, read once on arrival at step 3: one page of 200 enabled agents, which is what the
    /// console loads when the team page itself opens (`harnax-webui/src/pages/team/index.tsx:76`-`:83`).
    @Published public private(set) var memberCandidates: [AgentSummary] = []
    /// The last search's rows, or `nil` for 「no search in effect」. `nil` is not an empty result: the pool
    /// falls back to the seed page, exactly as `pool = candidates || agents` does (`MembersField.tsx:53`).
    @Published public private(set) var memberSearchResults: [AgentSummary]?
    @Published public private(set) var isSearchingMembers = false
    @Published public private(set) var isPreparing = false
    @Published public private(set) var candidateError: String?
    @Published public private(set) var isSaving = false

    // MARK: - Seams

    private let teams: any TeamCataloging
    private let writer: any TeamWriting
    private let models: any ModelCataloging
    private let skills: any SkillCataloging
    private let agents: any AgentCataloging

    public let mode: Mode
    /// Kept, and deliberately never used as a gate: a team's 是否公开 has no permission rule in front of it
    /// at all, unlike the agent form's (`TeamWizard.tsx:388`-`:401`, where the `Switch` carries no
    /// `disabled`; `specs/01-agent-team.md:141`, 「无权限门禁（与智能体编辑页不同）」).
    public let account: AccountSnapshot?
    private let editingID: Int64?
    /// The row's own model name, kept because the 100-row candidate page may not hold it
    /// (`TeamSummary.modelName` is the only name a broken reference still has).
    private let storedModelName: String?
    /// The ids the lead currently binds. The set comparison that decides whether `skillIds` goes out at all
    /// needs the baseline, and after the operator edits a row there is nothing else to compare against
    /// (`TeamWizard.tsx:199`-`:204`).
    private let storedSkillIDs: [Int64]

    /// Steps whose candidates have been read. A step is read once, and `reload(_:)` is the escape hatch.
    private var loaded: Set<TeamWizardStep> = []

    public init(
        mode: Mode,
        account: AccountSnapshot?,
        teams: any TeamCataloging,
        writer: any TeamWriting,
        models: any ModelCataloging,
        skills: any SkillCataloging,
        agents: any AgentCataloging
    ) {
        self.mode = mode
        self.account = account
        self.teams = teams
        self.writer = writer
        self.models = models
        self.skills = skills
        self.agents = agents
        if case let .edit(row) = mode {
            editingID = row.id
            storedModelName = hxPresented(row.modelName)
            storedSkillIDs = row.skillList.map(\.skillId)
        } else {
            editingID = nil
            storedModelName = nil
            storedSkillIDs = []
        }
        seed()
    }

    /// Assembly from the app's dependency bag; the seams above are what tests drive directly.
    public convenience init(dependencies: HarnaxDependencies, mode: Mode, account: AccountSnapshot?) {
        self.init(
            mode: mode,
            account: account,
            teams: dependencies.teams,
            writer: dependencies.teamWrite,
            models: dependencies.models,
            skills: dependencies.skills,
            agents: dependencies.agents
        )
    }

    // MARK: - Step 1 surface

    public var isCreate: Bool { mode.isCreate }

    /// The id the row currently holds, or `nil` when the candidate page does not resolve it.
    public var selectedModel: ModelSummary? {
        guard let modelID else { return nil }
        return modelCandidates.first { $0.id == modelID }
    }

    /// The line the control shows for the current selection: the candidate's own text when it resolves, the
    /// stored name plus the unavailability suffix when it does not
    /// (`TeamWizard.tsx:57`-`:66`).
    public var modelName: String {
        if let selectedModel { return modelText(selectedModel) }
        guard let modelID else { return hx("team.wizard.model.none") }
        let stored = storedModelName ?? "#\(modelID)"
        return isStoredModelUnavailable ? "\(stored) · \(hx("team.wizard.model.unavailable"))" : stored
    }

    /// Whether the current selection is a model the form has proved it cannot offer as a live choice.
    public var isStoredModelUnavailable: Bool { modelPageHasArrived && modelID != nil && selectedModel == nil }

    /// The dropdown's rows, in the console's order: the broken stored model first (the TSX `unshift`es it,
    /// `TeamWizard.tsx:59`), then every enabled chat model the page returned.
    public var modelChoices: [TeamModelChoice] {
        var choices = modelCandidates.compactMap { model -> TeamModelChoice? in
            guard let id = model.id else { return nil }
            return TeamModelChoice(id: id, title: modelText(model))
        }
        if isStoredModelUnavailable, let modelID {
            choices.insert(TeamModelChoice(id: modelID, title: modelName, isSelectable: false), at: 0)
        }
        return choices
    }

    /// Only the rows a tap may land on — what a picker listing is actually made of.
    public var modelOptions: [HXEntityPickerOption] { modelChoices.filter(\.isSelectable).map(\.option) }

    /// The wizard's own title (`TeamWizard.tsx:247`-`:251`).
    public var titleKey: String { isCreate ? "team.wizard.title.create" : "team.wizard.title.edit" }

    /// And its subtitle: a create explains the walk, an edit warns that running team sessions keep the
    /// previous members until refreshed (`TeamWizard.tsx:252`-`:257`).
    public var subtitleKey: String { isCreate ? "team.wizard.subtitle.create" : "team.wizard.subtitle.edit" }

    /// The 是否公开 switch. Always editable: the team form has no visibility gate to honour, and `admin`'s
    /// own write path does not impose one either — `updateTeam` takes `isPublic` from the body with no
    /// permission check (`TeamServiceImpl.kt:112`).
    public var canChangeVisibility: Bool { true }

    // MARK: - Walking the steps

    /// Validate the current step and, when it passes, move on. The returned issues are the ones that held the
    /// step closed, which is what the view lists.
    @discardableResult
    public func next() -> [TeamWizardIssue] {
        let issues = validate(step)
        guard issues.isEmpty, let following = step.following else { return issues }
        step = following
        return []
    }

    /// Leave step 2 without running its rules. A step skipped this way is not a step forgiven: `save()` runs
    /// the same rules and walks back to it (`validateAll`).
    public func skip() {
        guard step.isSkippable, let following = step.following else { return }
        step = following
    }

    public func back() {
        guard let preceding = step.preceding else { return }
        step = preceding
    }

    // MARK: - Candidates, read on demand

    /// Read the candidates a step needs. Called by the view whenever `step` changes, and a no-op for a step
    /// already read, so walking back and forth does not re-hit the endpoints.
    public func load(_ step: TeamWizardStep) async {
        guard !loaded.contains(step) else { return }
        isPreparing = true
        candidateError = nil
        defer {
            isPreparing = false
            loaded.insert(step)
        }
        switch step {
        case .basic: await loadModels()
        case .skill: await loadRepositories()
        case .member: await loadMemberSeed()
        }
    }

    /// Force a step's read again — the retry affordance behind a failed candidate list.
    public func reload(_ step: TeamWizardStep) async {
        loaded.remove(step)
        await load(step)
    }

    public func loadModels() async {
        // One page of 100, then keep `status===1 && modelType==='chat'` — the same read the agent wizard
        // makes and the same filter (`TeamWizard.tsx:139`-`:150`, against `CreateForm.tsx:101`-`:107`).
        switch await models.modelChoices(num: 1, size: 100) {
        case let .success(page):
            modelCandidates = page.records.filter { $0.status == 1 && $0.modelType == "chat" }
            modelPageHasArrived = true
        case let .failure(error):
            candidateError = ErrorMessage.text(for: error)
        }
    }

    public func loadRepositories() async {
        let fetched = await skills.sourcePage(name: nil, sourceType: nil, status: 1, num: 1, size: 100)
        switch fetched {
        case let .success(page):
            // The built-in CLI repository is dropped: a team has no CLI leg, and its skills may only arrive
            // through a CLI binding (`TeamWizard.tsx:131`-`:134`; `SkillBindingResolver.kt:53`-`:60` refuses
            // the same ids again server-side).
            repositories = page.records.filter { !$0.isPlatformOwned }
            for repositoryID in skillRows.compactMap(\.repositoryID) where skillsByRepository[repositoryID] == nil {
                await loadSkills(repositoryID: repositoryID)
            }
        case let .failure(error):
            candidateError = ErrorMessage.text(for: error)
        }
    }

    public func loadSkills(repositoryID: Int64) async {
        switch await skills.skillPage(name: nil, repositoryID: repositoryID, status: 1, num: 1, size: 100) {
        case let .success(page):
            skillsByRepository[repositoryID] = page.records
        case let .failure(error):
            candidateError = ErrorMessage.text(for: error)
        }
    }

    /// The member seed page: one page of 200 enabled agents (`team/index.tsx:76`-`:83`).
    public func loadMemberSeed() async {
        switch await agents.page(name: nil, status: 1, num: 1, size: Self.memberSeedPageSize) {
        case let .success(page):
            memberCandidates = page.records
        case let .failure(error):
            candidateError = ErrorMessage.text(for: error)
        }
    }

    // MARK: - The member search

    /// The member dropdown's remote search: `pageSize=50, status=1, name=<keyword>`
    /// (`MembersField.tsx:41`-`:44`).
    ///
    /// The 300 ms debounce is the view's, not the model's: `MembersField` hangs it on a `setTimeout`
    /// (`MembersField.tsx:41`-`:50`) and the spec tells iOS to do the same with `.task(id:)`
    /// (`specs/01-agent-team.md:305`, 注意点 5). `Self.searchDebounceMilliseconds` is the interval to use.
    /// What this method owns is the two things a view cannot get wrong: the request shape, and the fallback
    /// — a failed search returns to the seed pool rather than claiming the catalogue is empty
    /// (`MembersField.tsx:45`-`:49`).
    public func searchMembers(_ keyword: String) async {
        guard let term = keyword.trimmed else {
            memberSearchResults = nil
            return
        }
        isSearchingMembers = true
        defer { isSearchingMembers = false }
        switch await agents.page(name: term, status: 1, num: 1, size: Self.memberSearchPageSize) {
        case let .success(page):
            // An honest empty result stays empty: no results is a claim about the catalogue, and this one
            // the server made.
            memberSearchResults = page.records
        case .failure:
            memberSearchResults = nil
        }
    }

    /// The pool the dropdown lists: the search's rows while one is in effect, the seed page otherwise.
    public var memberPool: [AgentSummary] { memberSearchResults ?? memberCandidates }

    // MARK: - Options

    public var repositoryOptions: [HXEntityPickerOption] {
        repositories.map { repository in
            HXEntityPickerOption(id: repository.id, title: repository.title, subtitle: repository.endpoint)
        }
    }

    public func skillOptions(in repositoryID: Int64?, excluding taken: Set<Int64>) -> [HXEntityPickerOption] {
        guard let repositoryID else { return [] }
        return (skillsByRepository[repositoryID] ?? []).compactMap { skill in
            guard let id = skill.id, !taken.contains(id) else { return nil }
            return HXEntityPickerOption(
                id: id,
                title: skill.title ?? hx("team.binding.skillFallback", Int(id)),
                subtitle: hxPresented(skill.description)
            )
        }
    }

    /// The member dropdown. Unlike the agent wizard's four pickers, this one does **not** hide what another
    /// row already holds: the web leaves a duplicate selectable and refuses it with a row-level validator
    /// (`MembersField.tsx:114`-`:130`; `specs/01-agent-team.md:173`, 「下拉可重复选，靠行级 validator 拦」),
    /// because a team's members are de-duplicated by a refusal rather than by a silent `distinctBy`
    /// (`TeamServiceImpl.kt:284`-`:286`).
    ///
    /// A stored member that the pool does not hold still gets a row of its own, so the dropdown never reads
    /// as a bare id, and the unavailability suffix only appears when admin's flags said so
    /// (`MembersField.tsx:62`-`:81`).
    public var memberOptions: [HXEntityPickerOption] {
        var options = memberPool.compactMap { agent -> HXEntityPickerOption? in
            guard let id = agent.id else { return nil }
            return HXEntityPickerOption(
                id: id,
                title: hxPresented(agent.name) ?? hx("team.binding.memberFallback", Int(id)),
                subtitle: hxPresented(agent.description)
            )
        }
        for row in memberRows {
            guard let id = row.agentID, !options.contains(where: { $0.id == id }) else { continue }
            let name = hxPresented(row.shownName) ?? hx("team.binding.memberFallback", Int(id))
            options.append(
                HXEntityPickerOption(
                    id: id,
                    title: row.storedReferenceBroken
                        ? "\(name) · \(hx("team.binding.unavailable"))"
                        : name,
                    subtitle: nil
                )
            )
        }
        return options
    }

    /// Every member option, search included, minus the ones another picker is already showing.
    public func memberOptions(excluding taken: Set<Int64>) -> [HXEntityPickerOption] {
        memberOptions.filter { !taken.contains($0.id) }
    }

    // MARK: - Invalid references

    /// Whether one lead-skill row points at a skill that no longer resolves.
    ///
    /// The flag is the stored row's own evidence (`skillAvailable == false`), and it clears the moment a
    /// loaded candidate proves the skill is live — the skill page is asked with `status=1`, so resolving
    /// there is proof of enablement. Nothing here guesses from 「not on this page」, which is exactly the
    /// claim the console refuses to make (`MembersField.tsx:62`-`:70`).
    public func isSkillUnavailable(for rowID: UUID) -> Bool {
        guard let row = skillRows.first(where: { $0.id == rowID }),
              let id = row.skillID,
              row.storedReferenceBroken
        else { return false }
        guard let repositoryID = row.repositoryID else { return true }
        return !(skillsByRepository[repositoryID]?.contains(where: { $0.id == id }) ?? false)
    }

    /// Whether one member row points at an agent that no longer resolves — same rule, same reason
    /// (`TeamServiceImpl.kt:194`-`:202`: a member whose agent row has since gone stays in the list, flagged,
    /// because a silently shorter list is what lets an operator believe the team is still intact).
    public func isMemberUnavailable(for rowID: UUID) -> Bool {
        guard let row = memberRows.first(where: { $0.id == rowID }), let id = row.agentID else { return false }
        guard row.storedReferenceBroken else { return false }
        return !memberPool.contains(where: { $0.id == id })
    }

    /// The sentence the row appends to a broken reference, for a view that renders the flag itself rather
    /// than reading it out of an option's title.
    public func unavailableSuffix(for field: TeamWizardField) -> String {
        switch field {
        case .skill, .member: return hx("team.binding.unavailable")
        case .model: return hx("team.wizard.model.unavailable")
        default: return ""
        }
    }

    // MARK: - Step 1 edits

    public func pickModel(_ option: HXEntityPickerOption) {
        modelID = option.id
    }

    public func clearModel() {
        modelID = nil
    }

    /// The switch is free for a team at every step of the wizard's life — see `canChangeVisibility`.
    public func setIsPublic(_ value: Bool) {
        isPublic = value
    }

    // MARK: - Step 2 edits

    public func addSkillRow() {
        skillRows.append(TeamSkillRow())
    }

    /// The last skill row is cleared rather than deleted, the way the shared panel does it
    /// (`SkillConfigPanel.tsx:76`-`:84`): with one row left there would be no place left to pick the replacement,
    /// and the add button appends to the tail, so a delete that removed the only row reads as a failed delete.
    public func removeSkillRow(_ rowID: UUID) {
        guard let index = skillRows.firstIndex(where: { $0.id == rowID }) else { return }
        if skillRows.count == 1 {
            skillRows[index] = TeamSkillRow(id: rowID)
            return
        }
        skillRows.remove(at: index)
    }

    /// Clearing the repository zeroes the whole row, and picking one reads that repository's skills the first
    /// time it is needed (`TeamWizard.tsx:152`-`:164`).
    public func pickRepository(_ option: HXEntityPickerOption?, into rowID: UUID) async {
        guard let index = skillRows.firstIndex(where: { $0.id == rowID }) else { return }
        guard let option else {
            skillRows[index] = TeamSkillRow(id: rowID)
            return
        }
        let repositoryID = option.id
        skillRows[index].repositoryID = repositoryID
        skillRows[index].repositoryName = option.title
        skillRows[index].skillID = nil
        skillRows[index].skillName = nil
        if skillsByRepository[repositoryID] == nil {
            await loadSkills(repositoryID: repositoryID)
        }
    }

    public func pickSkill(_ option: HXEntityPickerOption, into rowID: UUID) {
        guard let index = skillRows.firstIndex(where: { $0.id == rowID }) else { return }
        let repositoryID = skillRows[index].repositoryID
        let item = repositoryID.flatMap { skillsByRepository[$0]?.first { $0.id == option.id } }
        skillRows[index].skillID = option.id
        skillRows[index].skillName = item?.title ?? option.title
        // A pick proves a live reference: the row now holds something the candidate page handed out, so the
        // stored flag has nothing left to say about it.
        skillRows[index].storedReferenceBroken = false
    }

    public func clearSkill(_ rowID: UUID) {
        guard let index = skillRows.firstIndex(where: { $0.id == rowID }) else { return }
        skillRows[index].skillID = nil
        skillRows[index].skillName = nil
    }

    public var excludedSkillIDs: Set<Int64> { Set(skillRows.compactMap(\.skillID)) }

    public func excludedSkillIDs(except rowID: UUID) -> Set<Int64> {
        Set(skillRows.filter { $0.id != rowID }.compactMap(\.skillID))
    }

    // MARK: - Step 3 edits

    /// 「添加成员」 appends a blank row (`MembersField.tsx:180`-`:188`).
    public func addMemberRow() {
        memberRows.append(TeamMemberRow())
    }

    /// The remove icon only appears while there is more than one row, so a team always has a place to pick
    /// into (`MembersField.tsx:170`-`:176`).
    public var canRemoveMemberRow: Bool { memberRows.count > 1 }

    public func removeMemberRow(_ rowID: UUID) {
        guard canRemoveMemberRow else { return }
        memberRows.removeAll { $0.id == rowID }
    }

    public func pickMember(_ option: HXEntityPickerOption, into rowID: UUID) {
        guard let index = memberRows.firstIndex(where: { $0.id == rowID }) else { return }
        memberRows[index].agentID = option.id
        memberRows[index].shownName = option.title
        memberRows[index].storedReferenceBroken = false
    }

    public func setDelegation(_ text: String, of rowID: UUID) {
        guard let index = memberRows.firstIndex(where: { $0.id == rowID }) else { return }
        memberRows[index].delegationDescription = text
    }

    /// The text a row shows under its agent: what this team asks of the member, or nothing. The member's own
    /// description is the server's fallback and is not the form's to invent (`TeamServiceImpl.kt:301`-`:304`).
    public func memberName(for rowID: UUID) -> String {
        guard let row = memberRows.first(where: { $0.id == rowID }), let id = row.agentID else { return "" }
        return hxPresented(row.shownName) ?? hx("team.binding.memberFallback", Int(id))
    }

    /// Arranges one member against the others.
    ///
    /// The order is a fact the server keeps and the lead reads: `buildDraft` always assigns `members`, so the
    /// array goes out in the on-screen order; the binding rows are re-inserted walking that array and come back
    /// `ORDER BY id` (`TeamMemberMapper.xml:16`), which is the order the 主管 sees as its list of delegable
    /// members. No endpoint is involved, and `delegationDescription` rides on the row it belongs to — a value
    /// type addressed by its own `id`, so a move cannot mix one member's text into another's.
    ///
    /// Indexes follow `onMove` semantics: `to` counts the list with the moved row already taken out.
    ///
    /// The skill step gets no handles on purpose. Its no-send rule compares the *set* (`skillIDsIfChanged`,
    /// 注意点 11), so a pure reorder of those rows would be arranged on screen, judged unchanged, and silently
    /// undone on the next read — and turning the comparison ordered would put a stored-but-broken skill back on
    /// the wire, where the whole-replacement resolver refuses it (`SkillBindingResolver.kt:43`-`:100`). A row
    /// that cannot be made to stick is not offered.
    public func moveMemberRow(from source: Int, to destination: Int) {
        Self.move(&memberRows, from: source, to: destination)
    }

    /// Out-of-range ends and self-moves do nothing rather than crash: the handles are laid out from an index
    /// the view read a frame ago.
    private static func move<T>(_ items: inout [T], from source: Int, to destination: Int) {
        guard items.indices.contains(source), items.indices.contains(destination), source != destination else {
            return
        }
        items.insert(items.remove(at: source), at: destination)
    }

    // MARK: - Validation

    /// The rules of one step, as the console splits them: step 1 checks the five basic fields, step 2 checks
    /// the skills, and step 3 — the one that carries the 保存 button — checks everything
    /// (`TeamWizard.tsx:225`-`:240`: `handleNext` at step 0 validates `BASIC_FIELDS`, at step 1 runs
    /// `showSkillIssue()`, at step 2 calls `handleFinish`, which runs the skill check, the whole form and the
    /// member count).
    public func validate(_ step: TeamWizardStep) -> [TeamWizardIssue] {
        switch step {
        case .basic: return validateBasics()
        case .skill: return validateSkills()
        case .member: return validateAll()
        }
    }

    /// Everything, in the order `handleFinish` reaches it: skills first (`TeamWizard.tsx:175`-`:178`), then
    /// the four basic fields (`:179`-`:188`, whose refusal is what `BASIC_FIELDS` jumps back to step 1 for),
    /// then the members (`:192`-`:198`). The order is not cosmetic — it decides which step a save jumps to.
    public func validateAll() -> [TeamWizardIssue] {
        validateSkills() + validateBasics() + validateMembers()
    }

    private func validateBasics() -> [TeamWizardIssue] {
        var issues: [TeamWizardIssue] = []
        if name.trimmed == nil {
            issues.append(issue(.name, "team.wizard.error.name"))
        } else if name.trimmedUTF16Count > Self.nameLimit {
            // `TeamCreateRequest.kt:17`-`:20`: `@NotBlank` plus `@Size(min = 1, max = 100)`. `@Size` counts
            // UTF-16 code units, which a grapheme count understates for anything outside the basic plane. A
            // duplicate name inside the tenant is the server's check (`TeamServiceImpl.kt:71`-`:76`) and so
            // surfaces as a failed write instead.
            issues.append(issue(.name, "team.wizard.error.name.long"))
        }
        if detail.trimmed == nil {
            // `@NotBlank` at `TeamCreateRequest.kt:22`-`:24` — unlike the agent form, whose `@NotNull` would
            // accept an empty string. A team without a description is a team nobody can find again anyway.
            issues.append(issue(.detail, "team.wizard.error.detail"))
        }
        if systemPrompt.trimmed == nil {
            // `@NotBlank` at `TeamCreateRequest.kt:26`-`:28`.
            issues.append(issue(.prompt, "team.wizard.error.prompt"))
        }
        if modelID == nil {
            // `@NotNull` at `TeamCreateRequest.kt:30`-`:32`, and `requireUsableModel` is stricter still
            // (`TeamServiceImpl.kt:242`-`:256`): the model must exist, be the tenant's own and shared with
            // the caller, be enabled and be a chat model. The candidate page is what keeps the first two from being a surprise, the
            // server's sentence is what keeps the last two honest.
            issues.append(issue(.model, "team.wizard.error.model"))
        }
        return issues
    }

    /// The same validator as the agent wizard's step 4, because the team wizard renders the same panel and
    /// calls the same function (`TeamWizard.tsx:12`, `:166`-`:171` → `findSkillIssue`).
    private func validateSkills() -> [TeamWizardIssue] {
        var issues: [TeamWizardIssue] = []
        var seenIDs = Set<Int64>()
        var seenNames = Set<String>()
        for row in skillRows where !row.isEmpty {
            guard let skillID = row.skillID else {
                // A repository picked and no skill chosen: the row is half-made and `findSkillIssue` returns
                // on it (`configValidation.ts:141`).
                issues.append(issue(.skill, "team.wizard.error.skill"))
                continue
            }
            if !seenIDs.insert(skillID).inserted {
                issues.append(issue(.skill, "team.wizard.error.skill.duplicate"))
                continue
            }
            // Same name, different id: the resolver refuses the pair outright
            // (`SkillBindingResolver.kt:63`-`:70`), so the form pre-checks it (注意点 10).
            if let skillName = hxPresented(row.skillName), !seenNames.insert(skillName.lowercased()).inserted {
                issues.append(issue(.skill, hx("team.wizard.error.skill.nameTaken", skillName)))
            }
        }
        return issues
    }

    /// Step 3's own rules: at least one member, no agent twice, delegation text within the cap
    /// (`MembersField.tsx:102`-`:168`; `TeamServiceImpl.kt:276`-`:289`).
    private func validateMembers() -> [TeamWizardIssue] {
        var issues: [TeamWizardIssue] = []
        guard !memberRows.isEmpty else {
            // The one refusal the console raises itself rather than through the form: an empty member list
            // jumps back to step 3 and toasts 「至少需要一个成员」 (`TeamWizard.tsx:192`-`:198`), and the
            // backend says the same (`TeamServiceImpl.kt:276`-`:278`, `TeamCreateRequest.kt:37`-`:40`).
            return [issue(.member, "team.wizard.error.members.empty")]
        }
        var seen = Set<Int64>()
        for row in memberRows {
            guard let agentID = row.agentID else {
                // `agentId` is required on every row, with no condition on it — the blank row the form seeds
                // is refused just like a half-filled one (`MembersField.tsx:106`-`:113`, `:279`).
                issues.append(issue(.member, "team.wizard.error.member"))
                continue
            }
            if !seen.insert(agentID).inserted {
                issues.append(issue(.member, "team.wizard.error.member.duplicate"))
            }
            if row.delegationDescription.utf16.count > Self.delegationLimit {
                // The form's own `max: 500` (`MembersField.tsx:150`-`:157`), which mirrors `@Size(max = 500)`
                // on `TeamCreateRequest.kt:57`-`:59`.
                issues.append(issue(.delegation, "team.wizard.error.delegation.long"))
            }
        }
        return issues
    }

    private func issue(_ field: TeamWizardField, _ key: String) -> TeamWizardIssue {
        TeamWizardIssue(field: field, step: stepOf(field), message: hx(key))
    }

    private func issue(_ field: TeamWizardField, _ key: String, _ arg: String) -> TeamWizardIssue {
        TeamWizardIssue(field: field, step: stepOf(field), message: hx(key, arg))
    }

    /// Which step an issue belongs to — and therefore which step a save walks the operator back to. Members
    /// belong to step 3 and never to step 1, which is the difference between this wizard and one that read
    /// 「the form is broken」 as 「start again」 (`TeamWizard.tsx:184`-`:186`, where only `BASIC_FIELDS` send
    /// the operator back to step 0).
    private func stepOf(_ field: TeamWizardField) -> TeamWizardStep {
        switch field {
        case .name, .detail, .prompt, .model: return .basic
        case .skill: return .skill
        case .member, .delegation: return .member
        }
    }

    // MARK: - Submit

    /// Save. The last step re-checks everything first (`TeamWizard.tsx:225`-`:240` → `handleFinish`), and a
    /// refusal moves the wizard to the step that owns the first issue rather than leaving it where the tap
    /// happened.
    public func save() async -> TeamWizardResult {
        guard !isSaving else {
            return .invalid(issues: [], step: step)
        }
        let issues = validateAll()
        if !issues.isEmpty {
            let first = issues.first?.step ?? .basic
            step = first
            return .invalid(issues: issues, step: first)
        }
        let draft = buildDraft()
        isSaving = true
        defer { isSaving = false }
        if let editingID {
            switch await writer.updateTeam(id: editingID, draft) {
            case .success:
                return .updated(id: editingID, name: draft.name ?? "")
            case let .failure(error):
                return .failed(message: ErrorMessage.text(for: error))
            }
        }
        switch await writer.createTeam(draft) {
        case .success:
            return .created
        case let .failure(error):
            return .failed(message: ErrorMessage.text(for: error))
        }
    }

    /// The request body. `TeamWizard.tsx:189`-`:216` plus the two routes it feeds
    /// (`team/index.tsx:368` adds `status: 1` on the create leg alone; `:393` sends the update leg as built).
    ///
    /// Two nulls carry meaning here and are not this form's to smooth over: `skillIDs` is sent only when the
    /// sorted set really changed (`skillIDsIfChanged`), and a blank delegation is sent as *absent* so the
    /// backend falls back to the member's own description (`TeamServiceImpl.kt:298`-`:304`). There is no
    /// `owner` to send at all — the writer struct has no such field, because `createTeam` stamps the calling
    /// account itself (`TeamServiceImpl.kt:88`).
    public func buildDraft() -> TeamSaveDraft {
        var draft = TeamSaveDraft()
        draft.name = name.trimmed
        draft.description = detail.trimmed
        draft.systemPrompt = systemPrompt
        draft.modelId = modelID
        draft.isPublic = isPublic.hxInt
        if isCreate { draft.status = 1 }
        draft.skillIDs = skillIDsIfChanged
        draft.members = memberRows.compactMap { row -> TeamMemberDraft? in
            // A row with no agent cannot become a member: `TeamMemberDraft.agentId` is not optional, which is
            // the same fact the form's required rule states. The validation pass refuses that row before a
            // save ever reaches here, so dropping it is belt-and-braces rather than a behaviour.
            guard let agentID = row.agentID else { return nil }
            return TeamMemberDraft(agentId: agentID, delegationDescription: row.delegationDescription.trimmed)
        }
        return draft
    }

    /// 集合语义: 「技能集合没动就不发这个字段」 (`TeamWizard.tsx:199`-`:210`).
    ///
    /// Compared as a sorted set, so moving rows around is not a change — the bindings are not ordered and a
    /// reorder that sent `skillIds` would hand a broken stored skill straight to the whole-replacement guard
    /// that refuses it (`TeamServiceImpl.kt:126`, 注意点 11). A create always sends, empty included, and an
    /// edit that really did empty the set sends `[]`, which means 「give the lead no skill」 — the one thing
    /// `nil` explicitly is not (`TeamUpdateRequest.kt:10`-`:13`).
    private var skillIDsIfChanged: [Int64]? {
        let selected = skillRows.compactMap(\.skillID)
        guard !isCreate else { return selected }
        return selected.sorted() == storedSkillIDs.sorted() ? nil : selected
    }

    /// The panel that offers to push the new configuration into the team's running sessions. Only an update
    /// reaches it: a create has no session yet, and `team/index.tsx:394`-`:399` opens it on the update leg
    /// alone.
    public func makeRefreshTarget(id: Int64, name: String) -> SessionRefreshTarget {
        let catalog = teams
        return SessionRefreshTarget(id: id, name: name, source: .team) {
            await catalog.teamRelatedSessions(id: id)
        }
    }

    // MARK: - Wiring the pieces together

    /// Initial values, straight off the list row for an edit. A create starts the way the console's `Form`
    /// does: one blank skill row and one blank member row (`TeamWizard.tsx:78`, `:87`, `:279`).
    private func seed() {
        guard case let .edit(row) = mode else {
            skillRows = [TeamSkillRow()]
            memberRows = [TeamMemberRow()]
            return
        }
        name = hxPresented(row.name) ?? ""
        detail = hxPresented(row.description) ?? ""
        systemPrompt = row.systemPrompt
        // `modelId` is a non-null `Long` defaulting to `0`, which is this DTO's 「no lead model」 rather than a
        // model called zero (`TeamResponse.kt:24`-`:25`, `TeamSummary.leadModelName`).
        modelID = row.modelId == 0 ? nil : row.modelId
        isPublic = row.isPublic == 1
        skillRows = row.skillList.map { skill in
            TeamSkillRow(
                repositoryID: skill.repositoryId,
                repositoryName: hxPresented(skill.repositoryName),
                skillID: skill.skillId,
                skillName: skill.displayName ?? "#\(skill.skillId)",
                storedReferenceBroken: skill.isUnavailable
            )
        }
        if skillRows.isEmpty { skillRows = [TeamSkillRow()] }
        memberRows = row.memberList.map { member in
            TeamMemberRow(
                agentID: member.agentId,
                shownName: member.displayName ?? "#\(member.agentId)",
                delegationDescription: member.delegationDescription ?? "",
                // Two flags say it, and the console reads both: a member whose agent row is gone *or* whose
                // agent has been switched off (`MembersField.tsx:69`-`:70`).
                storedReferenceBroken: member.isUnavailable || (member.agentStatus.map { $0 != 1 } ?? false)
            )
        }
        if memberRows.isEmpty { memberRows = [TeamMemberRow()] }
    }

    /// The one option line the console prints for a model, kept whole:
    /// `{modelName} - {providerName || 未知供应商} ¥{price || 0}/M` (`TeamWizard.tsx:51`-`:53`, the same line
    /// the agent wizard prints at `CreateForm.tsx:252`).
    private func modelText(_ model: ModelSummary) -> String {
        let base = model.technicalName ?? model.title ?? "\(model.id ?? 0)"
        let provider = model.providerTitle ?? hx("team.wizard.model.unknownProvider")
        let price = hx("model.chip.price", Self.priceText(model.price ?? 0))
        return "\(base) - \(provider) \(price)"
    }

    /// Prices are per-million floats on the wire; the console prints the number as it came.
    private static func priceText(_ price: Double) -> String {
        price == price.rounded() ? String(Int(price)) : String(format: "%g", price)
    }

    /// The backend's own cap on `name` (`TeamCreateRequest.kt:17`-`:20`).
    public static let nameLimit = 100
    /// The description `TextArea`'s `maxLength` — an input cap rather than a validation rule, which is why
    /// nothing refuses a longer value: the console cannot produce one (`TeamWizard.tsx:329`). A view that
    /// cannot cap a `TextEditor` may read this and stop typing at it.
    public static let detailLimit = 500
    /// The cap on one member's delegation text, in both the form and the DTO
    /// (`MembersField.tsx:153`, `:166`; `TeamCreateRequest.kt:57`-`:59`).
    public static let delegationLimit = 500
    /// The seed page the team list opens with (`team/index.tsx:78`).
    public static let memberSeedPageSize = 200
    /// The page a member search re-reads (`MembersField.tsx:43`).
    public static let memberSearchPageSize = 50
    /// The search's debounce, for the view that owns it (`MembersField.tsx:50`).
    public static let searchDebounceMilliseconds = 300
}

private extension String {
    /// Whitespace-only text reads as nothing typed, which is what `@NotBlank` means server-side and what
    /// `hxPresented` already means for a wire value.
    var trimmed: String? {
        let stripped = trimmingCharacters(in: .whitespacesAndNewlines)
        return stripped.isEmpty ? nil : stripped
    }

    var trimmedUTF16Count: Int { trimmingCharacters(in: .whitespacesAndNewlines).utf16.count }
}
