import XCTest

@testable import HarnaxCore

/// The rows `GET /api/admin/agent-tasks/{id}/logs` answers, plus the two filters the log screen builds on them.
///
/// Key names follow the entity (`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/entity/AgentTaskLog.kt:8-54`),
/// not `AgentTaskLogResponse`, whose schema comment stops at status 3 and which never reaches this route
/// (`§5.1`). Every column except `startTime` and `endTime` is non-null in the table, so the rows below carry all
/// fourteen keys the way the stack does — and the in-flight row is the one case where two keys are absent rather
/// than null, because `default-property-inclusion: non_null` drops them.
final class AgentTaskLogContractTests: XCTestCase {
    private func log(_ json: String) throws -> AgentTaskLog {
        try JSONDecoder().decode(AgentTaskLog.self, from: Data(json.utf8))
    }

    private func page(_ json: String) throws -> Page<AgentTaskLog> {
        try JSONDecoder().decode(Page<AgentTaskLog>.self, from: Data(json.utf8))
    }

    /// A finished run: both stamps, an answer, and the usage blob the executor writes as a `toString()`.
    private let finished = """
    {"id":901,"taskId":41,"taskName":"每日晨报","prompt":"汇总昨日提交","response":"共 3 次提交，无阻塞项。",\
    "sessionId":"task-41-7-4f2a9c","status":1,"errorInfo":"",\
    "tokenUsage":"TokenUsage(inputTokens=812, outputTokens=96, totalTokens=908, costTime=16.3, timestamp=1758762019000)",\
    "startTime":"2026-09-25 09:00:03","endTime":"2026-09-25 09:00:19","durationMs":16340,\
    "creator":"admin","createTime":"2026-09-25 09:00:03"}
    """

    func testAFinishedRowKeepsEveryColumnTheTableDeclares() throws {
        let row = try log(finished)

        XCTAssertEqual(row.id, 901, "the log id is what /logs/{logId}/stop addresses, not the task id")
        XCTAssertEqual(row.taskId, 41)
        XCTAssertEqual(row.title, "每日晨报")
        XCTAssertEqual(row.promptText, "汇总昨日提交")
        XCTAssertEqual(row.answerText, "共 3 次提交，无阻塞项。")
        XCTAssertEqual(row.conversationID, "task-41-7-4f2a9c")
        XCTAssertEqual(row.state, .succeeded)
        XCTAssertEqual(row.tokenUsage, "TokenUsage(inputTokens=812, outputTokens=96, totalTokens=908, costTime=16.3, timestamp=1758762019000)", "the usage column is kept whole; nothing here parses it")
        XCTAssertEqual(row.startTime, "2026-09-25 09:00:03")
        XCTAssertEqual(row.endTime, "2026-09-25 09:00:19")
        XCTAssertEqual(row.durationMs, 16_340)
        XCTAssertEqual(row.creator, "admin")
        XCTAssertEqual(row.createTime, "2026-09-25 09:00:03")
        XCTAssertNil(row.failureText, "an empty errorInfo is a run that did not fail, not a failure with no text")
    }

    /// A run still in flight has no end to report, and `non_null` inclusion drops the key rather than sending
    /// null (`AgentTaskLog.kt:44-47`).
    func testARowStillRunningDecodesWithoutItsTwoOptionalStamps() throws {
        let row = try log("""
        {"id":902,"taskId":41,"taskName":"每日晨报","prompt":"汇总昨日提交","response":"","sessionId":"task-41-7-8b1c",\
        "status":3,"errorInfo":"","tokenUsage":"","creator":"admin","createTime":"2026-09-26 09:00:01","durationMs":0}
        """)

        XCTAssertNil(row.startTime)
        XCTAssertNil(row.endTime)
        XCTAssertEqual(row.state, .running)
        XCTAssertNil(row.answerText, "a blank answer column reads as absent")
        XCTAssertTrue(row.tokenUsage.isEmpty, "usage stays an empty string; only the two stamps go missing from the wire")
    }

    func testThePageWrapsTheRowsInTheSameEnvelopeAsTheTaskList() throws {
        let decoded = try page("""
        {"pageNum":1,"pageSize":10,"total":27,"records":[\(finished)]}
        """)

        XCTAssertEqual(decoded.total, 27, "twelve pages of ten, so the sheet has to keep asking")
        XCTAssertEqual(decoded.records.count, 1)
    }

    /// A number outside the six the entity comments (`AgentTaskLog.kt:31-32`) reads as absent so the row still
    /// decodes — the console paints it as Failed, and the row keeps the raw value for the view to do that.
    func testAnUnknownStatusCodeIsAbsentRatherThanADecodingFailure() throws {
        let row = try log("""
        {"id":903,"taskId":41,"taskName":"T","prompt":"p","response":"r","sessionId":"s","status":9,"errorInfo":"",\
        "tokenUsage":"","creator":"admin","createTime":"2026-09-26 09:00:01","durationMs":10}
        """)

        XCTAssertNil(row.state)
        XCTAssertEqual(row.status, 9)
    }

