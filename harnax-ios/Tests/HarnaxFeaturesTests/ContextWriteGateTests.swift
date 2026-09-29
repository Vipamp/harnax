import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// The five write paths that go out on a tap, each held open by its double so the window between the tap and
/// the answer can be looked at.
///
/// Every one of these controls is disabled on its own in-flight flag, and the flag only lands on the next
/// render pass — so a second tap in the same beat reaches the model before the button does. `FakeEnvVars`
/// set the pattern for holding a call open
/// (`EnvVarFormViewModelTests.testASecondTapWhileTheStackIsAnsweringSendsNothing`); these are the five
/// context-domain call sites that had no guard of their own: the sync sheet's install, the MCP form's save,
/// the two model forms' saves, and the CLI kill switch's blast-radius read.
///
/// The second tap goes out as its own task rather than awaited inline: without a guard it would park in the
/// same double that is holding the first one, and the test would never reach the release. A short settle
/// window is what distinguishes "refused" from "posted a second request and is now parked".
@MainActor
final class ContextWriteGateTests: XCTestCase {
    private let admin = AccountSnapshot(username: "admin", isAdministrator: true)

    /// Step two of a sync. The selection is already on screen and the button still says "install 1", so a
    /// double tap is a plausible gesture — and a second install overwrites the source's sync report with a
    /// run nobody meant to make.
    func testTheSyncSheetStoresTheSelectionOnce() async throws {
        let skills = FakeSkills()
        skills.previewReplies = [.success(try PageStub.list([SkillPreviewItem].self, [
            ["name": "weekly-report", "resources": [String: String](), "exists": false],
        ]))]
        skills.installReplies = [.success(.none), .success(.none)]
        let model = SkillSyncModel(source: try SkillSourceSummary.stub(7), skills: skills)
        await model.load()
        XCTAssertEqual(model.selectedCount, 1, "everything arrives checked")

        skills.gateWrites = true
        let first = Task { await model.submit() }
        try await waitUntil { skills.installRequests.count == 1 }
        XCTAssertTrue(model.isSubmitting)
        let second = Task { await model.submit() }
        try await settle()
        XCTAssertEqual(skills.installRequests.count, 1, "a second tap cannot store the same selection twice")
        skills.releaseWrites()
        await first.value
        await second.value
        XCTAssertFalse(model.isSubmitting)
        XCTAssertEqual(skills.installRequests.count, 1, "one install, one POST")
    }

    func testTheMCPFormRegistersTheServerOnce() async throws {
        let mcp = FakeMcpServers()
        mcp.createReplies = [.success(EmptyResponse()), .success(EmptyResponse())]
        let vm = McpFormViewModel(mcp: mcp, mode: .create)
        vm.name = "报表服务"
        vm.endpointURL = "https://example.com/mcp"
        XCTAssertTrue(vm.validate().isEmpty, "the form is submittable, so the only thing left is the guard")

        mcp.gateWrites = true
        let first = Task { await vm.save() }
        try await waitUntil { mcp.createRequests.count == 1 }
        XCTAssertTrue(vm.isSaving)
        let second = Task { await vm.save() }
        try await settle()
        XCTAssertEqual(mcp.createRequests.count, 1, "a second tap cannot create the same server twice")
        mcp.releaseWrites()
        _ = await first.value
        _ = await second.value
        XCTAssertFalse(vm.isSaving)
        XCTAssertEqual(mcp.createRequests.count, 1, "one save, one POST")
    }

    /// A duplicate name inside one provider is the ordinary refusal on this route
    /// (`ModelServiceImpl.kt:96-110`), and a double submit is one way to cause it to an operator who never
    /// typed a second name.
    func testTheModelFormSavesOnce() async throws {
        let catalog = FakeModelCatalog()
        catalog.modelSaveReplies = [.success(EmptyResponse()), .success(EmptyResponse())]
        let vm = ModelFormModel(catalog: catalog, providerID: 3, editing: nil, account: admin)
        vm.name = "新模型"
        vm.technicalName = "qwen-max"

        catalog.gateWrites = true
        let first = Task { await vm.submit() }
        try await waitUntil { catalog.modelSaveCalls.count == 1 }
        XCTAssertTrue(vm.isSaving)
        let second = Task { await vm.submit() }
        try await settle()
        XCTAssertEqual(catalog.modelSaveCalls.count, 1, "a second tap cannot post the same model twice")
        catalog.releaseWrites()
        _ = await first.value
        _ = await second.value
        XCTAssertFalse(vm.isSaving)
        XCTAssertEqual(catalog.modelSaveCalls.count, 1, "one save, one POST")
    }

    func testTheProviderFormSavesOnce() async throws {
        let catalog = FakeModelCatalog()
        catalog.providerSaveReplies = [.success(EmptyResponse()), .success(EmptyResponse())]
        let vm = ModelProviderFormModel(catalog: catalog, editing: nil, account: admin)
        vm.name = "新厂商"
        vm.address = "https://dashscope.aliyuncs.com"

        catalog.gateWrites = true
        let first = Task { await vm.submit() }
        try await waitUntil { catalog.providerSaveCalls.count == 1 }
        XCTAssertTrue(vm.isSaving)
        let second = Task { await vm.submit() }
        try await settle()
        XCTAssertEqual(catalog.providerSaveCalls.count, 1, "a second tap cannot post the same provider twice")
        catalog.releaseWrites()
        _ = await first.value
        _ = await second.value
        XCTAssertFalse(vm.isSaving)
        XCTAssertEqual(catalog.providerSaveCalls.count, 1, "one save, one POST")
    }

    /// The switch asks who binds the package before it decides anything, and the decision is what a second tap
    /// would have made twice: two reads, then two writes, then two refresh offers for one row.
    func testTheKillSwitchAsksTheSameQuestionOnce() async throws {
        let clis = FakeClis()
        try clis.seedPage([["id": 3, "name": "harnax-cli", "version": "1.4.0"]])
        let vm = CliListViewModel(clis: clis)
        await vm.refresh()
        let row = try XCTUnwrap(vm.items.first)

        clis.gateRelatedAgents = true
        let first = Task { await vm.requestStatus(false, for: row) }
        try await waitUntil { clis.relatedAgentRequests == [3] }
        XCTAssertTrue(vm.isCheckingStatus, "the row shows progress while the read is out")
        let second = Task { await vm.requestStatus(false, for: row) }
        try await settle()
        XCTAssertEqual(clis.relatedAgentRequests, [3], "a second tap cannot ask who is bound a second time")
        clis.releaseReads()
        await first.value
        await second.value
        XCTAssertFalse(vm.isCheckingStatus)
        XCTAssertEqual(clis.relatedAgentRequests, [3], "one question, one read")
    }

    /// Long enough for a second task to reach the catalog it is about to park in, and short enough that a
    /// guard that does exist cannot be mistaken for one that is merely slow.
    private func settle() async throws {
        try await Task.sleep(for: .milliseconds(50))
    }

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }
}
