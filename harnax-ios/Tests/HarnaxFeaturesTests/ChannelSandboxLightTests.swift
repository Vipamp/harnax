import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// Item 4: the sandbox column has three states, and only two of them get a capsule.
///
/// The bug being pinned is a collapse. `SandboxStatus` is the runtime's own three-value answer and cannot
/// distinguish "this channel has no session to ask about" from "we asked and nothing came back", so the row
/// painted both `.unknown` and gave that a warning slot — every session-less channel therefore wore a
/// permanent warning on a screen where having no session is a normal configuration. The console splits the
/// same column three ways (`harnax-webui/src/pages/channel/index.tsx:317-327`).
@MainActor
final class ChannelSandboxLightTests: XCTestCase {
    private func row(_ name: String, sessionId: String?) throws -> ChannelSummary {
        try ChannelSummary.stub([
            "name": name,
            "id": 7,
            "type": "feishu",
            "status": 1,
        ].merging(sessionId.map { ["sessionId": $0] } ?? [:]) { _, new in new })
    }

    /// A row with no session has nothing to report, so it reports nothing: no word, no capsule.
    func testARowWithoutASessionIdClaimsNothing() throws {
        for sessionId in [nil, "", "   "] {
            let light = ChannelSandboxLight(sessionId: sessionId, status: .unknown)
            XCTAssertEqual(light, .noSession, "sessionId \(String(describing: sessionId)) is no session")
            XCTAssertNil(light.labelKey, "no copy may be rendered")
            XCTAssertNil(light.tone)
            XCTAssertFalse(light.showsCapsule)
        }
    }

    /// Asked and not answered is a missing fact, not a malfunction — the console gives that branch a default
    /// tag, and this side gives it the neutral slot instead of the old warning.
    func testAnUnansweredLookupIsNeutralAndNotAWarning() throws {
        let light = ChannelSandboxLight(sessionId: "chn-1", status: .unknown)
        XCTAssertEqual(light, .unanswered)
        XCTAssertEqual(light.labelKey, "channel.sandbox.unknown")
        XCTAssertEqual(light.tone, .textTertiary, "warning was the bug: it said something is wrong")
        XCTAssertNotEqual(light.tone, .warning)
        XCTAssertTrue(light.showsCapsule)
    }

    /// Only a real answer earns a coloured capsule, and the two answers stay apart.
    func testOnlyARuntimeAnswerGetsTheColouredCapsule() throws {
        let running = ChannelSandboxLight(sessionId: "chn-1", status: .running)
        let idle = ChannelSandboxLight(sessionId: "chn-1", status: .idle)
        XCTAssertEqual(running.labelKey, "channel.sandbox.running")
        XCTAssertEqual(running.tone, .success)
        XCTAssertEqual(idle.labelKey, "channel.sandbox.idle")
        XCTAssertEqual(idle.tone, .textTertiary)
    }

    /// A session-less row does not become interesting just because a status arrived for it: the session id is
    /// checked first, because without it the id was never in the lookup's request either.
    func testAnAnswerForARowWithNoSessionStillShowsNothing() throws {
        XCTAssertEqual(
            ChannelSandboxLight(sessionId: nil, status: .running),
            .noSession
        )
    }

    // MARK: - the view model's own criteria

    func testTheViewModelAnswersTheThreeStatesSeparately() async throws {
        let catalog = FakeChannels()
        catalog.replies = [.success(try PageStub.page(
            ChannelSummary.self,
            [
                ["name": "有会话且在跑", "id": 1, "type": "feishu", "status": 1, "sessionId": "s-1"],
                ["name": "有会话但没上报", "id": 2, "type": "feishu", "status": 1, "sessionId": "s-2"],
                ["name": "没有会话", "id": 3, "type": "feishu", "status": 1],
            ],
            total: 3
        ))]
        catalog.sandboxReplies = [.success(SandboxStub.map(["s-1": true]))]
        let vm = ChannelListViewModel(catalog: catalog)
        await vm.refresh()

        XCTAssertEqual(vm.sandboxLight(of: vm.items[0]), .running)
        XCTAssertEqual(vm.sandboxLight(of: vm.items[1]), .unanswered, "asked for both ids, only one came back")
        XCTAssertEqual(vm.sandboxLight(of: vm.items[2]), .noSession)
        XCTAssertEqual(
            vm.sandboxStatus(of: vm.items[1]), vm.sandboxStatus(of: vm.items[2]),
            "the runtime status still collapses them — the row is what separates them"
        )
    }