    // MARK: - the duration the console computes

    /// `(ms/1000).toFixed(1) + "s"` (`TaskLogModal.tsx:219`), computed in tenths so a locale that writes comma
    /// decimals still reads the same digits the console writes.
    func testDurationsAreSpelledWithOneDecimalAndNoLocale() {
        XCTAssertEqual(AgentTaskLog.spelledDuration(1_500), "1.5s")
        XCTAssertEqual(AgentTaskLog.spelledDuration(16_340), "16.3s")
        XCTAssertEqual(AgentTaskLog.spelledDuration(16_345), "16.3s", "below the half, so nothing carries")
        XCTAssertEqual(AgentTaskLog.spelledDuration(16_350), "16.4s", "an exact half rounds up")
        XCTAssertEqual(AgentTaskLog.spelledDuration(949), "0.9s")
        XCTAssertEqual(AgentTaskLog.spelledDuration(950), "1.0s")
        XCTAssertEqual(AgentTaskLog.spelledDuration(0), "0.0s", "a run that never reported a duration")
        XCTAssertEqual(AgentTaskLog.spelledDuration(-1), "0.0s", "and a clock that went backwards does not print -0.0")
    }

    func testTheRowRendersItsOwnDuration() throws {
        XCTAssertEqual(try log(finished).durationText, "16.3s")
    }

    // MARK: - who may stop a row

    /// Only a `3` is still running. A `4` is already going down — either to `5` via `finalizeStopped` or to `2`
    /// via the sweep (`§5.2`) — and the route answers 500 for anything else (`SchedulerController.kt:144-150`).
    func testOnlyARunningRowIsStoppableAtAll() throws {
        XCTAssertFalse(try log(finished).isStoppable)
        let running = try log("""
        {"id":904,"taskId":41,"taskName":"T","prompt":"p","response":"","sessionId":"s","status":3,"errorInfo":"",\
        "tokenUsage":"","creator":"admin","createTime":"2026-09-26 09:00:01","durationMs":0}
        """)
        XCTAssertTrue(running.isStoppable)
        let stopping = try log("""
        {"id":905,"taskId":41,"taskName":"T","prompt":"p","response":"","sessionId":"s","status":4,"errorInfo":"",\
        "tokenUsage":"","creator":"admin","createTime":"2026-09-26 09:00:01","durationMs":0}
        """)
        XCTAssertFalse(stopping.isStoppable, "a row that is already on its way down takes no second stop")
    }

    /// The read is wider than the write: `requireOwnedLog` (`AgentTaskCrudServiceImpl.kt:200-210`) is creator-only
    /// with no administrator pass, so another account's live row is readable and not stoppable.
    func testStoppingIsTheCreatorsAlone() throws {
        let running = try log(finished.replacingOccurrences(of: "\"status\":1", with: "\"status\":3"))
        XCTAssertTrue(running.stoppable(by: AccountSnapshot(username: "admin", isAdministrator: true)))
        XCTAssertFalse(
            running.stoppable(by: AccountSnapshot(username: "liwei", isAdministrator: true)),
            "an administrator gets no pass on this route"
        )
        XCTAssertFalse(running.stoppable(by: nil))
    }

    // MARK: - the filter value

    func testAnUnusedFilterIsEmptyAndSoStopsTheEmptyCopyBeingShownAsNoMatches() {
        XCTAssertTrue(AgentTaskLogFilter().isEmpty)
        XCTAssertFalse(AgentTaskLogFilter(state: .failed).isEmpty)
        XCTAssertFalse(AgentTaskLogFilter(keyword: "晨报").isEmpty)
        XCTAssertFalse(AgentTaskLogFilter(from: Date()).isEmpty, "a one-sided window is still a window")
    }

    /// A blank keyword is not a filter: the screen holds one in its own `@Published` and joins it at the read,
    /// so "cleared the search box" has to read the same as "never typed".
    func testAKeywordOfOnlySpacesIsNoFilter() {
        XCTAssertTrue(AgentTaskLogFilter(keyword: "   ").isEmpty)
    }

    /// The pair the sheet's two refresh paths key off. `keyword` is deliberately excluded — a keystroke already
    /// schedules a debounced reload, and comparing it here would put every character through two paths at once.
    func testTypingAloneNeverCountsAsANewSearch() {
        let bare = AgentTaskLogFilter()
        XCTAssertTrue(bare.isSearchEquivalent(to: AgentTaskLogFilter(keyword: "晨报")))
        XCTAssertFalse(bare.isSearchEquivalent(to: AgentTaskLogFilter(state: .running)))
        XCTAssertFalse(bare.isSearchEquivalent(to: AgentTaskLogFilter(from: Date())))
        XCTAssertFalse(
            AgentTaskLogFilter(state: .running).isSearchEquivalent(to: AgentTaskLogFilter(state: .stopped)),
            "…while a chosen status takes effect on the spot"
        )
    }
}
