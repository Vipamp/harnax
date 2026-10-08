import SwiftUI
import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// The five steps of the agent wizard, in the order the console walks them.
///
/// Mirror: `harnax-webui/src/pages/agent/components/CreateForm.tsx:226`-`:232`; the update form is step for
/// step identical (`UpdateForm.tsx:330`-`:336`), which is why one view model serves both modes.
public enum AgentWizardStep: Int, CaseIterable, Identifiable, Sendable {
    case basic
    case tool
    case mcp
    case skill
    case cli

    public var id: Int { rawValue }

    public var titleKey: String {
        switch self {
        case .basic: return "agent.wizard.step.basic"
        case .tool: return "agent.wizard.step.tool"
        case .mcp: return "agent.wizard.step.mcp"
        case .skill: return "agent.wizard.step.skill"
        case .cli: return "agent.wizard.step.cli"
        }
    }

    /// Steps 2–5 may be skipped, step 1 holds the four required fields and may not
    /// (`specs/01-agent-team.md:64`-`:111`).
    public var isSkippable: Bool { self != .basic }
    public var isLast: Bool { self == .cli }
    public var following: AgentWizardStep? { AgentWizardStep(rawValue: rawValue + 1) }
    public var preceding: AgentWizardStep? { rawValue > 0 ? AgentWizardStep(rawValue: rawValue - 1) : nil }
}

/// Which control an issue belongs to. The view uses it to focus the offending field once the step refuses.
public enum AgentWizardField: String, Equatable, Sendable {
    case name
    case detail
    case prompt
    case model
    case tool
    case mcp
    case skill
    case cli
}

/// One refusal. `message` is already rendered copy, so the view can list issues from several steps without
/// knowing which catalogue each came from.
public struct AgentWizardIssue: Equatable, Sendable {
    public let field: AgentWizardField
    public let step: AgentWizardStep
    public let message: String

    public init(field: AgentWizardField, step: AgentWizardStep, message: String) {
        self.field = field
        self.step = step
        self.message = message
    }
}

/// What a save attempt ended in.
public enum AgentWizardResult: Equatable, Sendable {
    case created
    /// The id and the name the row now has: the view needs both to offer 「刷新受影响会话」, and only an
    /// update offers it (`specs/01-agent-team.md:263`-`:275`).
    case updated(id: Int64, name: String)
    case invalid(issues: [AgentWizardIssue], step: AgentWizardStep)
    case failed(message: String)
}

/// One tool row of step 2 (`ToolConfigState`, `harnax-webui/src/pages/agent/components/ToolConfigPanel.tsx:7`-`:13`).
///
/// A row is a slot rather than a binding: the tool may still be unchosen, and an unchosen row that carries
/// environment values is the exact state the validator refuses.
public struct AgentToolRow: Identifiable, Equatable, Sendable {
    public let id: UUID
    public var toolID: Int64?
    /// The name the row shows. Resolved once at pick time, because the naming rule is language dependent
    /// (`ToolSummary.title(chinese:)`) and a row must keep its label even if the candidate list moves on.
    public var shownName: String?
    public var needConfirm: Bool
    public var env: [HXEnvBindingRow]

    public init(
        id: UUID = UUID(),
        toolID: Int64? = nil,
        shownName: String? = nil,
        needConfirm: Bool = false,
        env: [HXEnvBindingRow] = []
    ) {
        self.id = id
        self.toolID = toolID
        self.shownName = shownName
        self.needConfirm = needConfirm
        self.env = env
    }

    /// Nothing chosen and nothing typed: the row is not a row yet, and the request body drops it
    /// (`CreateForm.tsx:169`).
    public var isEmpty: Bool { toolID == nil && env.isEmpty }
}

/// One MCP row of step 3 (`McpConfigState`, `harnax-webui/src/pages/agent/components/McpConfigPanel.tsx:7`-`:12`).
public struct AgentMcpRow: Identifiable, Equatable, Sendable {
    public let id: UUID
    public var mcpID: Int64?
    public var shownName: String?
    public var env: [HXEnvBindingRow]

    public init(id: UUID = UUID(), mcpID: Int64? = nil, shownName: String? = nil, env: [HXEnvBindingRow] = []) {
        self.id = id
        self.mcpID = mcpID
        self.shownName = shownName
        self.env = env
    }

    public var isEmpty: Bool { mcpID == nil && env.isEmpty }
}

