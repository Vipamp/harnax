import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// The detail sheet's panels, in the presenter's own words: which sections appear, how each value is
/// shaped, and what a row that carries nothing leaves out. None of it needs a view.
final class SessionDetailViewModelTests: XCTestCase {
    private let basic = "chat.detail.section.basic"
    private let executor = "chat.detail.section.executor"
    private let teamLead = "chat.detail.section.teamLead"
    private let tools = "chat.detail.section.tools"
    private let skills = "chat.detail.section.skills"
    private let cli = "chat.detail.section.cli"
    private let members = "chat.detail.section.members"
    private let mcp = "chat.detail.section.mcp"

    private func panels(
        _ session: SessionSummary,
        bindings: SessionExecutorBindings? = nil,
        expanded: Bool = false,
        chinese: Bool = false
    ) -> [SessionDetailSection] {
        SessionDetailPresenter.sections(
            for: session, bindings: bindings, isPromptExpanded: expanded, chinese: chinese
        )
    }

    private func panel(
        _ session: SessionSummary,
        _ titleKey: String,
        bindings: SessionExecutorBindings? = nil,
        expanded: Bool = false,
        chinese: Bool = false
    ) throws -> SessionDetailSection {
        let sections = panels(session, bindings: bindings, expanded: expanded, chinese: chinese)
        return try XCTUnwrap(
            sections.first { $0.titleKey == titleKey },
            "no panel \(titleKey) in \(sections.map(\.titleKey))"
        )
    }

    private func field(_ session: SessionDetailSection, _ labelKey: String) -> SessionDetailRow? {
        session.rows.first { $0.labelKey == labelKey }
    }

    private func tryField(_ session: SessionDetailSection, _ labelKey: String) throws -> SessionDetailRow {
        try XCTUnwrap(field(session, labelKey), "no \(labelKey) in \(session.rows.map { $0.labelKey })")
    }

    // MARK: - a panel with nothing in it is not a panel

    func testOnlyTheTitleMakesOnePanelAndNoOther() throws {
        let session = SessionSummary.stub(
            title: "只有标题",
            sessionDescription: nil,
            sessionId: nil,
            name: nil,
            description: nil,
            systemPrompt: nil,
            modelName: nil,
            modelPrice: nil,
            owner: nil,
            creator: nil,
            createTime: nil
        )
        let sections = panels(session)
        XCTAssertEqual(sections.map(\.titleKey), [basic])
        XCTAssertEqual(sections.first?.rows.map(\.value), ["只有标题"])
    }

    @MainActor
    func testARowCarryingNothingAtAllOpensNoPanel() {
        let vm = SessionDetailViewModel(session: SessionSummary())
        XCTAssertTrue(vm.hasNothingToShow)
        XCTAssertTrue(vm.sections.isEmpty)
    }

    func testANamelessExecutorLeavesThePanelOutButAModelAloneStillFillsIt() throws {
        let bare = SessionSummary.stub(name: nil, description: nil, systemPrompt: nil)
        XCTAssertTrue(panels(bare).contains { $0.titleKey == executor })
        XCTAssertNil(field(try panel(bare, executor), "chat.detail.field.agentName"))

        let nameOnly = SessionSummary.stub(description: nil, systemPrompt: nil, modelName: nil, modelPrice: nil)
        let section = try panel(nameOnly, executor)
        XCTAssertEqual(section.rows.count, 1)
        XCTAssertEqual(field(section, "chat.detail.field.agentName")?.value, "估值助手")
    }

    func testBindingPanelsAppearOnlyWhenTheListHasAnEntry() throws {
        let withSkill = SessionSummary.stub(skillList: [SessionSkillItem.stub()])
        XCTAssertEqual(panels(withSkill).map(\.titleKey).last, skills)

        let withServer = SessionSummary.stub(mcpList: [SessionMcpItem.stub()])
        XCTAssertEqual(panels(withServer).map(\.titleKey).last, mcp)

        let nameless = SessionSummary.stub(skillList: [SessionSkillItem.stub(repositoryName: nil, skillName: nil, skillDescription: nil)])
        XCTAssertFalse(panels(nameless).contains { $0.titleKey == skills })
    }

