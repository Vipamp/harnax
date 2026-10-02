import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// The detail sheet's configuration editor: what it may open, what a save actually puts on the wire, and what
/// the panel is allowed to claim after each of the three answers the route can give.
///
/// The route is the one the web console never calls — the console drives the same four columns through
/// `POST /api/router/agent/command` (`harnax-webui/src/pages/session/components/ChatWindow.tsx:2408-2430`) —
/// so nothing here has a page-level reference to copy. What it does have is the server's own behaviour:
/// `PUT /api/admin/sessions/{sessionId}/config` answers `ResultVo<Void>` (`SessionController.kt:128-141`),
/// which is why a landed write is followed by a read rather than by a success message alone, and why the read
/// getting back is a case of its own.
///
/// The refusals are pinned where they are decided, not where they are drawn: a caller that ignores
/// `canSaveConfig` still gets a named answer, and a switch the model refuses is refused by the draft itself.
@MainActor
final class SessionDetailConfigEditingTests: XCTestCase {
    private func scripted(
        _ session: SessionSummary = .stub(),
        write: Result<EmptyResponse, APIError> = .failure(.offline),
        readBack: SessionSummary = .stub()
    ) -> (SessionDetailViewModel, ScriptedSessionConfig, () -> Int) {
        let config = ScriptedSessionConfig()
        config.writeResult = write
        config.row = readBack
        var writes = 0
        let vm = SessionDetailViewModel(session: session, config: config, onWritten: { writes += 1 })
        return (vm, config, { writes })
    }

    /// The panel's reading state: the row's own columns, no draft, nothing to send.
    func testThePanelOpensOnTheRowsOwnColumns() throws {
        let (vm, _, _) = scripted(.stub(enablePlan: 1))
        XCTAssertEqual(vm.stored.enablePlan, true)
        XCTAssertFalse(vm.isEditingConfig)
        XCTAssertFalse(vm.hasConfigChanges)
        XCTAssertFalse(vm.canSaveConfig)
        XCTAssertEqual(vm.displayedConfig, vm.stored)
    }

    /// Three refusals, each with its own reason: no leg, no string business key, and a conversation the
    /// server's own read answers with a `403` (`SessionServiceImpl.kt:276-279`).
    func testOnlyALiveRowWithAKeyAndALegOpensTheEditor() throws {
        XCTAssertFalse(SessionDetailViewModel(session: .stub()).canEditConfig, "this host has no config leg")
        XCTAssertFalse(
            SessionDetailViewModel(session: .stub(sessionId: "  "), config: ScriptedSessionConfig()).canEditConfig,
            "both config routes address the string sessionId, and a blank one addresses nothing"
        )
        XCTAssertFalse(
            SessionDetailViewModel(session: .stub(status: 0), config: ScriptedSessionConfig()).canEditConfig,
            "a switched-off conversation is refused by the read this write goes through first"
        )
        XCTAssertTrue(scripted().0.canEditConfig)
    }

    /// The body of a landed write carries the two moved fields and explicit nulls for the two that did not,
    /// because the service applies only what it finds non-null (`SessionServiceImpl.kt:288-300`) and an absent
    /// key would be read as that key's Kotlin default rather than as "leave the column alone".
    func testALandedWriteSendsOnlyWhatMovedAndReReadsTheRow() async throws {
        let (vm, config, writes) = scripted(
            write: .success(EmptyResponse()),
            readBack: .stub(enablePlan: 1, permissionMode: "EXPLORE")
        )
        vm.beginConfigEdit()
        vm.setConfigPlan(true)
        vm.setConfigPermissionMode(.explore)
        XCTAssertTrue(vm.canSaveConfig)
        await vm.saveConfig()

        XCTAssertEqual(config.writes, ["web-2f6c-8842"])
        XCTAssertEqual(
            config.changes,
            [SessionChatChange(enableThink: nil, enableSearch: nil, enablePlan: true, permissionMode: "EXPLORE")]
        )
        XCTAssertEqual(config.requested, ["web-2f6c-8842"], "the write's reply is empty, so the row is read back")
        XCTAssertEqual(vm.stored.enablePlan, true)
        XCTAssertEqual(vm.stored.permissionMode, .explore)
        XCTAssertFalse(vm.isEditingConfig)
        XCTAssertEqual(vm.configNotice, hx("chat.detail.config.saved"))
        XCTAssertEqual(writes(), 1, "the list holding this row has just had four columns replaced")
    }