    func testAFailedLookupLeavesEverySessionedRowNeutralAndTheSessionlessOneBare() async throws {
        let catalog = FakeChannels()
        catalog.replies = [.success(try PageStub.page(
            ChannelSummary.self,
            [
                ["name": "有会话", "id": 1, "type": "feishu", "status": 1, "sessionId": "s-1"],
                ["name": "无会话", "id": 2, "type": "feishu", "status": 1],
            ],
            total: 2
        ))]
        catalog.sandboxReplies = [.failure(.offline)]
        let vm = ChannelListViewModel(catalog: catalog)
        await vm.refresh()

        XCTAssertEqual(vm.sandboxLight(of: vm.items[0]), .unanswered)
        XCTAssertEqual(vm.sandboxLight(of: vm.items[1]), .noSession)
    }

    /// The delete path drops the remembered answer, and what is left must be the honest three states rather
    /// than a stale light on a row that no longer exists.
    func testARemovedRowTakesItsAnswerWithIt() async throws {
        let catalog = FakeChannels()
        catalog.replies = [.success(try PageStub.page(
            ChannelSummary.self,
            [
                ["name": "留下的一行", "id": 1, "type": "feishu", "status": 1, "sessionId": "s-1"],
                ["name": "删除的一行", "id": 2, "type": "feishu", "status": 1, "sessionId": "s-2"],
            ],
            total: 2
        ))]
        catalog.sandboxReplies = [.success(SandboxStub.map(["s-1": true, "s-2": true]))]
        catalog.deleteReplies = [.success(EmptyResponse())]
        let vm = ChannelListViewModel(catalog: catalog)
        await vm.refresh()
        XCTAssertEqual(vm.sandboxLight(of: vm.items[1]), .running)

        vm.beginDelete(vm.items[1])
        await vm.confirmDelete()
        XCTAssertEqual(vm.items.count, 1)
        XCTAssertEqual(vm.sandboxLight(of: vm.items[0]), .running, "the surviving row keeps its answer")
    }
}

/// The render half of item 4. A capsule that is always drawn cannot express "nothing to say", so the gate is
/// that the screen asks the light rather than the runtime status.
final class ChannelSandboxRenderGateTests: XCTestCase {
    func testTheRowDrawsTheCapsuleConditionally() throws {
        let text = try SystemDomainSources.contents(of: "HarnaxFeatures/SystemDomain/ChannelListView.swift")
        let chips = try SystemDomainSources.block(text, from: "private var chips")
        XCTAssertTrue(
            chips.contains("if let key = sandbox.labelKey"),
            "a session-less row must draw no capsule, so the column has to be conditional"
        )
        XCTAssertTrue(
            chips.contains("tone: sandbox.tone"),
            "and the colour comes from the light, not from a switch the row keeps to itself"
        )
    }

    func testTheScreenHoldsNoSandboxSwitchOfItsOwn() throws {
        let text = try SystemDomainSources.contents(of: "HarnaxFeatures/SystemDomain/ChannelListView.swift")
        XCTAssertFalse(
            text.contains("case .unknown"),
            "a second copy of the wording lives in the light; a switch here would drift from it"
        )
        // Line-scoped, not file-scoped: the WeChat 「未绑定」 chip legitimately wears the warning slot, and a
        // gate that reads "this file mentions sandbox somewhere and .warning somewhere" fires on that too.
        let offenders = text.components(separatedBy: "\n").filter {
            $0.contains("sandbox") && $0.contains(".warning")
        }
        XCTAssertTrue(
            offenders.isEmpty,
            "the old bug was the sandbox column reaching for the warning slot: \(offenders)"
        )
    }

    func testTheRowIsHandedTheLightNotTheRuntimeStatus() throws {
        let text = try SystemDomainSources.contents(of: "HarnaxFeatures/SystemDomain/ChannelListView.swift")
        XCTAssertTrue(
            text.contains("sandbox: vm.sandboxLight(of: row)"),
            "the call site has to go through the tri-state accessor"
        )
        XCTAssertTrue(
            text.contains("private let sandbox: ChannelSandboxLight"),
            "and the row's own property has to be that type, or the tri-state is lost on the way in"
        )
    }
}
