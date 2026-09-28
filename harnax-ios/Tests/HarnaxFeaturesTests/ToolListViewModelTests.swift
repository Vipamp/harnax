import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// The read-only tool list's behaviour: what the first answer becomes on screen, how the two filters reach
/// the stack, what paging does to the row set, and what a failure may not take away.
///
/// There is no write coverage here on purpose — the domain has no writes
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentToolController.kt:13-15`).
@MainActor
final class ToolListViewModelTests: XCTestCase {
    private func page(_ records: [[String: Any]], total: Int? = nil, pageNum: Int = 1) throws -> Page<ToolSummary> {
        try PageStub.page(ToolSummary.self, records, pageNum: pageNum, total: total)
    }

    /// `displayName`/`displayNameZh` are present so the title rule has something to prefer; the 0/1 columns
    /// are spelled as integers, because that is what the backend sends.
    private func tool(_ id: Int?, _ display: String) -> [String: Any] {
        var row: [String: Any] = ["displayName": display, "name": "tool_\(display)", "status": 1]
        if let id { row["id"] = id }
        return row
    }

    private func seededTools(_ count: Int, total: Int? = nil, pageNum: Int = 1) throws -> Page<ToolSummary> {
        try page((1...count).map { tool($0, "工具 \($0)") }, total: total, pageNum: pageNum)
    }

    private func fresh(_ tools: FakeToolCatalog = FakeToolCatalog()) async throws -> (ToolListViewModel, FakeToolCatalog) {
        tools.replies = [.success(try seededTools(2, total: 2))]
        let vm = ToolListViewModel(tools: tools)
        await vm.refresh()
        return (vm, tools)
    }

    // MARK: - first screen

    func testTheFirstPageBecomesRowsNotASpinner() async throws {
        let (vm, tools) = try await fresh()
        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertEqual(vm.total, 2)
        XCTAssertEqual(tools.requests.map(\.num), [1])
        XCTAssertFalse(vm.canLoadMore, "two rows out of two leaves nothing to fetch")
    }

    func testAnInstallationWithNoToolsIsNotASpinningWheel() async throws {
        let tools = FakeToolCatalog()
        tools.replies = [.success(try page([], total: 0))]
        let vm = ToolListViewModel(tools: tools)
        await vm.refresh()
        XCTAssertEqual(vm.phase, .empty)
        XCTAssertTrue(vm.items.isEmpty)
    }

    func testAFailedFirstReadOwnsTheScreen() async throws {
        let tools = FakeToolCatalog()
        tools.replies = [.failure(.offline)]
        let vm = ToolListViewModel(tools: tools)
        await vm.refresh()
        XCTAssertEqual(vm.phase, .failed(ErrorMessage.text(for: .offline)))
        XCTAssertNil(vm.inlineError, "there is no list behind a banner to keep")
    }

    // MARK: - filters on the wire

    /// The tool page route names its filter `keyword`, where the agent and team routes name theirs `name`.
    func testTheSearchWordGoesOutAsTheKeywordFilter() async throws {
        let (vm, tools) = try await fresh()
        vm.keyword = "邮件"
        tools.replies = [.success(try seededTools(1, total: 1))]
        try await waitUntil { tools.filters.count == 2 }
        XCTAssertEqual(tools.filters.last?.keyword, "邮件")
    }

    /// The status filter is the raw 0/1 column, and `all` leaves it off rather than sending a sentinel.
    func testTheStatusFilterIsSentAsTheZeroOneColumn() async throws {
        let (vm, tools) = try await fresh()
        tools.replies = [.success(try seededTools(1, total: 1))]
        vm.filter = .disabled
        try await waitUntil { tools.filters.count == 2 }
        XCTAssertEqual(tools.filters.last?.status, 0)
        vm.filter = .enabled
        try await waitUntil { tools.filters.count == 3 }
        XCTAssertEqual(tools.filters.last?.status, 1)
        vm.filter = .all
        try await waitUntil { tools.filters.count == 4 }
        XCTAssertNil(tools.filters.last?.status)
    }

    func testWhitespaceOnlySearchIsNotAFilter() async throws {
        let (vm, _) = try await fresh()
        vm.keyword = "   "
        XCTAssertFalse(vm.isFiltered, "spaces are not a search the user meant")
        vm.filter = .enabled
        XCTAssertTrue(vm.isFiltered)
    }

    // MARK: - paging

    func testTheNextPageAppendsRatherThanReplacing() async throws {
        let tools = FakeToolCatalog()
        tools.replies = [
            .success(try page([tool(1, "甲"), tool(2, "乙")], total: 5)),
            .success(try page([tool(3, "丙"), tool(4, "丁")], total: 5, pageNum: 2)),
        ]
        let vm = ToolListViewModel(tools: tools)
        await vm.refresh()
        XCTAssertTrue(vm.canLoadMore)
        await vm.loadMore()
        XCTAssertEqual(tools.requests.map(\.num), [1, 2])
        XCTAssertEqual(vm.items.count, 4)
        XCTAssertEqual(vm.total, 5)
        XCTAssertTrue(vm.canLoadMore)
    }

    /// A shifted page can repeat a row the screen already shows; `List` keys on the row, so a duplicate
    /// would render twice under one identity.
    func testARowAlreadyOnScreenIsNotAppendedTwice() async throws {
        let tools = FakeToolCatalog()
        tools.replies = [
            .success(try page([tool(1, "甲"), tool(2, "乙")], total: 3)),
            .success(try page([tool(2, "乙"), tool(3, "丙")], total: 3, pageNum: 2)),
        ]
        let vm = ToolListViewModel(tools: tools)
        await vm.refresh()
        await vm.loadMore()
        XCTAssertEqual(vm.items.compactMap(\.id), [1, 2, 3])
        XCTAssertFalse(vm.canLoadMore)
    }

    func testScrollingPastTheLastPageAsksNothing() async throws {
        let (vm, tools) = try await fresh()
        await vm.loadMore()
        XCTAssertEqual(tools.requests.count, 1, "canLoadMore is the only gate the scroll needs")
    }

    func testAFailedPageKeepsItsRowsAndAsksTheSameNumberAgain() async throws {
        let tools = FakeToolCatalog()
        tools.replies = [.success(try page([tool(1, "甲")], total: 3))]
        let vm = ToolListViewModel(tools: tools)
        await vm.refresh()
        XCTAssertTrue(vm.canLoadMore)

        tools.replies = [.failure(.offline), .success(try page([tool(2, "乙")], total: 3, pageNum: 2))]
        await vm.loadMore()
        XCTAssertEqual(vm.items.count, 1, "the rows the operator is reading stay")
        XCTAssertEqual(vm.inlineError, ErrorMessage.text(for: .offline))
        XCTAssertEqual(vm.phase, .content)
        await vm.loadMore()
        XCTAssertEqual(tools.requests.map(\.num), [1, 2, 2], "a failed page is retried, not skipped")
        XCTAssertEqual(vm.items.count, 2)
    }

    /// A refresh failure on a populated list is a banner, not a replacement screen — and the next successful
    /// refresh clears it.
    func testAFailedRefreshLeavesTheListUpAndClearsOnceItWorks() async throws {
        let (vm, tools) = try await fresh()
        tools.replies = [.failure(.timeout)]
        await vm.refresh()
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(vm.inlineError, ErrorMessage.text(for: .timeout))

        tools.replies = [.success(try seededTools(1, total: 1))]
        await vm.refresh()
        XCTAssertNil(vm.inlineError)
        XCTAssertEqual(vm.items.count, 1)
    }

    // MARK: - the drill-down

    /// The row the sheet opened with is the row it shows: `/page` already carries the parameter table, so
    /// opening a sheet is not a request.
    func testOpeningADetailCostsNoRequest() async throws {
        let (vm, tools) = try await fresh()
        let model = ToolDetailModel(tools: tools, tool: vm.items[0])
        XCTAssertTrue(tools.detailRequests.isEmpty)
        XCTAssertEqual(model.tool.id, 1)
    }

    func testTheReReadRowReplacesWhatIsOnScreen() async throws {
        let (vm, tools) = try await fresh()
        tools.detailReplies = [.success(try ToolSummary.stub(tool(1, "改名后的工具")))]
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
        tools.replies = [.success(try page([tool(nil, "无名")], total: 1))]
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