    /// A refusal leaves everything the user typed on the screen: the columns did not move, so `stored` still
    /// holds the server's truth and only the notice changes.
    func testARefusedWriteKeepsTheDraftAndNeverReadsBack() async throws {
        let (vm, config, writes) = scripted(write: .failure(.business(code: 403, message: "session is disabled")))
        vm.beginConfigEdit()
        vm.setConfigSearch(true)
        await vm.saveConfig()

        XCTAssertEqual(vm.configNotice, "session is disabled", "the backend's own sentence reaches the panel")
        XCTAssertTrue(vm.isEditingConfig)
        XCTAssertEqual(vm.draft?.enableSearch, true)
        XCTAssertEqual(vm.stored.enableSearch, false, "a write that did not land moves no column")
        XCTAssertTrue(config.requested.isEmpty, "the panel does not read after a refusal")
        XCTAssertEqual(writes(), 0)
    }

    /// The awkward third answer: the write landed and the read did not. The panel says both things at once —
    /// it shows what was applied and names the read-back as the part that is missing.
    func testALandedWriteWhoseReadBackFailsStillAppliesAndSaysSo() async throws {
        let (vm, config, _) = scripted(write: .success(EmptyResponse()))
        config.error = .offline
        vm.beginConfigEdit()
        vm.setConfigPlan(true)
        await vm.saveConfig()

        XCTAssertEqual(vm.stored.enablePlan, true, "the server took the write, so the draft is the truth")
        XCTAssertFalse(vm.isEditingConfig)
        XCTAssertEqual(vm.configNotice, hx("chat.detail.config.readBackFailed"))
    }

    /// A draft that moved nothing never reaches the socket, and the caller that ignores `canSaveConfig` gets
    /// the named refusal rather than a silent no-op.
    func testAnUnmovedDraftSendsNothing() async throws {
        let (vm, config, writes) = scripted(write: .success(EmptyResponse()))
        vm.beginConfigEdit()
        XCTAssertFalse(vm.canSaveConfig)
        await vm.saveConfig()

        XCTAssertTrue(config.writes.isEmpty)
        XCTAssertEqual(vm.configNotice, hx("chat.detail.config.nothing"))
        XCTAssertTrue(vm.isEditingConfig, "nothing was written, so there is nothing to close")
        XCTAssertEqual(writes(), 0)
    }

    /// Cancelling hands back the confirmed values whole: nothing went to the server, so the read is still the
    /// truth and the draft is thrown away rather than reverted field by field.
    func testCancelDiscardsTheDraftAndLeavesTheServerTheTruth() throws {
        let (vm, _, _) = scripted()
        vm.beginConfigEdit()
        vm.setConfigPlan(true)
        XCTAssertEqual(vm.draft?.enablePlan, true)
        vm.cancelConfigEdit()

        XCTAssertFalse(vm.isEditingConfig)
        XCTAssertNil(vm.configNotice)
        XCTAssertEqual(vm.displayedConfig, vm.stored)
        XCTAssertEqual(vm.stored.enablePlan, false)
    }

    /// The model's two refusals are decided on the draft, not only on the control: a caller that never renders
    /// the row still cannot put a forbidden value into it (`SessionServiceImpl.kt:291-296`).
    func testASwitchTheModelRefusesStaysOffTheDraft() throws {
        let (vm, _, _) = scripted(.stub(modelSupportReasoning: 0, modelSupportInternet: 0))
        vm.beginConfigEdit()
        vm.setConfigSearch(true)
        vm.setConfigThink(true)

        XCTAssertEqual(vm.draft?.enableSearch, false)
        XCTAssertEqual(vm.draft?.enableThink, false)
        XCTAssertFalse(vm.canToggleConfigSearch)
        XCTAssertFalse(vm.canToggleConfigThink)
        XCTAssertEqual(vm.configSearchHint, hx("chat.detail.config.search.unsupported"))
        XCTAssertEqual(vm.configThinkHint, hx("chat.detail.config.think.unsupported"))
    }

    /// Mode 2 is the model's 「思考必须开启」 and overrides the stored column, so the switch is dead the other
    /// way: it cannot be turned off.
    func testARequiredThinkingModeCannotBeTurnedOff() throws {
        let (vm, _, _) = scripted(.stub(modelThinkingMode: 2))
        vm.beginConfigEdit()
        vm.setConfigThink(false)

        XCTAssertEqual(vm.draft?.enableThink, true, "the console overrides the column with the mode")
        XCTAssertFalse(vm.canToggleConfigThink)
        XCTAssertEqual(vm.configThinkHint, hx("chat.detail.config.think.required"))
    }

    /// Plan is the one switch with no capability behind it — neither the console nor the service gates it on a
    /// model column (`ChatWindow.tsx:3591-3599`, `SessionServiceImpl.kt:298`) — so it stays live even on a
    /// model that refuses the other two.
    func testPlanStaysLiveWhateverTheModelSupports() throws {
        let (vm, _, _) = scripted(.stub(modelSupportReasoning: 0, modelSupportInternet: 0))
        vm.beginConfigEdit()
        vm.setConfigPlan(true)

        XCTAssertEqual(vm.draft?.enablePlan, true)
        XCTAssertTrue(vm.canToggleConfigPlan)
        XCTAssertTrue(vm.hasConfigChanges)
    }
}
