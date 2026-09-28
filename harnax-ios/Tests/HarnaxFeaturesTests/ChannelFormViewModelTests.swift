import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// E2 — the channel form, create and edit in one type.
///
/// The three behaviours these tests exist to hold:
/// - the credential fields are a matrix of the *pair* of type and mode, and `canSubmit` reads that matrix;
/// - `configJson` is merged over what the server stored rather than rewritten, because the WeChat scan keeps
///   its tokens in the same blob (`UpdateForm.tsx:110-121`);
/// - an untouched secret goes back as the mask the read path handed out, which is how the server knows to keep
///   the real value (`ChannelServiceImpl.kt:304-309`). There is no client-side "unchanged" flag because the
///   wire already carries one.
@MainActor
final class ChannelFormViewModelTests: XCTestCase {
    private var catalog = FakeChannels()
    private var agents = FakeAgents()

    /// A row as the wire makes it. `configJson` really is a string, and a secret inside it really is the mask.
    private func summary(_ fields: [String: Any]) throws -> ChannelSummary {
        try ChannelSummary.stub(fields)
    }

    private func config(of text: String?) -> ChannelConfig {
        ChannelConfig(json: text)
    }

    private func makeForm(row: ChannelSummary? = nil) -> ChannelFormViewModel {
        ChannelFormViewModel(row: row, catalog: catalog, agents: agents)
    }

    private func fill(
        _ vm: ChannelFormViewModel,
        name: String = "运营助理",
        type: ChannelType = .wecom,
        agent: Int64 = 7
    ) {
        vm.name = name
        vm.setType(type)
        vm.agentId = agent
        for field in vm.visibleFields {
            vm.setFieldValue("值", for: field)
        }
    }

    // MARK: - the matrix

    func testACreateFormStartsBlankWithAutoStartOn() {
        let vm = makeForm()
        XCTAssertTrue(vm.name.isEmpty)
        XCTAssertNil(vm.typeChoice)
        XCTAssertTrue(vm.visibleFields.isEmpty, "no type picked, so there is no field matrix yet")
        XCTAssertFalse(vm.showsModePicker, "no type picked, so there is nothing to pick a mode for")
        XCTAssertTrue(vm.autoStart, "auto-listen at startup is the insert's default")
        XCTAssertEqual(vm.thinkingChoice, .followModel)
        XCTAssertNil(vm.immutableSessionId)
        XCTAssertNil(vm.callbackUrl)
    }

    func testPickingATypeResolvesTheModeItsServiceCanRun() {
        let vm = makeForm()
        vm.setType(.feishu)
        XCTAssertEqual(vm.modeChoice, .websocket)
        XCTAssertTrue(vm.showsModePicker, "Feishu really can run two of them")
        vm.setType(.wecom)
        XCTAssertEqual(vm.modeChoice, .websocket)
        XCTAssertFalse(vm.showsModePicker, "WeCom has exactly one mode, so a one-option menu is noise")
        vm.setType(.wechat)
        XCTAssertEqual(vm.modeChoice, .longPolling)
        XCTAssertTrue(vm.showsScanHint)
        XCTAssertTrue(vm.visibleFields.isEmpty, "personal WeChat has no credentials to type")
    }

    /// Switching type keeps the mode only when the new type can actually run it, and field text is kept too:
    /// the underlying `configJson` keys are the same keys, and dropping the values here would silently undo a
    /// credential the server still has.
    func testSwitchingTypeReResolvesTheModeButKeepsTheValues() {
        let vm = makeForm()
        vm.setType(.feishu)
        vm.setMode(.webhook)
        XCTAssertEqual(vm.visibleFields.count, 4, "the callback pair joins the list in webhook mode")
        vm.setFieldValue("cli_a1", for: vm.visibleFields[0])

        vm.setType(.dingtalk)
        XCTAssertEqual(vm.modeChoice, .stream, "DingTalk cannot run a webhook")
        XCTAssertEqual(vm.fieldValue(for: vm.visibleFields[0]), "cli_a1")

        vm.setType(.feishu)
        XCTAssertEqual(vm.modeChoice, .websocket, "…and coming back does not resurrect the dead webhook")
    }