/// One skill row of step 4: repository and skill, and no environment dimension at all
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:530`-`:533`).
public struct AgentSkillRow: Identifiable, Equatable, Sendable {
    public let id: UUID
    public var repositoryID: Int64?
    public var repositoryName: String?
    public var skillID: Int64?
    public var skillName: String?

    public init(
        id: UUID = UUID(),
        repositoryID: Int64? = nil,
        repositoryName: String? = nil,
        skillID: Int64? = nil,
        skillName: String? = nil
    ) {
        self.id = id
        self.repositoryID = repositoryID
        self.repositoryName = repositoryName
        self.skillID = skillID
        self.skillName = skillName
    }

    public var isEmpty: Bool { repositoryID == nil && skillID == nil }
}

/// C2 — the five-step create/edit form.
///
/// Both modes share this type because the two web components share everything but their initial values and
/// their submit call (`specs/01-agent-team.md:62`). Edit seeds from the **list row**, not from a detail
/// request: `GET /api/admin/agents/page` already fills the four binding lists per row, and the console's
/// edit dialog reads `values` off that row (`specs/01-agent-team.md:227`; `agent/index.tsx` hands the row to
/// `UpdateForm` directly). There is no detail endpoint in this path, so this type never asks for one.
///
/// The five candidate lists are read lazily, one per step, and each read goes through its own seam — a
/// wizard that could not run without every domain's client would not be testable step by step.
@MainActor
public final class AgentFormViewModel: ObservableObject {
    /// Which agent the wizard is about, and where its initial values come from.
    public enum Mode: Sendable {
        case create
        case edit(AgentSummary)

        public var isCreate: Bool {
            if case .create = self { return true }
            return false
        }
    }

    // MARK: - Step 1

    /// Current step. Writing it is how a step chip jumps about; `next()`/`back()`/`skip()` are the walk.
    @Published public var step: AgentWizardStep = .basic
    @Published public var name: String = ""
    @Published public var detail: String = ""
    @Published public var systemPrompt: String = ""
    @Published public var modelID: Int64?
    @Published public var isPublic: Bool = false
    /// The 技能自我进化 switch (`AgentSummary.skillSelfWrite`), and whether the operator has ever moved it. The
    /// second half is the load-bearing one — see `buildDraft`.
    @Published public var skillSelfWrite: Bool = false
    @Published public private(set) var hasTouchedSelfWrite = false

    // MARK: - Steps 2–5

    @Published public var toolRows: [AgentToolRow] = []
    @Published public var mcpRows: [AgentMcpRow] = []
    @Published public var skillRows: [AgentSkillRow] = []
    /// Step 5 is the only card-shaped dimension: the selection is an id array and the values live in a
    /// dictionary keyed by cli id, which is what keeps a filled row through delete-then-re-add
    /// (`harnax-webui/src/pages/agent/components/CliConfigPanel.tsx:11`, `:20`-`:27`).
    @Published public var selectedCLIIDs: [Int64] = []
    @Published public var cliEnv: [Int64: [HXEnvBindingRow]] = [:]
    /// Names for cards whose package has fallen out of the candidate page — an edit can hold one.
    @Published public var cliNames: [Int64: String] = [:]

    // MARK: - Candidates

    @Published public private(set) var modelCandidates: [ModelSummary] = []
    @Published public private(set) var toolCandidates: [ToolSummary] = []
    @Published public private(set) var mcpCandidates: [McpServerRow] = []
    @Published public private(set) var repositories: [SkillSourceSummary] = []
    /// Skills keyed by repository: step 4 reads one repository's page per repository the operator opens
    /// (`harnax-webui/src/pages/agent/components/SkillConfigPanel.tsx:116`-`:130`).
    @Published public private(set) var skillsByRepository: [Int64: [SkillItem]] = [:]
    @Published public private(set) var cliCandidates: [CliSummary] = []
    @Published public private(set) var envCandidates: [EnvVarCandidate] = []
    @Published public private(set) var isPreparing = false
    @Published public private(set) var candidateError: String?
    @Published public private(set) var isSaving = false

    // MARK: - Seams

    private let agents: any AgentCataloging
    private let writer: any AgentWriting
    private let models: any ModelCataloging
    private let tools: any ToolCataloging
    private let mcp: any McpCataloging
    private let skills: any SkillCataloging
    private let clis: any CliCataloging
    private let envVars: any EnvVarCataloging

    public let mode: Mode
    private let account: AccountSnapshot?
    private let editingID: Int64?
    /// The row's own model name, kept because the 100-row candidate page may not hold it.
    private let storedModelName: String?
    /// Steps whose candidates have been read. A step is read once, and `reload(_:)` is the escape hatch.
    private var loaded: Set<AgentWizardStep> = []
    private var envCandidatesLoaded = false

    public init(
        mode: Mode,
        account: AccountSnapshot?,
        agents: any AgentCataloging,
        writer: any AgentWriting,
        models: any ModelCataloging,
        tools: any ToolCataloging,
        mcp: any McpCataloging,
        skills: any SkillCataloging,
        clis: any CliCataloging,
        envVars: any EnvVarCataloging
    ) {
        self.mode = mode
        self.account = account
        self.agents = agents
        self.writer = writer
        self.models = models
        self.tools = tools
        self.mcp = mcp
        self.skills = skills
        self.clis = clis
        self.envVars = envVars
        if case let .edit(row) = mode {
            editingID = row.id
            storedModelName = hxPresented(row.modelName)
        } else {
            editingID = nil
            storedModelName = nil
        }
        seed()
    }

    /// Assembly from the app's dependency bag; the seams above are what tests drive directly.
    public convenience init(dependencies: HarnaxDependencies, mode: Mode, account: AccountSnapshot?) {
        self.init(
            mode: mode,
            account: account,
            agents: dependencies.agents,
            writer: dependencies.agentWrite,
            models: dependencies.models,
            tools: dependencies.tools,
            mcp: dependencies.mcp,
            skills: dependencies.skills,
            clis: dependencies.clis,
            envVars: dependencies.envVars
        )
    }

    // MARK: - Step 1 surface

    public var isCreate: Bool { mode.isCreate }

    /// The row's model when the candidate page does not hold it. The card shows the name and the wizard
    /// keeps the id: an id that has fallen out of the visible page is still a legal stored value.
    /// Use `modelDisplayName` on screen — it is this plus the degradation a stale reference owes.
    public var modelName: String {
        if let selectedModel { return selectedModel.title ?? selectedModel.technicalName ?? "" }
        return storedModelName ?? hx("agent.wizard.model.unnamed")
    }

    /// The model page has answered. Absence can only be observed against a page that arrived: before the
    /// read the wizard would be guessing that a stored id had gone away.
    @Published private(set) var modelPageHasArrived = false

    /// A stored `modelId` the candidate page does not hold — disabled, deleted or turned into a non-chat
    /// row. 注意点 12 (`specs/01-agent-team.md:338`) forbids dropping such an entry from the list, and the
    /// shape to copy is the web's retained option (`harnax-webui/src/pages/team/components/TeamWizard.tsx:49`-`:68`).
    public var isModelStale: Bool {
        modelPageHasArrived && modelID != nil && selectedModel == nil
    }

    /// The label the row and its option carry: the name, and for a reference that no longer resolves the
    /// web's own words for why it reads degraded (`pages.team.modelUnavailable`).
    public var modelDisplayName: String {
        isModelStale ? "\(modelName) · \(hx("agent.wizard.model.unavailable"))" : modelName
    }

    /// The resolved selection, or `nil` when the id does not resolve in the candidate page.
    public var selectedModel: ModelSummary? {
        guard let modelID else { return nil }
        return modelCandidates.first { $0.id == modelID }
    }

    /// Capability gates for steps 2 and 3. An unresolvable model does not lock a panel: the wizard has no
    /// evidence either way, and refusing to configure is a claim about the server's data.
    public var supportsTools: Bool { selectedModel.map { $0.supportsTool } ?? true }
    public var supportsMCP: Bool { selectedModel.map { $0.supportsMcp } ?? true }

    /// The owner line. Disabled and never submitted — the backend writes the current account itself
    /// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:124`;
    /// `specs/01-agent-team.md:72`). An edit shows the row's creator, which is also not editable.
    public var ownerName: String {
        if case let .edit(row) = mode, let creator = hxPresented(row.creator) { return creator }
        if let account { return account.nickname ?? account.username }
        return ""
    }

    /// Whether this account may touch 是否公开 at all: an administrator and any create always may, otherwise
    /// only the creator of a still-private row (`permissionUtil.ts:57`-`:75`).
    public var canChangeVisibility: Bool {
        let currentlyPublic = isCreate ? false : (mode.row?.isPublic == 1)
        return account?.canChangeVisibility(
            creator: mode.row?.creator,
            currentlyPublic: currentlyPublic,
            isCreate: isCreate
        ) ?? isCreate
    }

    /// The wizard's own title.
    public var titleKey: String { isCreate ? "agent.wizard.title.create" : "agent.wizard.title.edit" }

    // MARK: - Walking the steps

    /// The refusals still standing, keyed by the step each one belongs to.
    ///
    /// This lives here rather than in the view's `@State` because a step chip must not be able to erase it:
    /// `save()` jumps to the step that held the save open, and the old `move(to:)` wiped every notice on the
    /// way, so the sentence explaining the jump died the moment the operator tapped anything. A step's issues
    /// are retired by the next validation pass over that step, not by the walk.
    @Published private(set) var outstandingIssues: [AgentWizardStep: [AgentWizardIssue]] = [:]

    /// What `step` last refused. Empty for a step never checked, and for one whose last pass came out clean.
    /// `AgentFormView` reads this for the banner and for the chip tone, so a step with something outstanding
    /// cannot read as finished.
    public func issues(for step: AgentWizardStep) -> [AgentWizardIssue] {
        outstandingIssues[step] ?? []
    }

    /// Validate the current step and, when it passes, move on. The returned issues are the ones that held
    /// the step closed, which is what the view lists.
    @discardableResult
    public func next() -> [AgentWizardIssue] {
        let found = validate(step)
        record(found, covering: stepsCovered(by: step))
        guard found.isEmpty, let following = step.following else { return found }
        step = following
        return []
    }

    /// Skip the step without validating it. Only steps 2–5 offer this, and skipping leaves whatever the step
    /// refused standing: nothing has been re-checked.
    public func skip() {
        guard step.isSkippable, let following = step.following else { return }
        step = following
    }

    public func back() {
        guard let preceding = step.preceding else { return }
        step = preceding
    }

    /// Under the step *each issue names*, not the step the check ran on: the last step validates all five
    /// kinds at once, and its messages belong to the steps they came from.
    private func record(_ found: [AgentWizardIssue], covering steps: [AgentWizardStep]) {
        var grouped: [AgentWizardStep: [AgentWizardIssue]] = [:]
        for issue in found {
            grouped[issue.step, default: []].append(issue)
        }
        for target in steps {
            outstandingIssues[target] = grouped[target]
        }
    }

    /// The steps one pass over `step` really covered. Steps 1–4 check their own kind; step 5 re-checks the
    /// whole form before submitting, so a clean pass there retires every message on screen.
    private func stepsCovered(by step: AgentWizardStep) -> [AgentWizardStep] {
        step == .cli ? AgentWizardStep.allCases : [step]
    }

    // MARK: - Candidates, read on demand

    /// Read the candidates a step needs. Called by the view whenever `step` changes, and a no-op for a step
    /// already read so that walking back and forth does not re-hit the endpoints.
    public func load(_ step: AgentWizardStep) async {
        guard !loaded.contains(step) else { return }
        isPreparing = true
        candidateError = nil
        defer {
            isPreparing = false
            loaded.insert(step)
        }
        switch step {
        case .basic:
            await loadModels()
        case .tool:
            await loadTools()
        case .mcp:
            await loadMCPServers()
        case .skill:
            await loadRepositories()
        case .cli:
            await loadCLIs()
        }
    }

    /// Force a step's read again — the retry affordance behind a failed candidate list.
    public func reload(_ step: AgentWizardStep) async {
        loaded.remove(step)
        await load(step)
    }

    public func loadModels() async {
        // The console asks for a page of 100 and then keeps `status===1 && modelType==='chat'`
        // (`CreateForm.tsx:101`-`:107`); the same filter applies here.
        switch await models.modelChoices(num: 1, size: 100) {
        case let .success(page):
            modelCandidates = page.records.filter { $0.status == 1 && $0.modelType == "chat" }
            // Only an answered page entitles the wizard to call a stored id stale.
            modelPageHasArrived = true
        case let .failure(error):
            candidateError = ErrorMessage.text(for: error)
        }
    }

    public func loadTools() async {
        // `GET /tools/available` is `status=1 AND active=1 AND is_required=0` server-side, so the mandatory
        // tools the runtime injects anyway are not even offered (`AgentToolController.kt:54`-`:65`).
        let fetched = await tools.availableTools()
        await loadEnvCandidates()
        switch fetched {
        case let .success(rows):
            toolCandidates = rows
            reconcileTools()
        case let .failure(error):
            candidateError = ErrorMessage.text(for: error)
        }
    }

    public func loadMCPServers() async {
        let fetched = await mcp.mcpPage(keyword: nil, status: 1, type: nil, num: 1, size: 100)
        await loadEnvCandidates()
        switch fetched {
        case let .success(page):
            mcpCandidates = page.records
            reconcileMCPs()
        case let .failure(error):
            candidateError = ErrorMessage.text(for: error)
        }
    }

    public func loadRepositories() async {
        let fetched = await skills.sourcePage(name: nil, sourceType: nil, status: 1, num: 1, size: 100)
        switch fetched {
        case let .success(page):
            // The built-in CLI repository is dropped from the page's candidates and refused again by the
            // backend (`SkillBindingResolver.kt:55`-`:60`).
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

    public func loadCLIs() async {
        let fetched = await clis.cliPage(name: nil, status: 1, num: 1, size: 100)
        await loadEnvCandidates()
        switch fetched {
        case let .success(page):
            cliCandidates = page.records
            for cli in page.records {
                guard let id = cli.id else { continue }
                if let title = cli.title { cliNames[id] = title }
                guard cliEnv[id] == nil else { continue }
                cliEnv[id] = Self.blankEnv(for: cli.envParamEntries)
            }
            reconcileCLIs()
        case let .failure(error):
            candidateError = ErrorMessage.text(for: error)
        }
    }

    /// Variable candidates, read once. An empty list must not delete a stored reference: rows keep their own
    /// `referenceName`, and the editor shows `env.binding.unresolved` for an id the list does not hold.
    public func loadEnvCandidates() async {
        guard !envCandidatesLoaded else { return }
        if case let .success(rows) = await envVars.envVarCandidates() {
            envCandidates = rows
            envCandidatesLoaded = true
        }
    }

    // MARK: - Options

    public var modelOptions: [HXEntityPickerOption] {
        var options = modelCandidates.compactMap { model -> HXEntityPickerOption? in
            guard let id = model.id else { return nil }
            return HXEntityPickerOption(id: id, title: modelText(model), subtitle: nil)
        }
        guard let modelID, isModelStale else { return options }
        // The web puts the retained row on top too, but `disabled` (`TeamWizard.tsx:60`-`:66`). iOS keeps it
        // selectable: 注意点 12 asks for the degraded style, not for an entry that cannot be looked at, and
        // an operator who came to check what the agent holds must be able to leave it exactly as it was.
        options.insert(HXEntityPickerOption(id: modelID, title: modelDisplayName, subtitle: nil), at: 0)
        return options
    }

    public func toolOptions(excluding taken: Set<Int64>) -> [HXEntityPickerOption] {
        toolCandidates.compactMap { tool in
            guard let id = tool.id, !taken.contains(id) else { return nil }
            return HXEntityPickerOption(
                id: id,
                title: tool.title(chinese: prefersChinese) ?? Self.toolLabel(for: tool, id: id),
                subtitle: tool.details
            )
        }
    }

    public func mcpOptions(excluding taken: Set<Int64>) -> [HXEntityPickerOption] {
        mcpCandidates.compactMap { server in
            guard let id = server.id, !taken.contains(id) else { return nil }
            return HXEntityPickerOption(
                id: id,
                title: server.title ?? hx("mcp.unnamed"),
                subtitle: hxPresented(server.description)
            )
        }
    }

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
                title: skill.title ?? hx("skill.table.unnamed"),
                subtitle: hxPresented(skill.description)
            )
        }
    }

    public func cliOptions(excluding taken: Set<Int64>) -> [HXEntityPickerOption] {
        cliCandidates.compactMap { cli in
            guard let id = cli.id, !taken.contains(id) else { return nil }
            let version = hxPresented(cli.version)
            return HXEntityPickerOption(
                id: id,
                title: cli.title ?? "\(id)",
                // The web option is three lines: name, version, description. The picker has two, so the
                // version rides with the name and the description takes the second line.
                subtitle: [version, hxPresented(cli.description)].compactMap { $0 }.joined(separator: " · ")
            )
        }
    }

    // MARK: - Exclusions

    /// Ids another row already holds. The backend de-duplicates after ordering
    /// (`AgentServiceImpl.kt:432`, `:469`, `:594`), so a second row with the same id silently loses its
    /// values — hiding the choice is the only client-side defence (`注意点 4`).
    public func excludedToolIDs(except rowID: UUID) -> Set<Int64> {
        Set(toolRows.filter { $0.id != rowID }.compactMap(\.toolID))
    }

    public func excludedMCPIDs(except rowID: UUID) -> Set<Int64> {
        Set(mcpRows.filter { $0.id != rowID }.compactMap(\.mcpID))
    }

    public func excludedSkillIDs(except rowID: UUID) -> Set<Int64> {
        Set(skillRows.filter { $0.id != rowID }.compactMap(\.skillID))
    }

    public var excludedCLIIDs: Set<Int64> { Set(selectedCLIIDs) }

    // MARK: - Step 1 edits

    public func pickModel(_ option: HXEntityPickerOption) {
        modelID = option.id
    }

    /// Taking the model back off the row: the web's `Select` is `allowClear` in both forms
    /// (`CreateForm.tsx:249`, `UpdateForm.tsx:354`; `specs/01-agent-team.md:73`), and the field is required,
    /// so the clear is what lets an operator start the choice over instead of being stuck with it.
    public func clearModel() {
        modelID = nil
    }

    /// The 是否公开 switch, refused to an account that may not change it.
    public func setIsPublic(_ value: Bool) {
        guard canChangeVisibility else { return }
        isPublic = value
    }

    /// The 技能自我进化 switch. Turning it off is as much a decision as turning it on, so the two are told apart
    /// by the touch rather than by the value — see `buildDraft`.
    public func setSkillSelfWrite(_ value: Bool) {
        hasTouchedSelfWrite = true
        skillSelfWrite = value
    }

    // MARK: - Step 2 edits

    public func addToolRow() {
        toolRows.append(AgentToolRow())
    }

    public func removeToolRow(_ rowID: UUID) {
        toolRows.removeAll { $0.id == rowID }
    }

    public func pickTool(_ option: HXEntityPickerOption, into rowID: UUID) {
        guard let index = toolRows.firstIndex(where: { $0.id == rowID }) else { return }
        let candidate = toolCandidates.first { $0.id == option.id }
        toolRows[index].toolID = option.id
        toolRows[index].shownName = option.title
        if let candidate {
            toolRows[index].needConfirm = candidate.requiresConfirmation
            // Non-secret rows take the package default; a secret row stays blank because the server only
            // ever prints a mask for it (`ToolConfigPanel.tsx:58`-`:64`).
            toolRows[index].env = Self.presetEnv(for: candidate.entries, presetsDefaults: true)
        } else {
            toolRows[index].env = []
        }
    }

    /// The switch may only be tightened, never loosened: a tool that declares confirmation keeps it
    /// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:425`-`:427`).
    public func confirmIsLocked(for rowID: UUID) -> Bool {
        guard let row = toolRows.first(where: { $0.id == rowID }), let id = row.toolID else { return false }
        return toolCandidates.first { $0.id == id }?.requiresConfirmation ?? false
    }

    /// Tools are the only dimension whose single row can be deleted — down to none (`specs/01-agent-team.md:175`).
    public func setToolConfirm(_ value: Bool, of rowID: UUID) {
        guard let index = toolRows.firstIndex(where: { $0.id == rowID }) else { return }
        if !value, confirmIsLocked(for: rowID) { return }
        toolRows[index].needConfirm = value
    }

    /// A binding into one row's parameter table, so `HXEnvBindingEditor` owns the editing and this type only
    /// owns where the rows live.
    public func toolEnvBinding(for rowID: UUID) -> Binding<[HXEnvBindingRow]> {
        Binding(
            get: { self.toolRows.first { $0.id == rowID }?.env ?? [] },
            set: { newValue in
                guard let index = self.toolRows.firstIndex(where: { $0.id == rowID }) else { return }
                self.toolRows[index].env = newValue
            }
        )
    }

    // MARK: - Step 3 edits

    public func addMcpRow() {
        mcpRows.append(AgentMcpRow())
    }

    /// The last MCP row cannot be deleted (`McpConfigPanel.tsx:98`-`:102`).
    public var canRemoveMcpRow: Bool { mcpRows.count > 1 }

    public func removeMcpRow(_ rowID: UUID) {
        guard canRemoveMcpRow else { return }
        mcpRows.removeAll { $0.id == rowID }
    }

    /// Clearing the pick (`allowClear` on the web's server `Select`, `McpConfigPanel.tsx:107`). This is the
    /// way out of a row the trash will not take: `canRemoveMcpRow` keeps refusing at one row, correctly,
    /// because the step must always hold a place to pick into (`McpConfigPanel.tsx:64`-`:69`).
    ///
    /// The whole row is zeroed, parameter table included, matching `pickRepository(nil)` for skills
    /// (`AgentFormViewModel.swift:743`-`:748`). The web leaves `envEntries` behind after a clear, which
    /// strands a table whose typed values belong to no server.
    public func clearMCP(into rowID: UUID) {
        guard let index = mcpRows.firstIndex(where: { $0.id == rowID }) else { return }
        mcpRows[index] = AgentMcpRow(id: rowID)
    }

    public func pickMCP(_ option: HXEntityPickerOption, into rowID: UUID) {
        guard let index = mcpRows.firstIndex(where: { $0.id == rowID }) else { return }
        mcpRows[index].mcpID = option.id
        mcpRows[index].shownName = option.title
        // An MCP pick never backfills the package defaults: they would freeze the "follow the server spec"
        // value into the agent snapshot (`McpConfigPanel.tsx:43`-`:48`).
        let declarations = mcpCandidates.first { $0.id == option.id }?.envEntries ?? []
        mcpRows[index].env = Self.blankEnv(for: declarations)
    }

    public func mcpEnvBinding(for rowID: UUID) -> Binding<[HXEnvBindingRow]> {
        Binding(
            get: { self.mcpRows.first { $0.id == rowID }?.env ?? [] },
            set: { newValue in
                guard let index = self.mcpRows.firstIndex(where: { $0.id == rowID }) else { return }
                self.mcpRows[index].env = newValue
            }
        )
    }

    // MARK: - Step 4 edits

    public func addSkillRow() {
        skillRows.append(AgentSkillRow())
    }

    /// The last row is cleared in place, any other one is deleted (`SkillConfigPanel.tsx:76`-`:84`).
    public func removeSkillRow(_ rowID: UUID) {
        guard let index = skillRows.firstIndex(where: { $0.id == rowID }) else { return }
        if skillRows.count == 1 {
            skillRows[index] = AgentSkillRow(id: rowID)
            return
        }
        skillRows.remove(at: index)
    }

    /// Clearing the repository zeroes the whole row (`specs/01-agent-team.md:106`).
    public func pickRepository(_ option: HXEntityPickerOption?, into rowID: UUID) async {
        guard let index = skillRows.firstIndex(where: { $0.id == rowID }) else { return }
        guard let option else {
            skillRows[index] = AgentSkillRow(id: rowID)
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
        guard let repositoryID = skillRows[index].repositoryID else {
            skillRows[index].skillID = option.id
            skillRows[index].skillName = option.title
            return
        }
        let item = skillsByRepository[repositoryID]?.first { $0.id == option.id }
        skillRows[index].skillID = option.id
        skillRows[index].skillName = item?.title ?? option.title
    }

    public func clearSkill(_ rowID: UUID) {
        guard let index = skillRows.firstIndex(where: { $0.id == rowID }) else { return }
        skillRows[index].skillID = nil
        skillRows[index].skillName = nil
    }

    // MARK: - Step 5 edits

    /// 「添加 CLI」 puts the first unselected package on a new card, and is disabled once every package has
    /// one (`CliConfigPanel.tsx:150`-`:177`).
    public var canAddCLI: Bool { !remainingCLIIDs.isEmpty }

    public var remainingCLIIDs: [Int64] {
        cliCandidates.compactMap { cli in
            guard let id = cli.id, !selectedCLIIDs.contains(id) else { return nil }
            return id
        }
    }

    public func addCLI() {
        guard let next = remainingCLIIDs.first else { return }
        selectedCLIIDs.append(next)
        if cliEnv[next] == nil {
            cliEnv[next] = Self.blankEnv(for: cliCandidates.first { $0.id == next }?.envParamEntries ?? [])
        }
    }

    /// Deleting a card keeps its values in `cliEnv`: the dictionary is keyed by package id, which is exactly
    /// why delete-then-re-add restores them (`CliConfigPanel.tsx:11`).
    public func removeCLI(at index: Int) {
        guard selectedCLIIDs.indices.contains(index) else { return }
        selectedCLIIDs.remove(at: index)
    }

    /// Re-picking a card. The previous package keeps its own dictionary entry.
    public func pickCLI(_ option: HXEntityPickerOption, at index: Int) {
        guard selectedCLIIDs.indices.contains(index), selectedCLIIDs[index] != option.id else { return }
        selectedCLIIDs[index] = option.id
        cliNames[option.id] = option.title
        if cliEnv[option.id] == nil {
            cliEnv[option.id] = Self.blankEnv(for: cliCandidates.first { $0.id == option.id }?.envParamEntries ?? [])
        }
    }

    public func cliName(for id: Int64) -> String {
        cliNames[id] ?? hx("agent.wizard.cli.unnamed", Int(id))
    }

    public func shippedSkillName(for id: Int64) -> String? {
        hxPresented(cliCandidates.first { $0.id == id }?.shippedSkillName)
    }

    public func cliEnvBinding(for id: Int64) -> Binding<[HXEnvBindingRow]> {
        Binding(
            get: { self.cliEnv[id] ?? [] },
            set: { newValue in self.cliEnv[id] = newValue }
        )
    }

    // MARK: - Reorder

    /// Moves a tool card, parameters included.
    ///
    /// The row is a value type, so its `env` table rides along — a card that owns `KEY=value` still owns it
    /// after the move. Indexes follow `onMove` semantics: `to` counts the list with the moved row already
    /// taken out, which is what SwiftUI's `List` transfer gesture hands the callback.
    ///
    /// Nothing new has to be saved: the array order *is* the stored order, because the insert loop walks the
    /// request list in order and every binding table is re-created from scratch (`AgentServiceImpl.kt:400`-`:404`,
    /// `:439`-`:442`, `:528`-`:535`, `:557`-`:560` with `deleteByAgentId` at `:249`-`:252`), while reads come back
    /// `ORDER BY id` (`AgentToolBindingMapper.xml`, `AgentMcpBindingMapper.xml`, `AgentSkillBindingMapper.xml`,
    /// `AgentCliBindingMapper.xml`). So `buildDraft` sending the array in the on-screen order is the whole
    /// feature — see `specs/01-agent-team.md` 注意点 6.
    public func moveToolRow(from source: Int, to destination: Int) {
        Self.move(&toolRows, from: source, to: destination)
    }

    public func moveMcpRow(from source: Int, to destination: Int) {
        Self.move(&mcpRows, from: source, to: destination)
    }

    public func moveSkillRow(from source: Int, to destination: Int) {
        Self.move(&skillRows, from: source, to: destination)
    }

    /// Moves a CLI card. The parameter tables live in `cliEnv`, keyed by package id, so the dictionary needs
    /// no touch: after the move the same id still resolves to the same table, and `buildDraft` pairs them by
    /// walking `selectedCLIIDs` in order (`AgentFormViewModel.swift:1043`).
    public func moveCLI(from source: Int, to destination: Int) {
        Self.move(&selectedCLIIDs, from: source, to: destination)
    }

    /// One implementation of the four moves. Out-of-range ends and self-moves are refused instead of
    /// crashing, because the row handles are laid out from an index the view computed a moment earlier and a
    /// stale frame must degrade into "nothing happened".
    private static func move<T>(_ items: inout [T], from source: Int, to destination: Int) {
        guard items.indices.contains(source), items.indices.contains(destination), source != destination else {
            return
        }
        items.insert(items.remove(at: source), at: destination)
    }

    // MARK: - Validation

    /// The rules of one step, as the web form splits them: step 1 checks the four basic fields, steps 2–4
    /// check their own kind, and step 5 checks all four before submitting
    /// (`CreateForm.tsx:147`-`:152`; `configValidation.ts`).
    public func validate(_ step: AgentWizardStep) -> [AgentWizardIssue] {
        switch step {
        case .basic: return validateBasics()
        case .tool: return validateTools()
        case .mcp: return validateMCPs()
        case .skill: return validateSkills()
        case .cli: return validateBasics() + validateTools() + validateMCPs() + validateSkills() + validateCLIs()
        }
    }

    public func validateAll() -> [AgentWizardIssue] {
        validateBasics() + validateTools() + validateMCPs() + validateSkills() + validateCLIs()
    }

    private func validateBasics() -> [AgentWizardIssue] {
        var issues: [AgentWizardIssue] = []
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            issues.append(issue(.name, "agent.wizard.error.name"))
        } else if trimmed.utf16.count > Self.nameLimit {
            // The console only checks non-empty; the backend's `@Size(max = 100)` is what the length rule
            // comes from (`AgentCreateRequest.kt:13`-`:16`). `@Size` counts UTF-16 code units, which grapheme
            // counting understates for anything outside the basic plane. A duplicate name in the tenant is the
            // server's check to make (`AgentServiceImpl.kt:114`-`:116`), so it surfaces as a failed save instead.
            issues.append(issue(.name, "agent.wizard.error.name.long"))
        }
        if detail.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            // `@NotNull` would accept an empty string, but the console refuses one and a description-less
            // card is a card the operator cannot find again (`specs/01-agent-team.md:69`).
            issues.append(issue(.detail, "agent.wizard.error.detail"))
        }
        if systemPrompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            issues.append(issue(.prompt, "agent.wizard.error.prompt"))
        }
        if modelID == nil {
            issues.append(issue(.model, "agent.wizard.error.model"))
        }
        return issues
    }

    private func validateTools() -> [AgentWizardIssue] {
        var issues: [AgentWizardIssue] = []
        var seen = Set<Int64>()
        for row in toolRows where !row.isEmpty || row.toolID != nil {
            guard let toolID = row.toolID else {
                issues.append(issue(.tool, "agent.wizard.error.tool"))
                continue
            }
            if !seen.insert(toolID).inserted {
                issues.append(issue(.tool, "agent.wizard.error.tool.duplicate"))
                continue
            }
            if let key = missingRequiredEnv(row.env, defaultValueCounts: false) {
                issues.append(issue(.tool, hx("agent.wizard.error.env.missing", row.shownName ?? Self.toolIDLabel(toolID), key)))
            }
        }
        return issues
    }

    private func validateMCPs() -> [AgentWizardIssue] {
        var issues: [AgentWizardIssue] = []
        var seen = Set<Int64>()
        for row in mcpRows where !row.isEmpty || row.mcpID != nil {
            guard let mcpID = row.mcpID else {
                issues.append(issue(.mcp, "agent.wizard.error.mcp"))
                continue
            }
            if !seen.insert(mcpID).inserted {
                issues.append(issue(.mcp, "agent.wizard.error.mcp.duplicate"))
                continue
            }
            if let key = missingRequiredEnv(row.env, defaultValueCounts: true) {
                issues.append(issue(.mcp, hx("agent.wizard.error.env.missing", row.shownName ?? hx("mcp.unnamed"), key)))
            }
        }
        return issues
    }

    private func validateSkills() -> [AgentWizardIssue] {
        var issues: [AgentWizardIssue] = []
        var seenIDs = Set<Int64>()
        var seenNames = Set<String>()
        for row in skillRows where !row.isEmpty {
            guard let skillID = row.skillID else {
                issues.append(issue(.skill, "agent.wizard.error.skill"))
                continue
            }
            if !seenIDs.insert(skillID).inserted {
                issues.append(issue(.skill, "agent.wizard.error.skill.duplicate"))
                continue
            }
            // Same name, different id: the backend refuses the pair (`SkillBindingResolver.kt:63`-`:68`),
            // so the form pre-checks it (`注意点 10`).
            if let skillName = hxPresented(row.skillName), !seenNames.insert(skillName.lowercased()).inserted {
                issues.append(issue(.skill, hx("agent.wizard.error.skill.nameTaken", skillName)))
            }
        }
        return issues
    }

    /// A required parameter of a selected package. CLI keeps the package default in the sandbox, so a declared
    /// default counts as filled and only a param with neither a value nor a default is missing
    /// (`configValidation.ts:150`-`:160`).
    private func validateCLIs() -> [AgentWizardIssue] {
        var issues: [AgentWizardIssue] = []
        for id in selectedCLIIDs {
            if let key = missingRequiredEnv(cliEnv[id] ?? [], defaultValueCounts: true) {
                issues.append(issue(.cli, "agent.wizard.error.env.missing", cliName(for: id), key))
            }
        }
        return issues
    }

    /// A required parameter with nothing in it.
    ///
    /// `defaultValueCounts` is the asymmetry the three tables keep: a tool's declared default is not treated
    /// as filled, an MCP's or a CLI's is (`configValidation.ts:120`, `:130`, `:156`; `envBinding.ts:21`-`:35`).
    private func missingRequiredEnv(_ rows: [HXEnvBindingRow], defaultValueCounts: Bool) -> String? {
        for row in rows where row.isRequired {
            if row.isFilled { continue }
            if defaultValueCounts, row.defaultValue != nil { continue }
            return row.envKey
        }
        return nil
    }

    private func issue(_ field: AgentWizardField, _ key: String) -> AgentWizardIssue {
        AgentWizardIssue(field: field, step: stepOf(field), message: hx(key))
    }

    private func issue(_ field: AgentWizardField, _ key: String, _ arg: String, _ second: String) -> AgentWizardIssue {
        AgentWizardIssue(field: field, step: stepOf(field), message: hx(key, arg, second))
    }

    private func issue(_ field: AgentWizardField, _ key: String, _ arg: String) -> AgentWizardIssue {
        AgentWizardIssue(field: field, step: stepOf(field), message: hx(key, arg))
    }

    private func stepOf(_ field: AgentWizardField) -> AgentWizardStep {
        switch field {
        case .name, .detail, .prompt, .model: return .basic
        case .tool: return .tool
        case .mcp: return .mcp
        case .skill: return .skill
        case .cli: return .cli
        }
    }

    // MARK: - Submit

    /// Save. The last step re-checks all four kinds first, and a failure of the whole read is reported as
    /// one line rather than as a stack trace (`specs/01-agent-team.md:127`).
    public func save() async -> AgentWizardResult {
        guard !isSaving else {
            // The re-entrant guard: a second tap while one save is in flight. It carries no issue because
            // nothing was re-checked, and `AgentFormView` says so rather than rendering an empty list.
            return .invalid(issues: [], step: step)
        }
        let issues = validateAll()
        // Recorded before the jump, so every step the whole-form check refused still has its own sentence
        // when the operator walks back over it.
        record(issues, covering: AgentWizardStep.allCases)
        if !issues.isEmpty {
            let first = issues.first?.step ?? .basic
            step = first
            return .invalid(issues: issues, step: first)
        }
        let draft = buildDraft()
        isSaving = true
        defer { isSaving = false }
        if let editingID {
            switch await writer.updateAgent(id: editingID, draft) {
            case .success:
                return .updated(id: editingID, name: draft.name ?? "")
            case let .failure(error):
                return .failed(message: ErrorMessage.text(for: error))
            }
        }
        switch await writer.createAgent(draft) {
        case .success:
            return .created
        case let .failure(error):
            return .failed(message: ErrorMessage.text(for: error))
        }
    }

    /// The request body. The shape follows `CreateForm.tsx:146`-`:190`: `owner` is absent, create carries
    /// `status: 1`, edit carries no `status`, and skills always go out as a list so that an emptied step 4
    /// really does clear them.
    public func buildDraft() -> AgentSaveDraft {
        var draft = AgentSaveDraft()
        draft.name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        draft.description = detail.trimmingCharacters(in: .whitespacesAndNewlines)
        draft.systemPrompt = systemPrompt
        draft.modelId = modelID
        draft.isPublic = isPublic.hxInt
        // Absent means「不动」and only means that: the update route keeps the stored value when the key is
        // missing (`AgentServiceImpl.kt:180`), so a form nobody touched must not send the value it merely
        // read. Turning the switch off is sent explicitly as `0`, because that *is* a decision.
        if hasTouchedSelfWrite {
            draft.skillSelfWrite = skillSelfWrite.hxInt
        }
        if isCreate { draft.status = 1 }
        draft.tools = toolRows.compactMap { row -> AgentToolDraft? in
            guard let toolID = row.toolID else { return nil }
            // Tools and MCPs send every declared parameter, blank included (`customValue: ""`), which is the
            // asymmetry the spec leaves as-is (`specs/01-agent-team.md:210`).
            return AgentToolDraft(id: toolID, needConfirm: row.needConfirm, envBindings: row.env.map { $0.draft })
        }
        draft.mcps = mcpRows.compactMap { row -> AgentMcpDraft? in
            guard let mcpID = row.mcpID else { return nil }
            return AgentMcpDraft(id: mcpID, envBindings: row.env.map { $0.draft })
        }
        draft.skillIDs = skillRows.compactMap(\.skillID)
        draft.clis = selectedCLIIDs.map { id in
            // CLI is the exception: only filled rows go out, because a blank one would hold the snapshot key
            // that means "follow the package default" (`CliConfigPanel.tsx:59`-`:72`).
            let bindings = (cliEnv[id] ?? []).filter(\.isFilled).map { $0.draft }
            return AgentCliDraft(id: id, envBindings: bindings)
        }
        return draft
    }

    /// The panel that offers to push the new configuration into the sessions holding this agent. Only an
    /// update reaches it — a create has no session yet.
    public func makeRefreshTarget(id: Int64, name: String) -> SessionRefreshTarget {
        let catalog = agents
        return SessionRefreshTarget(id: id, name: name, source: .agent) {
            await catalog.relatedSessions(id: id)
        }
    }

    // MARK: - Wiring the pieces together

    /// Initial values, straight off the list row for an edit.
    private func seed() {
        guard case let .edit(row) = mode else { return }
        name = hxPresented(row.name) ?? ""
        detail = hxPresented(row.description) ?? ""
        systemPrompt = row.systemPrompt ?? ""
        modelID = row.modelId
        isPublic = row.isPublic == 1
        skillSelfWrite = row.isSelfWriting
        toolRows = (row.toolList ?? []).map { binding in
            AgentToolRow(
                toolID: binding.toolId,
                shownName: binding.name(chinese: prefersChinese) ?? binding.toolId.map { Self.toolIDLabel($0) },
                needConfirm: binding.requiresConfirmation,
                env: Self.seedEnv(binding.envBindings)
            )
        }
        if toolRows.isEmpty { toolRows = [AgentToolRow()] }
        mcpRows = (row.mcpList ?? []).map { binding in
            AgentMcpRow(
                mcpID: binding.mcpId,
                shownName: binding.name ?? hx("mcp.unnamed"),
                env: Self.seedEnv(binding.envBindings)
            )
        }
        if mcpRows.isEmpty { mcpRows = [AgentMcpRow()] }
        skillRows = (row.skillList ?? []).map { binding in
            AgentSkillRow(
                repositoryID: binding.repositoryId,
                repositoryName: binding.repository,
                skillID: binding.skillId,
                skillName: binding.name
            )
        }
        if skillRows.isEmpty { skillRows = [AgentSkillRow()] }
        for binding in row.cliList ?? [] {
            guard let id = binding.cliId else { continue }
            selectedCLIIDs.append(id)
            if let name = binding.name { cliNames[id] = name }
            cliEnv[id] = Self.seedEnv(binding.envBindings)
        }
    }

    /// A stored row whose declaration has not arrived yet: the parameter's name is all the row needs to stay
    /// sendable, and the two flags stay off so nothing is refused or masked without evidence.
    private static func seedEnv(_ bindings: [AgentEnvBinding]?) -> [HXEnvBindingRow] {
        (bindings ?? []).map { binding in
            HXEnvBindingRow(
                entry: EnvParamEntry(envParamName: binding.envKey),
                binding: binding
            )
        }
    }

    private static func blankEnv(for declarations: [EnvParamEntry]) -> [HXEnvBindingRow] {
        declarations.map { HXEnvBindingRow(entry: $0) }
    }

    private static func presetEnv(for declarations: [EnvParamEntry], presetsDefaults: Bool) -> [HXEnvBindingRow] {
        declarations.map { entry in
            let preset = presetsDefaults && !entry.secret ? hxPresented(entry.defaultValue) ?? "" : ""
            return HXEnvBindingRow(
                entry: entry,
                source: preset.isEmpty ? .reference : .custom,
                customValue: preset
            )
        }
    }

    /// Fill in the declarations the entities have added since a row was seeded, keeping every value the
    /// operator had already chosen (`UpdateForm.tsx:154`-`:179`).
    private static func mergedEnv(
        existing: [HXEnvBindingRow],
        declarations: [EnvParamEntry]
    ) -> [HXEnvBindingRow] {
        var merged = existing.map { row in
            guard let match = declarations.first(where: { $0.envParamName == row.envKey }) else { return row }
            return HXEnvBindingRow(
                entry: match,
                source: row.source,
                envVarID: row.envVarID,
                referenceName: row.referenceName,
                customValue: row.customValue
            )
        }
        for declaration in declarations where !existing.contains(where: { $0.envKey == declaration.envParamName }) {
            merged.append(HXEnvBindingRow(entry: declaration))
        }
        return merged
    }

    /// The three reconciliations: an edit seeded rows from stored bindings, and the entity's own
    /// declarations can only be merged in once the candidate page has arrived.
    private func reconcileTools() {
        toolRows = toolRows.map { row in
            guard let id = row.toolID,
                  let candidate = toolCandidates.first(where: { $0.id == id }) else { return row }
            var updated = row
            updated.shownName = row.shownName ?? candidate.title(chinese: prefersChinese) ?? Self.toolLabel(for: candidate, id: id)
            updated.env = Self.mergedEnv(existing: row.env, declarations: candidate.entries)
            return updated
        }
    }

    private func reconcileMCPs() {
        mcpRows = mcpRows.map { row in
            guard let id = row.mcpID,
                  let candidate = mcpCandidates.first(where: { $0.id == id }) else { return row }
            var updated = row
            updated.shownName = row.shownName ?? candidate.title ?? hx("mcp.unnamed")
            updated.env = Self.mergedEnv(existing: row.env, declarations: candidate.envEntries)
            return updated
        }
    }

    private func reconcileCLIs() {
        for id in selectedCLIIDs {
            guard let candidate = cliCandidates.first(where: { $0.id == id }) else { continue }
            cliEnv[id] = Self.mergedEnv(existing: cliEnv[id] ?? [], declarations: candidate.envParamEntries)
        }
    }

    /// The one option line the console prints for a model, kept whole: `{modelName} - {provider} ¥{price}/M`
    /// (`CreateForm.tsx:252`).
    private func modelText(_ model: ModelSummary) -> String {
        let base = model.technicalName ?? model.title ?? "\(model.id ?? 0)"
        let provider = model.providerTitle ?? hx("agent.wizard.model.unknownProvider")
        let price = hx("model.chip.price", Self.priceText(model.price ?? 0))
        return "\(base) - \(provider) \(price)"
    }

    /// Prices are per-million floats on the wire; the console prints the number as it came.
    private static func priceText(_ price: Double) -> String {
        price == price.rounded() ? String(Int(price)) : String(format: "%g", price)
    }

    private static func toolLabel(for tool: ToolSummary, id: Int64) -> String {
        tool.codeName ?? toolIDLabel(id)
    }

    private static func toolIDLabel(_ id: Int64) -> String { hx("agent.binding.toolFallback", Int(id)) }

    private var prefersChinese: Bool { HarnaxCatalog.shared.language.prefersChinese }

    /// The backend's own cap on `name` (`AgentCreateRequest.kt:13`-`:16`).
    public static let nameLimit = 100
}

private extension AgentFormViewModel.Mode {
    /// The row an edit was seeded from; `nil` for a create.
    var row: AgentSummary? {
        if case let .edit(row) = self { return row }
        return nil
    }
}
