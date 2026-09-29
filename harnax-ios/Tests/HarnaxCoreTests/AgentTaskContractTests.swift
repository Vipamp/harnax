import XCTest

@testable import HarnaxCore

/// The task domain's contract layer, read against the keys `Page<AgentTask>` really carries — the entity, not
/// a DTO (`harnax-scheduler/.../controller/AgentTaskController.kt:72`, entity `AgentTask.kt:8-66`, `§2.1`).
final class AgentTaskContractTests: XCTestCase {
    private var rows: [AgentTaskSummary]!

    override func setUpWithError() throws {
        let page: Page<AgentTaskSummary> = try Fixture.decode(
            Envelope<Page<AgentTaskSummary>>.self,
            "agent-tasks-page-two-rows"
        ).data!
        rows = page.records
    }

    /// Row 2 is a task that has never fired, and `lastRunStatus`/`lastRunTime` are not columns of `agent_task`
    /// but a LEFT JOIN — so `default-property-inclusion: non_null` drops those two keys entirely (`:72-83`).
    /// A row is still readable without them, which is the whole point of declaring them optional.
    func testRealPageDecodesRowByRow() throws {
        XCTAssertEqual(rows.count, 2)

        let fired = rows[0]
        XCTAssertEqual(fired.id, 41)
        XCTAssertEqual(fired.tenantId, 1)
        XCTAssertEqual(fired.name, "每日晨报")
        XCTAssertEqual(fired.agentId, 7)
        XCTAssertEqual(fired.agentName, "翻译助手")
        XCTAssertEqual(fired.cronExpression, "0 0 9 * * ?")
        XCTAssertEqual(fired.timeoutSeconds, 300)
        XCTAssertEqual(fired.creator, "admin")
        XCTAssertEqual(fired.active, 1)
        XCTAssertEqual(fired.updateTime, "2026-09-25 18:40:11")
        XCTAssertEqual(fired.lastRunStatus, 1)
        XCTAssertEqual(fired.lastRunTime, "2026-09-25 09:00:03")

        let never = rows[1]
        XCTAssertNil(never.lastRunStatus)
        XCTAssertNil(never.lastRunTime)
        XCTAssertNil(never.lastRun, "…and with no key there is no status to draw")
    }

    /// `taskStatus`, `concurrent`, `isPublic` and `active` are 0/1 `Int` columns, and this is the one domain
    /// where 0 on the status column means "paused" while `active` means "not deleted" (`§2.1`).
    func testFlagColumnsReadAsTheServerWritesThem() throws {
        XCTAssertTrue(rows[0].isEnabled)
        XCTAssertFalse(rows[0].allowsConcurrentRun)
        XCTAssertTrue(rows[0].isShared)

        XCTAssertFalse(rows[1].isEnabled)
        XCTAssertTrue(rows[1].allowsConcurrentRun)
        XCTAssertFalse(rows[1].isShared)
    }

    /// `agentName` and `description` are non-null columns whose *value* may be empty (`:26`, `:44`), and the
    /// console's own blank fallback is what `hxPresented` stands in for here.
    func testBlankColumnsReadAsAbsent() throws {
        XCTAssertNil(rows[1].agentDisplayName)
        XCTAssertNil(rows[1].note)
        XCTAssertEqual(rows[1].title, "依赖巡检")
        XCTAssertEqual(rows[0].note, "工作日每天早上九点跑一次")
    }

    // MARK: - the last-run badge

    /// All six values of `AgentTaskLog.status`, from the entity's own comment rather than the response DTO
    /// whose schema note stops at 3 (`AgentTaskLog.kt:31-32`).
    func testLastRunStatusCoversTheWholeStateMachine() throws {
        XCTAssertEqual(AgentTaskLastRun.allCases.map(\.rawValue), [0, 1, 2, 3, 4, 5])
        for status in AgentTaskLastRun.allCases {
            XCTAssertEqual(AgentTaskLastRun.resolve(status.rawValue), status)
            XCTAssertTrue(status.titleKey.hasPrefix("task.lastRun."))
        }
        XCTAssertNil(AgentTaskLastRun.resolve(nil))
        XCTAssertNil(AgentTaskLastRun.resolve(9), "an unnameable number must not decode as a status")
    }

    /// Running and Stopping are the pair the web list polls on (`§2.5`); the other four are terminal.
    func testOnlyRunningAndStoppingAreInFlight() {
        XCTAssertEqual(AgentTaskLastRun.allCases.filter(\.isInFlight).map(\.rawValue), [3, 4])
    }

    // MARK: - who may write a row

    /// Creator-only, with **no** administrator pass: the update and delete statements carry
    /// `AND creator = #{currentUsername}` in the SQL itself (`AgentTaskMapper.xml:64,69`), so an admin editing
    /// someone else's task is refused. `AccountSnapshot.canManage` would be wrong here for exactly that reason.
    func testWritingIsGatedOnTheCreatorAlone() throws {
        let mine = rows[0]
        let theirs = rows[1]

        XCTAssertTrue(mine.writable(by: AccountSnapshot(username: "admin")))
        XCTAssertTrue(mine.writable(by: AccountSnapshot(username: "admin", isAdministrator: true)))
        XCTAssertFalse(mine.writable(by: AccountSnapshot(username: "alice", isAdministrator: true)),
                       "an administrator gets no extra reach in this domain")
        XCTAssertFalse(mine.writable(by: nil))
        XCTAssertFalse(theirs.writable(by: AccountSnapshot(username: "admin")))
        XCTAssertTrue(theirs.writable(by: AccountSnapshot(username: "alice")))
    }