    func testThePanelsKeepTheConsolesOrder() throws {
        let session = SessionSummary.stub(
            mcpList: [SessionMcpItem.stub()],
            skillList: [SessionSkillItem.stub()],
            isPublic: 1
        )
        XCTAssertEqual(panels(session).map(\.titleKey), [basic, executor, mcp, skills])

        // The console's own order is basic, agent, tools, mcp, skill, cli, members
        // (`DetailModal.tsx:192-561`), so an agent row puts the MCP panel before the skill panel — the two
        // binding tails it can reach through `agentId` sit between them.
        let agent = try ExecutorRows.agent(
            mcp: [ExecutorRows.mcp()], skills: [ExecutorRows.agentSkill()],
            tools: [ExecutorRows.tool()], cli: [ExecutorRows.cli()]
        )
        XCTAssertEqual(
            panels(session, bindings: .agent(agent)).map(\.titleKey),
            [basic, executor, tools, mcp, skills, cli]
        )

        // A team row owns the lead's skills and its members, and binds nothing else
        // (`DetailModal.tsx:114-128` clears the three agent-side lists).
        let teamSession = SessionSummary.stub(agentId: nil, teamId: 12)
        let team = try ExecutorRows.team(
            skills: [ExecutorRows.teamSkill()], members: [ExecutorRows.member()]
        )
        XCTAssertEqual(
            panels(teamSession, bindings: .team(team)).map(\.titleKey),
            [basic, teamLead, skills, members]
        )
    }

    // MARK: - price (`¥{n}/M`)

    func testThePriceNumberKeepsWholeUnitsWhole() throws {
        XCTAssertEqual(SessionDetailPresenter.priceNumber(2.5), "2.5")
        XCTAssertEqual(SessionDetailPresenter.priceNumber(2.0), "2")
        XCTAssertEqual(SessionDetailPresenter.priceNumber(0.0020), "0.002")
        XCTAssertEqual(SessionDetailPresenter.priceNumber(1_234), "1234")
        XCTAssertEqual(SessionDetailPresenter.priceNumber(0), "0")
    }

    func testAPriceTheRowDoesNotCarriesNoLineAndNoNaN() {
        XCTAssertNil(SessionDetailPresenter.priceNumber(nil))
        XCTAssertNil(SessionDetailPresenter.priceText(nil))
        XCTAssertNil(SessionDetailPresenter.priceNumber(.nan))
        XCTAssertNil(SessionDetailPresenter.priceNumber(.infinity))
    }

    func testThePriceRowIsTheFormattedValueNotTheRawNumber() throws {
        let section = try panel(SessionSummary.stub(), executor)
        let price = try tryField(section, "chat.detail.field.modelPrice")
        XCTAssertEqual(price.value, hx("chat.detail.price", "2.5"))
        XCTAssertNotEqual(price.value, "2.5", "the row handed the bare number to the view")
    }

    func testAMissingPriceDropsItsRowAndLeavesTheModelOne() throws {
        let section = try panel(SessionSummary.stub(modelPrice: nil), executor)
        XCTAssertNil(field(section, "chat.detail.field.modelPrice"))
        XCTAssertNotNil(field(section, "chat.detail.field.model"))
    }

    // MARK: - the system prompt

    func testAPromptLongerThanFourLinesCollapsesToFourAndSaysItCanExpand() throws {
        let section = try panel(SessionSummary.stub(), executor)
        let prompt = try tryField(section, "chat.detail.field.systemPrompt")
        XCTAssertEqual(prompt.layout, .paragraph(expandable: true))
        let lines = try XCTUnwrap(prompt.value).components(separatedBy: "\n")
        XCTAssertEqual(lines.count, SessionDetailPresenter.promptLineLimit)
        XCTAssertTrue(try XCTUnwrap(prompt.value).hasSuffix("…"))
        XCTAssertEqual(lines.first, "你是估值助手。")
    }