    func testAModeTheTypeCannotRunIsRefusedOutright() {
        let vm = makeForm()
        vm.setType(.feishu)
        vm.setMode(.stream)
        XCTAssertEqual(vm.modeChoice, .websocket)
    }

    /// An old row can still hold a mode its type cannot run; the form shows the runnable one instead of the
    /// dead stored one, because sending the stored pair is what produces a channel that receives nothing.
    func testAStoredModeTheTypeCannotRunIsReplacedOnEdit() throws {
        let row = try summary([
            "id": 1, "name": "n", "type": "feishu", "communicationMode": "stream", "agentId": 3,
        ])
        let vm = makeForm(row: row)
        XCTAssertEqual(vm.modeChoice, .websocket)
    }

    // MARK: - canSubmit

    func testANewChannelNeedsItsNameAgentAndRequiredCredentials() {
        let vm = makeForm()
        XCTAssertFalse(vm.canSubmit, "no type yet, so there is no create body to build")
        fill(vm)
        for field in vm.visibleFields { vm.setFieldValue("", for: field) }
        XCTAssertFalse(vm.canSubmit, "WeCom needs both halves of its credential")
        vm.setFieldValue("bot-1", for: vm.visibleFields[0])
        XCTAssertFalse(vm.canSubmit, "one of two is not enough")
        vm.setFieldValue("secret", for: vm.visibleFields[1])
        XCTAssertTrue(vm.canSubmit)

        vm.agentId = nil
        XCTAssertFalse(vm.canSubmit, "agentId is @NotNull on the wire")
        vm.name = "   "
        XCTAssertFalse(vm.canSubmit, "a blank name is not a name")
    }

    func testTheNameLimitIsTheColumnWidthNotTheFieldWidth() {
        let vm = makeForm()
        fill(vm)
        vm.name = String(repeating: "名", count: 100)
        XCTAssertTrue(vm.canSubmit)
        vm.name = String(repeating: "名", count: 101)
        XCTAssertFalse(vm.canSubmit)
        vm.name = "  " + String(repeating: "名", count: 100) + "  "
        XCTAssertTrue(vm.canSubmit, "the padding is not part of the column")
    }

    /// A WeChat create has no fields at all, so the matrix's "required" set is empty and the form is submittable
    /// as soon as it has a name and an agent.
    func testWechatCanBeCreatedWithNoCredentials() {
        let vm = makeForm()
        fill(vm, type: .wechat)
        XCTAssertTrue(vm.canSubmit)
    }

    /// Feishu webhook is the one mode-conditional requirement; Feishu over WebSocket is not.
    func testTheCallbackPairIsOnlyRequiredInWebhookMode() {
        let vm = makeForm()
        fill(vm, type: .feishu)
        XCTAssertTrue(vm.canSubmit, "websocket Feishu needs only app id and secret")
        vm.setMode(.webhook)
        for field in vm.visibleFields { vm.setFieldValue("", for: field) }
        vm.setFieldValue("cli", for: vm.visibleFields[0])
        vm.setFieldValue("secret", for: vm.visibleFields[1])
        XCTAssertFalse(vm.canSubmit, "without the Encrypt Key the platform refuses to sign the callback")
        vm.setFieldValue("aes", for: vm.visibleFields[2])
        XCTAssertFalse(vm.canSubmit)
        vm.setFieldValue("token", for: vm.visibleFields[3])
        XCTAssertTrue(vm.canSubmit)
    }

    // MARK: - the create body

    func testACreateWithNoCredentialsLeavesConfigJsonOffTheBody() async throws {
        let vm = makeForm()
        fill(vm, type: .wechat)
        catalog.createReplies = [.success(EmptyResponse())]
        await vm.save()
        let draft = try XCTUnwrap(catalog.createRequests.first)
        XCTAssertNil(draft.configJson, "a create sends nothing rather than an empty object")
        XCTAssertEqual(draft.type, "wechat")
        XCTAssertEqual(draft.communicationMode, "long_polling")
        XCTAssertTrue(vm.saved)
    }