    /// A row whose `creator` the stack left empty is nobody's, so nobody may write it.
    func testAnEmptyCreatorNamesNoOwner() throws {
        let orphan = try row(#"{"name":"a","agentName":"","prompt":"p","cronExpression":"0 0 9 * * ?","taskStatus":1,"concurrent":0,"timeoutSeconds":300,"description":"","isPublic":1,"creator":"","active":1}"#)
        XCTAssertFalse(orphan.writable(by: AccountSnapshot(username: "")))
        XCTAssertFalse(orphan.writable(by: AccountSnapshot(username: "admin")))
    }

    // MARK: - the write bodies

    /// The create body is all eight fields of `AgentTaskCreateRequest`, with the two flags as 0/1 — never as
    /// JSON booleans (`TaskForm.tsx:61-65` does the same conversion before submitting).
    func testCreateDraftSendsFlagsAsNumbers() throws {
        let json = try JSONObject(
            AgentTaskDraft(
                name: "晨报", agentId: 7, prompt: "p", cronExpression: "0 0 9 * * ?",
                concurrent: 1, timeoutSeconds: nil, description: "note", isPublic: 0
            )
        )
        XCTAssertEqual(json["agentId"] as? Int, 7)
        XCTAssertEqual(json["concurrent"] as? Int, 1)
        XCTAssertEqual(json["isPublic"] as? Int, 0)
        XCTAssertEqual(json["description"] as? String, "note")
        XCTAssertNil(json["timeoutSeconds"], "an omitted timeout lets the DTO's own 300 stand")
        XCTAssertNil(json["taskStatus"], "the server fills status, creator, active and tenant itself")
        XCTAssertNil(json["agentName"], "admin stamps the snapshot name from agentId")
    }

    /// Partial by construction (`AgentTaskUpdateRequest.kt:16-45`, `§3.2`): an untouched edit form sends a body
    /// with no keys at all, and every key it does send is one the user moved.
    func testEmptyChangeEncodesNoKeysAtAll() throws {
        XCTAssertTrue(try JSONObject(AgentTaskChange()).isEmpty)
    }

    func testChangeCarriesOnlyTheColumnsThatMoved() throws {
        let json = try JSONObject(AgentTaskChange(name: nil, prompt: "new", concurrent: 0))
        XCTAssertEqual(Set(json.keys), ["prompt", "concurrent"])
        XCTAssertNil(json["cronExpression"], "an unchanged expression is not resent, so it is not revalidated")
    }

    /// An empty *description* has to go out as `""` rather than be left off: leaving it off means "keep the
    /// stored note", and clearing a field is a change the user made on purpose (`AgentTaskCrudServiceImpl.kt:143-146`).
    func testAClearedTextFieldStillGoesOutAsAnEmptyString() throws {
        let json = try JSONObject(AgentTaskChange(description: ""))
        XCTAssertEqual(Set(json.keys), ["description"])
        XCTAssertEqual(json["description"] as? String, "")
    }

    // MARK: - the two non-failure codes

    /// 40902 is "written, not scheduled" and 40903 is "this node does not schedule"; only the first is a
    /// success-with-warning for the caller (`§4.3`).
    func testOnlyTheSyncFailureIsTreatedAsACompletedWrite() {
        XCTAssertTrue(AgentTaskCode.isSchedulerOutOfSync(.business(code: 40902, message: "Task saved, but the scheduler did not reload")))
        XCTAssertFalse(AgentTaskCode.isSchedulerOutOfSync(.business(code: 40903, message: "Scheduling is disabled on this instance")))
        XCTAssertFalse(AgentTaskCode.isSchedulerOutOfSync(.business(code: 400, message: "Agent task not found")))
        XCTAssertFalse(AgentTaskCode.isSchedulerOutOfSync(.business(code: 500, message: "Failed to create agent task")))
        XCTAssertFalse(AgentTaskCode.isSchedulerOutOfSync(.offline))
    }

    // MARK: - the picker's list

    /// `GET /agent-tasks/agents` is admin's own projection of the live agents: two keys, no status field, and
    /// no envelope page around it (`AgentTaskController.kt:202-210`).
    func testAgentOptionsDecodeAsAPlainArray() throws {
        let options = try JSONDecoder().decode(
            Envelope<[AgentTaskAgentOption]>.self,
            from: Data(#"{"code":200,"message":"success","timestamp":1759026030000,"data":[{"id":7,"name":"翻译助手"},{"id":9,"name":" "}]}"#.utf8)
        ).data!
        XCTAssertEqual(options.count, 2)
        XCTAssertEqual(options[0].id, 7)
        XCTAssertEqual(options[0].displayName, "翻译助手")
        XCTAssertNil(options[1].displayName, "a blank name is not a label")
    }

    // MARK: - helpers

    /// A row the way the wire makes it: a key the stack left out is a key that is absent, not a nil argument.
    private func row(_ json: String) throws -> AgentTaskSummary {
        try JSONDecoder().decode(AgentTaskSummary.self, from: Data(json.utf8))
    }

    private func JSONObject<T: Encodable>(_ value: T) throws -> [String: Any] {
        let data = try JSONEncoder().encode(value)
        return (try JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }
}