    func testExpandingHandsBackTheWholePrompt() throws {
        let full = try XCTUnwrap(SessionSummary.stub().systemPrompt)
        XCTAssertTrue(SessionDetailPresenter.promptNeedsExpansion(full))
        XCTAssertEqual(SessionDetailPresenter.promptText(full, expanded: true), full)
        XCTAssertNotEqual(SessionDetailPresenter.promptText(full, expanded: false), full)
    }

    @MainActor
    func testTheViewModelOwnsTheExpandStateAndTogglesIt() throws {
        let vm = SessionDetailViewModel(session: SessionSummary.stub())
        XCTAssertFalse(vm.isPromptExpanded)
        let collapsed = try XCTUnwrap(vm.sections.first { $0.titleKey == executor })
        let prompt = try tryField(collapsed, "chat.detail.field.systemPrompt")
        XCTAssertTrue(try XCTUnwrap(prompt.value).hasSuffix("…"))

        vm.togglePrompt()
        XCTAssertTrue(vm.isPromptExpanded)
        let open = try XCTUnwrap(vm.sections.first { $0.titleKey == executor })
        XCTAssertEqual(try tryField(open, "chat.detail.field.systemPrompt").value, vm.session.systemPrompt)
    }

    func testAShortPromptIsShownWholeWithNoExpandControl() throws {
        let section = try panel(SessionSummary.stub(systemPrompt: "只引用给出的报表。"), executor)
        let prompt = try tryField(section, "chat.detail.field.systemPrompt")
        XCTAssertEqual(prompt.value, "只引用给出的报表。")
        XCTAssertEqual(prompt.layout, .paragraph(expandable: false))
    }

    func testABlankPromptLeavesTheLineOut() throws {
        let section = try panel(SessionSummary.stub(systemPrompt: "   \n  "), executor)
        XCTAssertNil(field(section, "chat.detail.field.systemPrompt"))
        XCTAssertFalse(SessionDetailPresenter.promptNeedsExpansion("   "))
    }

    // MARK: - the 失效 badge

    func testTheUnavailableBadgeAppearsOnlyWhenTheTeamRowSaysSo() throws {
        // No repository column on these fixtures: the marks array then says exactly what the flag said.
        let session = SessionSummary.stub(agentId: nil, teamId: 12)
        let dead = try panel(
            session, skills,
            bindings: .team(ExecutorRows.team(skills: [ExecutorRows.teamSkill(repository: nil, available: false)]))
        )
        XCTAssertEqual(
            dead.rows.first?.marks,
            [.badge(key: "chat.detail.badge.unavailable", tone: .danger)]
        )

        let live = try panel(
            session, skills,
            bindings: .team(ExecutorRows.team(skills: [ExecutorRows.teamSkill(repository: nil)]))
        )
        XCTAssertTrue(try XCTUnwrap(live.rows.first).marks.isEmpty)
    }

    func testNeitherTheAgentRowNorTheSnapshotClaimsASkillIsGone() throws {
        // `AgentResponse.SkillItem` has no availability column at all (`AgentResponse.kt:106-121`), and a
        // deleted skill never reached the conversation's own copy (`SessionServiceImpl.kt:145`). The badge is
        // therefore the team row's alone, and both other sources must render the same skill unflagged.
        let agent = try panel(
            SessionSummary.stub(), skills,
            bindings: .agent(ExecutorRows.agent(skills: [ExecutorRows.agentSkill(repository: nil)]))
        )
        XCTAssertTrue(try XCTUnwrap(agent.rows.first).marks.isEmpty)

        let snapshot = try panel(SessionSummary.stub(skillList: [SessionSkillItem.stub(repositoryName: nil)]), skills)
        XCTAssertTrue(try XCTUnwrap(snapshot.rows.first).marks.isEmpty)
    }

    func testTheTeamRowOwnsTheSkillPanelOnceItIsIn() throws {
        // The conversation's copy is dropped as soon as the authoritative row arrives, not merged with it: the
        // two lists disagree precisely when a binding has been deleted, and then the by-id row is the true one.
        let session = SessionSummary.stub(
            agentId: nil, teamId: 12, skillList: [SessionSkillItem.stub(skillName: "旧快照里的技能")]
        )
        let section = try panel(
            session, skills,
            bindings: .team(ExecutorRows.team(skills: [ExecutorRows.teamSkill(skillName: "公告解析")]))
        )
        XCTAssertEqual(section.rows.map(\.value), ["公告解析"])
    }