    func testACreateSendsTheFilledCredentialsAsOneBlob() async throws {
        let vm = makeForm()
        fill(vm)
        vm.setFieldValue("bot-1", for: vm.visibleFields[0])
        vm.setFieldValue("s-1", for: vm.visibleFields[1])
        catalog.createReplies = [.success(EmptyResponse())]
        await vm.save()
        let draft = try XCTUnwrap(catalog.createRequests.first)
        XCTAssertEqual(config(of: draft.configJson).text(for: .appId), "bot-1")
        XCTAssertEqual(config(of: draft.configJson).text(for: .appSecret), "s-1")
        XCTAssertEqual(draft.name, "运营助理")
        XCTAssertEqual(draft.agentId, 7)
        XCTAssertEqual(catalog.updateRequests.count, 0)
    }

    /// `enabled` is auto-listen at startup and the three capability switches are 0/1 columns, not booleans.
    func testTheSwitchesGoOutAsIntegers() async throws {
        let vm = makeForm()
        fill(vm)
        vm.autoStart = false
        vm.searchEnabled = true
        vm.planEnabled = true
        catalog.createReplies = [.success(EmptyResponse())]
        await vm.save()
        let draft = try XCTUnwrap(catalog.createRequests.first)
        XCTAssertEqual(draft.enabled, 0)
        XCTAssertEqual(draft.enableSearch, 1)
        XCTAssertEqual(draft.enablePlan, 1)
    }

    /// Omitting `enableThink` is how the form says "whatever the bound model wants"; picking either position
    /// sends a real answer.
    func testFollowingTheModelMeansTheFieldIsAbsentNotZero() async throws {
        let vm = makeForm()
        fill(vm)
        catalog.createReplies = [.success(EmptyResponse()), .success(EmptyResponse())]
        await vm.save()
        XCTAssertNil(try XCTUnwrap(catalog.createRequests.first).enableThink)

        vm.thinkingChoice = .off
        await vm.save()
        XCTAssertEqual(try XCTUnwrap(catalog.createRequests.last).enableThink, 0)
    }

    // MARK: - the edit body

    private func editedRow(extraConfig: [String: String] = [:]) throws -> ChannelSummary {
        var blob: [String: String] = ["appId": "bot-1", "appSecret": "ab****cd"]
        for (key, value) in extraConfig { blob[key] = value }
        let data = try JSONSerialization.data(withJSONObject: blob)
        return try summary([
            "id": 12, "name": "旧渠道", "type": "wecom", "communicationMode": "websocket",
            "agentId": 3, "agentName": "翻译", "enabled": 1, "status": 1,
            "sessionId": "chn-fixed", "callbackUrl": "https://example.com/callback",
            "description": "备注", "configJson": String(decoding: data, as: UTF8.self),
        ])
    }

    func testAnEditOpensOnTheRowRatherThanOnDefaults() throws {
        let vm = makeForm(row: try editedRow())
        XCTAssertEqual(vm.name, "旧渠道")
        XCTAssertEqual(vm.typeChoice, .wecom)
        XCTAssertEqual(vm.modeChoice, .websocket)
        XCTAssertEqual(vm.agentId, 3)
        XCTAssertTrue(vm.autoStart)
        XCTAssertEqual(vm.description, "备注")
        XCTAssertEqual(vm.thinkingChoice, .off, "a stored 0 is a decision already made, not “follow the model”")
        XCTAssertEqual(vm.fieldValue(for: vm.visibleFields[0]), "bot-1")
        XCTAssertEqual(vm.fieldValue(for: vm.visibleFields[1]), "ab****cd")
        XCTAssertEqual(vm.immutableSessionId, "chn-fixed")
        XCTAssertEqual(vm.callbackUrl, "https://example.com/callback")
    }

    /// The untouched secret is sent back as the mask and the service recognises its own display form, which is
    /// why there is no client-side dirty flag on this form.
    func testAnUntouchedSecretGoesBackAsTheMaskItArrivedAs() async throws {
        let row = try editedRow()
        let vm = makeForm(row: row)
        catalog.updateReplies = [.success(EmptyResponse())]
        await vm.save()
        let change = try XCTUnwrap(catalog.updateRequests.first).change
        XCTAssertEqual(config(of: change.configJson).text(for: .appSecret), "ab****cd")
        XCTAssertEqual(config(of: change.configJson).text(for: .appId), "bot-1")
    }

