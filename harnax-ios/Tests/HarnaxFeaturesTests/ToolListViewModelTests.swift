import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// The read-only tool list's behaviour: one unpaged read of the builtin table, a search and a status filter
/// applied to the rows already on screen, and what a failure may not take away.
///
/// The data source is `GET /api/admin/tools/builtin`, the console's own route
/// (`harnax-webui/src/services/ant-design-pro/tool.ts:13-16`), which is why nothing here asserts on a
/// keyword or a status going out on the wire: the search is local, and only a refresh costs a request.
///
/// There is no write coverage here on purpose — the domain has no writes
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentToolController.kt:13-15`).
@MainActor
final class ToolListViewModelTests: XCTestCase {
    // MARK: - fixtures

    /// The columns the console's own filter reads, spelled out per case so a test says what it matches on.
    private func row(
        id: Int = 1,
        name: String? = nil,
        display: String? = nil,
        displayZh: String? = nil,
        description: String? = nil,
        status: Int? = 1
    ) throws -> ToolSummary {
        var fields: [String: Any] = ["id": id]
        if let name { fields["name"] = name }
        if let display { fields["displayName"] = display }
        if let displayZh { fields["displayNameZh"] = displayZh }
        if let description { fields["description"] = description }
        if let status { fields["status"] = status }
        return try ToolSummary.stub(fields)
    }

    private func seededTools(_ rows: [[String: Any]]) throws -> [ToolSummary] {
        try rows.map { try ToolSummary.stub($0) }
    }

    /// Two ordinary rows, enough to prove that a filter narrowed the set rather than emptied it.
    private var sampleRows: [[String: Any]] {
        [
            ["id": 1, "name": "send_email", "displayName": "Send Email", "displayNameZh": "发送邮件",
             "description": "Sends a mail through the configured server", "status": 1],
            ["id": 2, "name": "read_file", "displayName": "Read File", "displayNameZh": "读取文件",
             "description": "Reads a file inside the sandbox root", "status": 1],
        ]
    }

    private func fresh() async throws -> (ToolListViewModel, FakeToolCatalog) {
        let tools = FakeToolCatalog()
        tools.builtinReplies = [.success(try seededTools(sampleRows))]
        let vm = ToolListViewModel(tools: tools)
        await vm.refresh()
        return (vm, tools)
    }

    // MARK: - the read

    func testTheBuiltinTableIsTheOneReadTheScreenMakes() async throws {
        let (vm, tools) = try await fresh()
        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertEqual(vm.total, 2, "the table's own size is the total, because nothing is left to fetch")
        XCTAssertEqual(tools.builtinCalls, 1)
        XCTAssertTrue(tools.detailRequests.isEmpty)
    }

    /// `/builtin` answers every code-registered row at once (`AgentToolMapper.xml:64-67`), so the whole
    /// table is on screen after one read — the console's table is not paginated either
    /// (`harnax-webui/src/pages/tool/index.tsx:170`).
    func testTheWholeTableLandsFromOneReadWithNoTailToFetch() async throws {
        let tools = FakeToolCatalog()
        tools.builtinReplies = [.success(try seededTools((1...40).map { ["id": $0, "name": "tool_\($0)"] }))]
        let vm = ToolListViewModel(tools: tools)
        await vm.refresh()
        XCTAssertEqual(vm.items.count, 40)
        XCTAssertEqual(tools.builtinCalls, 1, "a second read would mean the screen still thinks it is paging")
    }

    func testAnInstallationWithNoToolsIsNotASpinningWheel() async throws {
        let tools = FakeToolCatalog()
        tools.builtinReplies = [.success([])]
        let vm = ToolListViewModel(tools: tools)
        await vm.refresh()
        XCTAssertEqual(vm.phase, .empty)
        XCTAssertTrue(vm.items.isEmpty)
    }

    func testAFailedFirstReadOwnsTheScreen() async throws {
        let tools = FakeToolCatalog()
        tools.builtinReplies = [.failure(.offline)]
        let vm = ToolListViewModel(tools: tools)
        await vm.refresh()
        XCTAssertEqual(vm.phase, .failed(ErrorMessage.text(for: .offline)))
        XCTAssertNil(vm.inlineError, "there is no list behind a banner to keep")
    }

    func testAFailedRefreshLeavesTheListUpAndClearsOnceItWorks() async throws {
        let (vm, tools) = try await fresh()
        tools.builtinReplies = [.failure(.timeout)]
        await vm.refresh()
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(vm.inlineError, ErrorMessage.text(for: .timeout))

        tools.builtinReplies = [.success(try seededTools([["id": 9, "name": "only"]]))]
        await vm.refresh()
        XCTAssertNil(vm.inlineError)
        XCTAssertEqual(vm.items.count, 1)
    }

    /// Two refreshes can be on the wire at once — a pull while a filter's read has not landed. The older
    /// answer must not win the rows, which is the same identity rule the paged screens keep.
    func testASlowAnswerDoesNotOverwriteANewerOne() async throws {
        let tools = FakeToolCatalog()
        tools.builtinReplies = [.success(try seededTools([["id": 1, "name": "stale"]]))]
        tools.readGate.arm()
        let vm = ToolListViewModel(tools: tools)
        let slow = Task { await vm.refresh() }
        try await waitUntil { tools.builtinCalls == 1 }

        tools.builtinReplies = [.success(try seededTools([["id": 2, "name": "fresh"], ["id": 3, "name": "later"]]))]
        await vm.refresh()
        XCTAssertEqual(vm.items.map(\.id), [2, 3])

        tools.readGate.release()
        await slow.value
        XCTAssertEqual(vm.items.map(\.id), [2, 3], "the stale answer arrived last and lost")
        XCTAssertEqual(vm.total, 2)
    }

    /// A refresh replaces the row set rather than adding to it: the builtin table is re-read whole, so rows
    /// a boot removed must disappear.
    func testASecondRefreshReplacesRatherThanAppends() async throws {
        let (vm, tools) = try await fresh()
        tools.builtinReplies = [.success(try seededTools([["id": 1, "name": "send_email"]]))]
        await vm.refresh()
        XCTAssertEqual(vm.items.map(\.id), [1])
    }

    // MARK: - the search, which never costs a request

    /// The headline gap this screen existed to close: the paged route's `keyword` is matched by MySQL
    /// against `name`/`display_name`/`description` only (`AgentToolMapper.xml:45-57`), so the Chinese
    /// column was unreachable. The console filters in memory over four columns
    /// (`harnax-webui/src/pages/tool/index.tsx:57-68`), and 读取文件 is the word a Chinese operator types.
    func testAChineseDisplayNameMatchesWhereTheServerWouldNotHaveFoundIt() async throws {
        let (vm, tools) = try await fresh()
        vm.keyword = "读取"
        XCTAssertEqual(vm.items.map(\.id), [2])
        XCTAssertEqual(vm.total, 2, "the table is still two rows wide; only the view narrowed")
        XCTAssertEqual(tools.builtinCalls, 1, "a local search costs no read")
    }

    /// The four columns, one needle each: a row matched by nothing else still matches on its description.
    func testTheSearchCoversTheSameFourColumnsTheConsoleDoes() async throws {
        let rows = try seededTools([
            ["id": 1, "name": "alpha_tool", "displayName": "Beta", "displayNameZh": "伽马", "description": "Gamma work"],
        ])
        let tools = FakeToolCatalog()
        tools.builtinReplies = [.success(rows)]
        let vm = ToolListViewModel(tools: tools)
        await vm.refresh()
        for needle in ["alpha", "Beta", "伽马", "gamma wo"] {
            vm.keyword = needle
            XCTAssertEqual(vm.items.map(\.id), [1], "\(needle) should match")
        }
        vm.keyword = "delta"
        XCTAssertTrue(vm.items.isEmpty, "a word in none of the four columns matches nothing")
    }

    /// `toLowerCase()` on both sides is the console's case rule, and Swift's `lowercased()` is
    /// locale-independent in the same way.
    func testTheSearchIgnoresCaseOnBothSides() async throws {
        let (vm, _) = try await fresh()
        vm.keyword = "READ_FILE"
        XCTAssertEqual(vm.items.map(\.id), [2])
        vm.keyword = "读取文件"
        XCTAssertEqual(vm.items.map(\.id), [2])
    }

    /// The console tests the trimmed keyword for emptiness but lowercases the *typed* string
    /// (`tool/index.tsx:59-60`), so padding stays in the needle and a padded search legitimately misses.
    /// Copied rather than improved: two screens, one rule about what a search word is.
    func testAPaddedSearchWordIsNotTrimmedForMatching() async throws {
        let (vm, _) = try await fresh()
        vm.keyword = " 读取 "
        XCTAssertTrue(vm.items.isEmpty)
        XCTAssertTrue(vm.isFiltered, "it is still a search the user meant to filter by")
        vm.keyword = "读取"
        XCTAssertEqual(vm.items.map(\.id), [2])
    }

    /// `item.name &&` in the console means an absent or empty column cannot match — an empty needle is the
    /// only thing that does, and that case is the unfiltered table.
    func testAColumnThatIsNotThereCannotMatch() async throws {
        let tools = FakeToolCatalog()
        tools.builtinReplies = [.success(try seededTools([
            ["id": 1, "name": "", "displayName": "", "displayNameZh": "", "description": ""],
            ["id": 2, "name": "email_relay"],
        ]))]
        let vm = ToolListViewModel(tools: tools)
        await vm.refresh()
        vm.keyword = "@"
        XCTAssertTrue(vm.items.isEmpty)
        vm.keyword = "relay"
        XCTAssertEqual(vm.items.map(\.id), [2])
    }

    // MARK: - the filters, which are local too

    /// `status` is the raw 0/1 column; the SQL the paged route ran compared it for equality, and doing it
    /// locally keeps the same answer — including a row whose column the backend left out.
    func testTheStatusFilterSelectsTheZeroOneColumnLocally() async throws {
        let tools = FakeToolCatalog()
        tools.builtinReplies = [.success(try seededTools([
            ["id": 1, "name": "on", "status": 1],
            ["id": 2, "name": "off", "status": 0],
            ["id": 3, "name": "unset"],
        ]))]
        let vm = ToolListViewModel(tools: tools)
        await vm.refresh()
        XCTAssertEqual(vm.items.map(\.id), [1, 2, 3], "all sends no judgement at all")
        vm.filter = .enabled
        XCTAssertEqual(vm.items.map(\.id), [1], "a row with no status column is not an enabled one either")
        vm.filter = .disabled
        XCTAssertEqual(vm.items.map(\.id), [2])
        XCTAssertEqual(tools.builtinCalls, 1, "changing a filter on an already-loaded table costs no read")
    }

    func testTheSearchAndTheStatusFilterNarrowTogether() async throws {
        let (vm, _) = try await fresh()
        vm.keyword = "邮件"
        vm.filter = .disabled
        XCTAssertTrue(vm.items.isEmpty, "the only 邮件 row is enabled")
        vm.filter = .enabled
        XCTAssertEqual(vm.items.map(\.id), [1])
    }

    func testWhitespaceOnlySearchIsNotAFilter() async throws {
        let (vm, _) = try await fresh()
        vm.keyword = "   "
        XCTAssertFalse(vm.isFiltered, "spaces are not a search the user meant")
        XCTAssertEqual(vm.items.count, 2)
        vm.filter = .enabled
        XCTAssertTrue(vm.isFiltered)
    }

    /// An empty table under a filter and an installation that registers nothing are different news, and the
    /// screen picks its sentence from `isFiltered`, so the phase has to stay `.empty` for both.
    func testAFilterThatMatchesNothingLeavesAnEmptyPhaseAndSaysSo() async throws {
        let (vm, _) = try await fresh()
        vm.keyword = "nothing matches this"
        XCTAssertEqual(vm.phase, .empty)
        XCTAssertTrue(vm.isFiltered)
    }

    func testClearingTheSearchBringsTheWholeTableBack() async throws {
        let (vm, tools) = try await fresh()
        vm.keyword = "读取"
        XCTAssertEqual(vm.items.count, 1)
        vm.keyword = ""
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertFalse(vm.isFiltered)
        XCTAssertEqual(tools.builtinCalls, 1)
    }

    // MARK: - the drill-down

    /// The row the sheet opened with is the row it shows: `/builtin` carries the parameter table, so opening
    /// a sheet is not a request.
    func testOpeningADetailCostsNoRequest() async throws {
        let (vm, tools) = try await fresh()
        let model = ToolDetailModel(tools: tools, tool: vm.items[0])
        XCTAssertTrue(tools.detailRequests.isEmpty)
        XCTAssertEqual(model.tool.id, 1)
    }

    func testTheReReadRowReplacesWhatIsOnScreen() async throws {
        let (vm, tools) = try await fresh()
        tools.detailReplies = [.success(try row(id: 1, display: "改名后的工具"))]
        let model = ToolDetailModel(tools: tools, tool: vm.items[0])
        await model.reload()
        XCTAssertEqual(tools.detailRequests, [1])
        XCTAssertEqual(model.tool.displayName, "改名后的工具")
        XCTAssertNil(model.inlineError)
    }

    /// A re-read that fails is a banner over the row that was already readable.
    func testAFailedReReadKeepsTheRowItHad() async throws {
        let (vm, tools) = try await fresh()
        tools.detailReplies = [.failure(.business(code: 404, message: "tool not found"))]
        let model = ToolDetailModel(tools: tools, tool: vm.items[0])
        await model.reload()
        XCTAssertEqual(model.tool.id, 1)
        XCTAssertEqual(
            model.inlineError,
            ErrorMessage.text(for: .business(code: 404, message: "tool not found"))
        )
    }

    func testARowWithNoIdCannotBeReRead() async throws {
        let tools = FakeToolCatalog()
        tools.builtinReplies = [.success(try seededTools([["name": "无名", "displayName": "无名"]]))]
        let vm = ToolListViewModel(tools: tools)
        await vm.refresh()
        let model = ToolDetailModel(tools: tools, tool: vm.items[0])
        XCTAssertFalse(model.canReload)
        await model.reload()
        XCTAssertTrue(tools.detailRequests.isEmpty)
    }

    // MARK: - what the drill-down says

    func testTheParameterRowsComeWithTheirFlagsAndDefault() throws {
        let tool = try ToolSummary.stub([
            "id": 1,
            "name": "send_email",
            "envParams": [
                [
                    "id": 3, "envParamName": "SMTP_PASSWORD", "description": "登录口令",
                    "required": true, "secret": true, "defaultValue": "abc****wxyz",
                ],
                ["envParamName": "TIMEOUT", "required": false, "secret": false, "defaultValue": "30"],
            ],
        ])
        let sections = ToolDetailPresenter.sections(for: tool, chinese: false)
        XCTAssertEqual(sections.map(\.titleKey), ["env.title"], "a row with neither bean nor method gets no second section")
        let params = try XCTUnwrap(sections.first)
        XCTAssertEqual(params.rows.count, 2)
        XCTAssertEqual(params.rows[0].title, "SMTP_PASSWORD")
        XCTAssertEqual(params.rows[0].subtitle, "登录口令 · \(hx("env.default")) abc****wxyz")
        XCTAssertEqual(params.rows[0].badges, [hx("env.required"), hx("env.sensitive")])
        XCTAssertEqual(params.rows[1].badges, [], "a parameter that is neither required nor secret marks nothing")
        XCTAssertEqual(params.rows[1].subtitle, "\(hx("env.default")) 30")
    }

    /// The keys and the entry table come from different columns, so a row can name required parameters
    /// without describing them (`AgentToolServiceImpl.kt:39-40`).
    func testRequiredKeysStandInWhenTheEntryTableIsEmpty() throws {
        let tool = try ToolSummary.stub([
            "id": 2, "name": "web_search", "envParams": [], "requiredEnvParamKeys": ["SEARCH_QUOTA"],
        ])
        let params = try XCTUnwrap(ToolDetailPresenter.sections(for: tool, chinese: false).first)
        XCTAssertEqual(params.titleKey, "env.title")
        XCTAssertEqual(params.rows.map(\.title), ["SEARCH_QUOTA"])
        XCTAssertEqual(params.rows[0].badges, [hx("env.required")])
    }

    func testAToolWithNothingToSayHasNoSections() throws {
        let tool = try ToolSummary.stub(["id": 3, "name": "noop"])
        XCTAssertTrue(ToolDetailPresenter.sections(for: tool, chinese: false).isEmpty)
    }

    /// The implementation section is the one thing the table has no column for, and a row with neither bean
    /// nor method drops it rather than showing a header over nothing.
    func testTheImplementationSectionNamesTheBeanAndTheMethodApart() throws {
        let tool = try ToolSummary.stub(["id": 4, "name": "clip_video", "beanName": "videoTool", "methodName": "clip"])
        let sections = ToolDetailPresenter.sections(for: tool, chinese: false)
        let implementation = try XCTUnwrap(sections.last)
        XCTAssertEqual(implementation.titleKey, "tool.section.implementation")
        XCTAssertEqual(implementation.rows.map(\.subtitle), ["videoTool", "clip"])

        let bare = try ToolSummary.stub(["id": 5, "name": "plain"])
        XCTAssertEqual(ToolDetailPresenter.sections(for: bare, chinese: false).count, 0)
    }

    func testTheTitleFallsBackThroughTheColumnsThenTheIdThenAPlaceholder() throws {
        let bilingual = try ToolSummary.stub([
            "id": 6, "name": "send_email", "displayName": "Send Email", "displayNameZh": "发送邮件",
        ])
        XCTAssertEqual(ToolDetailPresenter.title(for: bilingual, chinese: true), "发送邮件")
        XCTAssertEqual(ToolDetailPresenter.title(for: bilingual, chinese: false), "Send Email")
        XCTAssertEqual(ToolDetailPresenter.codeNameLine(for: bilingual, chinese: true), "send_email")

        let blankChinese = try ToolSummary.stub([
            "id": 7, "name": "clip_video", "displayName": "   ", "displayNameZh": "",
        ])
        XCTAssertEqual(ToolDetailPresenter.title(for: blankChinese, chinese: true), "clip_video")
        XCTAssertNil(ToolDetailPresenter.codeNameLine(for: blankChinese, chinese: true), "one name is one line")

        let nameless = try ToolSummary.stub(["id": 8])
        XCTAssertEqual(ToolDetailPresenter.title(for: nameless, chinese: false), hx("agent.binding.toolFallback", 8))

        let nothingAtAll = try ToolSummary.stub(["displayName": "  "])
        XCTAssertEqual(ToolDetailPresenter.title(for: nothingAtAll, chinese: false), hx("agent.binding.unnamed"))
    }

    // MARK: - helpers

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }
}