    func testASkillMarksItsSourceRepositoryBesideItsName() throws {
        let section = try panel(SessionSummary.stub(skillList: [SessionSkillItem.stub()]), skills)
        let row = try XCTUnwrap(section.rows.first)
        XCTAssertNil(row.labelKey, "an entry is named by the server, not by catalogue copy")
        XCTAssertEqual(row.value, "公告解析")
        XCTAssertEqual(row.detail, "从公告里抽估值口径")
        XCTAssertEqual(row.marks, [.chip(text: "qoder-skills", tone: nil)])
    }

    func testANamelessSkillKeepsItsDescriptionAsItsTitle() throws {
        let section = try panel(SessionSummary.stub(skillList: [
            SessionSkillItem.stub(skillName: "  ", skillDescription: "估值口径对照"),
        ]), skills)
        XCTAssertEqual(section.rows.count, 1)
        XCTAssertEqual(try XCTUnwrap(section.rows.first).value, "估值口径对照")
    }

    // MARK: - team rows and agent rows

    func testATeamRowNamesItsPanelForTheLeadAndStillRenders() throws {
        let team = SessionSummary.stub(agentId: nil, teamId: 5)
        let sections = panels(team)
        XCTAssertTrue(sections.contains { $0.titleKey == teamLead })
        XCTAssertFalse(sections.contains { $0.titleKey == executor })
        let section = try XCTUnwrap(sections.first { $0.titleKey == teamLead })
        XCTAssertEqual(try tryField(section, "chat.detail.field.leadName").value, "估值助手")
    }

    func testAnAgentRowNamesItsPanelForTheAgent() throws {
        let section = try panel(SessionSummary.stub(), executor)
        XCTAssertEqual(try tryField(section, "chat.detail.field.agentName").value, "估值助手")
    }

    func testARowWithNeitherAgentNorTeamStillRendersEverythingItHas() throws {
        let orphan = SessionSummary.stub(agentId: nil, teamId: nil, name: nil)
        let sections = panels(orphan)
        XCTAssertTrue(sections.contains { $0.titleKey == basic })
        let executorPanel = try XCTUnwrap(sections.first { $0.titleKey == executor })
        XCTAssertNil(field(executorPanel, "chat.detail.field.agentName"))
        XCTAssertNotNil(field(executorPanel, "chat.detail.field.model"))
    }

    func testNoRowEverywhereOnTheseRowsIsNilShapedOrPlaceholderCopy() throws {
        let rows = [
            SessionSummary.stub(),
            SessionSummary.stub(agentId: nil, teamId: 5),
            SessionSummary.stub(agentId: nil, teamId: nil, name: nil, description: nil, modelPrice: nil),
            SessionSummary.stub(title: nil, sessionDescription: nil, owner: nil, creator: nil, createTime: nil),
        ]
        for session in rows {
            for section in panels(session) {
                for row in section.rows {
                    let texts = [row.value, row.detail].compactMap { $0 }
                    for text in texts {
                        XCTAssertFalse(text.isEmpty, "\(section.titleKey) has a blank value")
                        // Exact equality, not a substring test: a real session id and a real timestamp are
                        // full of hyphens, and a row showing "web-2f6c-8842" is the opposite of a placeholder.
                        for placeholder in ["-", "--", "None", "Unknown", "null", "nil"] {
                            XCTAssertNotEqual(text, placeholder)
                        }
                        XCTAssertFalse(text.contains("Optional"), "\(section.titleKey) leaked an Optional")
                        XCTAssertFalse(text.contains("nil"), "\(section.titleKey) leaked a nil")
                    }
                    for mark in row.marks {
                        if case let .chip(text, _) = mark {
                            XCTAssertFalse(text.isEmpty, "\(section.titleKey) has an empty chip")
                        }
                    }
                }
            }
        }
    }

    // MARK: - the shared badge