    /// The scan writes `botToken`, `userId`, `botId` and `baseUrl` into the same blob and the form has no field
    /// for any of them. A save that only knows the managed keys has to leave the rest exactly as it arrived —
    /// rewriting the whole blob is what used to erase them.
    func testAnEditCarriesTheScanKeysItKnowsNothingAbout() async throws {
        let row = try editedRow(extraConfig: [
            "botToken": "tok-from-scan", "userId": "u-1", "retry": "5",
        ])
        let vm = makeForm(row: row)
        catalog.updateReplies = [.success(EmptyResponse())]
        await vm.save()
        let stored = config(of: try XCTUnwrap(catalog.updateRequests.first).change.configJson)
        XCTAssertEqual(stored.text(for: "botToken"), "tok-from-scan")
        XCTAssertEqual(stored.text(for: "userId"), "u-1")
        XCTAssertEqual(stored.text(for: "retry"), "5")
    }

    func testAnEmptiedCredentialFieldActuallyLeavesTheBlob() async throws {
        let vm = makeForm(row: try editedRow())
        vm.setFieldValue("", for: vm.visibleFields[1])
        catalog.updateReplies = [.success(EmptyResponse())]
        await vm.save()
        let stored = config(of: try XCTUnwrap(catalog.updateRequests.first).change.configJson)
        XCTAssertNil(stored.text(for: .appSecret), "clearing the field is how the console reads a cleared secret")
        XCTAssertEqual(stored.text(for: .appId), "bot-1")
    }

    /// An edit wants `{}` where a create wants nothing: the key has to be present for the cleared credentials
    /// to be cleared server-side.
    func testAnEditThatClearsEverythingSendsAnEmptyObject() async throws {
        let vm = makeForm(row: try editedRow())
        for field in vm.visibleFields { vm.setFieldValue("  ", for: field) }
        catalog.updateReplies = [.success(EmptyResponse())]
        await vm.save()
        XCTAssertEqual(try XCTUnwrap(catalog.updateRequests.first).change.configJson, "{}")
    }

    func testAnEditAddressesTheRowByIdAndSendsTheWholePatch() async throws {
        let vm = makeForm(row: try editedRow())
        vm.name = "改名"
        catalog.updateReplies = [.success(EmptyResponse())]
        await vm.save()
        let (id, change) = try XCTUnwrap(catalog.updateRequests.first)
        XCTAssertEqual(id, 12)
        XCTAssertEqual(change.name, "改名")
        XCTAssertEqual(change.type, "wecom")
        XCTAssertEqual(change.communicationMode, "websocket")
        XCTAssertEqual(change.agentId, 3)
        XCTAssertEqual(change.enableThink, 0, "on edit there is no follow-model position: the stored 0 goes back as 0")
        XCTAssertEqual(catalog.createRequests.count, 0, "and it never POSTs a second channel")
    }

    /// On edit only Feishu's callback pair is required — every other field has a stored value behind it.
    func testAnEditDoesNotDemandFieldsTheRowAlreadyHas() throws {
        let vm = makeForm(row: try editedRow())
        for field in vm.visibleFields {
            XCTAssertFalse(vm.isRequired(field))
        }
    }

    func testAFeishuWebhookEditStillDemandsTheCallbackPair() throws {
        let data = try JSONSerialization.data(withJSONObject: ["appId": "a"])
        let row = try summary([
            "id": 4, "type": "feishu", "communicationMode": "webhook", "agentId": 1,
            "configJson": String(decoding: data, as: UTF8.self),
        ])
        let vm = makeForm(row: row)
        for field in vm.visibleFields {
            XCTAssertTrue(vm.isRequired(field), "\(field.key.rawValue) is the pair the platform signs with")
        }
        XCTAssertFalse(vm.canSubmit, "and the masked read path cannot pre-fill them")
    }