    func testTheSharedBadgeFollowsThePublicFlag() throws {
        let open = try panel(SessionSummary.stub(isPublic: 1), basic)
        let visibility = try tryField(open, "chat.detail.field.visibility")
        XCTAssertNil(visibility.value, "the flag is a badge, not a sentence")
        XCTAssertEqual(visibility.marks, [.badge(key: "state.badge.shared", tone: .teal)])

        for closed in [0, nil] {
            let section = try panel(SessionSummary.stub(isPublic: closed), basic)
            XCTAssertNil(field(section, "chat.detail.field.visibility"), "isPublic \(String(describing: closed)) claimed a badge")
        }
    }

    // MARK: - one field, one panel

    func testEveryPresentFieldReachesExactlyOnePanel() throws {
        let session = SessionSummary.stub(
            mcpList: [SessionMcpItem.stub(), SessionMcpItem.stub(mcpId: 13, mcpName: "汇率源", mcpDescription: "提供中间价")],
            skillList: [SessionSkillItem.stub(), SessionSkillItem.stub(skillId: 22, skillName: "估值表", skillDescription: "季度估值表")],
            isPublic: 1
        )
        let sections = panels(session)
        let rows = sections.flatMap(\.rows)

        let labels = rows.compactMap(\.labelKey)
        XCTAssertEqual(Set(labels).count, labels.count, "a label reached two panels")

        let values = rows.compactMap(\.value) + rows.compactMap(\.detail)
        XCTAssertEqual(Set(values).count, values.count, "a value reached two panels")

        let expected = [
            "chat.detail.field.sessionId": "web-2f6c-8842",
            "chat.detail.field.title": "季度估值复核",
            "chat.detail.field.sessionDescription": "按 9 月末报表复核三条产品线",
            "chat.detail.field.owner": "harnax-demo",
            "chat.detail.field.creator": "simon",
            "chat.detail.field.createTime": "2026-09-12 14:20:00",
            "chat.detail.field.agentName": "估值助手",
            "chat.detail.field.executorDescription": "读公告、算估值、出表格",
            "chat.detail.field.model": "gpt-4o",
        ]
        for (label, value) in expected {
            let hits = rows.filter { $0.labelKey == label }
            XCTAssertEqual(hits.count, 1, "\(label) appeared \(hits.count) times")
            XCTAssertEqual(hits.first?.value, value)
        }
        XCTAssertEqual(sections.flatMap(\.rows).filter { $0.value == "估值助手" }.count, 1)

        let skillRows = sections.filter { $0.titleKey == skills }.flatMap(\.rows).count
        XCTAssertEqual(skillRows, 2)
        XCTAssertEqual(sections.filter { $0.titleKey == mcp }.flatMap(\.rows).count, 2)
    }

    func testTheBusinessKeyIsTheCodeRowAndTheOnlyOne() throws {
        let section = try panel(SessionSummary.stub(), basic)
        let codeRows = section.rows.filter { $0.layout == .code }
        XCTAssertEqual(codeRows.count, 1)
        XCTAssertEqual(codeRows.first?.labelKey, "chat.detail.field.sessionId")
    }

    @MainActor
    func testABlankBusinessKeyLeavesTheCopyRowOut() throws {
        let section = try panel(SessionSummary.stub(sessionId: "  "), basic)
        XCTAssertNil(field(section, "chat.detail.field.sessionId"))
        XCTAssertNil(SessionDetailViewModel(session: SessionSummary.stub(sessionId: "  ")).businessKey)
        XCTAssertEqual(SessionDetailViewModel(session: SessionSummary.stub()).businessKey, "web-2f6c-8842")
    }

    @MainActor
    func testTheFooterCarriesTheUpdateTimeAndNothingElseDoes() throws {
        let vm = SessionDetailViewModel(session: SessionSummary.stub())
        XCTAssertEqual(vm.updatedLine, hx("chat.detail.updated", "2026-09-14 09:05:00"))
        for section in vm.sections {
            for row in section.rows {
                XCTAssertNotEqual(row.value, "2026-09-14 09:05:00", "updateTime also reached \(section.titleKey)")
            }
        }
        XCTAssertNil(SessionDetailViewModel(session: SessionSummary.stub(updateTime: "  ")).updatedLine)
    }
}