    /// The update route is addressed by the row id alone, so a row the wire gave no id for cannot be written —
    /// and falling through to the create branch would POST a duplicate.
    func testARowWithNoIdIsRefusedInsteadOfCreatedAgain() async throws {
        let row = try summary(["name": "n", "type": "wecom", "agentId": 1])
        let vm = makeForm(row: row)
        await vm.save()
        XCTAssertFalse(vm.saved)
        XCTAssertEqual(vm.errorText, hx("error.unpackable"))
        XCTAssertTrue(catalog.updateRequests.isEmpty)
        XCTAssertTrue(catalog.createRequests.isEmpty)
    }

    /// A required-thinking model refuses an explicit off and the answer is the server's own sentence.
    func testARefusedSaveShowsWhatTheServerSaid() async throws {
        let vm = makeForm(row: try editedRow())
        catalog.updateReplies = [.failure(.business(code: 500, message: "该模型必须开启思考模式"))]
        await vm.save()
        XCTAssertFalse(vm.saved)
        XCTAssertEqual(vm.errorText, "该模型必须开启思考模式")
    }

    func testSavingRefusesASecondTapWhileTheFirstIsOut() async throws {
        let vm = makeForm()
        fill(vm)
        catalog.gateWrites = true
        catalog.createReplies = [.success(EmptyResponse())]
        let write = Task { await vm.save() }
        try await waitUntil { self.catalog.createRequests.count == 1 }
        XCTAssertTrue(vm.isSaving)
        XCTAssertFalse(vm.canSubmit, "a form mid-save is not a form that can start another save")
        catalog.releaseWrites()
        await write.value
        XCTAssertFalse(vm.isSaving)
        XCTAssertTrue(vm.saved)
        XCTAssertEqual(catalog.createRequests.count, 1, "…and the second tap never reached the wire")
    }

    // MARK: - the agent picker

    func testThePickerAsksForTheConsolesFirstHundredAndNamesEachOption() async throws {
        let vm = makeForm()
        agents.replies = [.success(try PageStub.page([
            ["id": 1, "name": "翻译"],
            ["id": 2, "name": "运营"],
            ["name": "无 id"],
            ["id": 3],
        ]))]
        await vm.loadAgents()
        XCTAssertEqual(vm.agents, [
            ChannelFormViewModel.AgentOption(id: 1, name: "翻译"),
            ChannelFormViewModel.AgentOption(id: 2, name: "运营"),
        ], "a row with no id cannot be addressed and a row with no name cannot be shown")
        XCTAssertEqual(agents.requests.map(\.num), [1])
        XCTAssertEqual(agents.requests.map(\.size), [100])

        agents.replies = [.success(try PageStub.page([]))]
        await vm.loadAgents()
        XCTAssertEqual(agents.requests.count, 1, "the picker loads once, not once per appear")
    }

    /// An edit of a channel whose agent is not on the first page still has to name it, and the row carries the
    /// name for exactly that case.
    func testTheBoundAgentIsAppendedWhenThePageDoesNotCarryIt() async throws {
        let vm = makeForm(row: try editedRow())
        agents.replies = [.success(try PageStub.page([["id": 9, "name": "别的"]]))]
        await vm.loadAgents()
        XCTAssertEqual(vm.agents.last?.id, 3)
        XCTAssertEqual(vm.agents.last?.name, "翻译")
        XCTAssertEqual(vm.agentTitle, "翻译")

        let anonymous = makeForm(row: try summary([
            "id": 5, "name": "n", "type": "wecom", "agentId": 42, "communicationMode": "websocket",
        ]))
        agents.replies = [.success(try PageStub.page([["id": 9, "name": "别的"]]))]
        await anonymous.loadAgents()
        XCTAssertEqual(anonymous.agents.last?.name, "#42", "with no agentName the id is the only honest label")
        XCTAssertEqual(anonymous.agentTitle, "#42")
    }

    /// A picker that cannot list agents is not a form that failed to save: the retry belongs on the field.
    func testAPickerFailureSitsOnTheFieldNotOnTheSaveError() async throws {
        let vm = makeForm()
        agents.replies = [.failure(.offline)]
        await vm.loadAgents()
        XCTAssertEqual(vm.agentErrorText, hx("error.offline"))
        XCTAssertNil(vm.errorText)
        XCTAssertTrue(vm.agents.isEmpty)
    }

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }
}
