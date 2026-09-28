import XCTest
import HarnaxCore
import HarnaxKit
import HarnaxFeatures

/// The two accounts this sheet reads to decide whether a tenant may be addressed at all
/// (`ApiKeyServiceImpl.kt:72-78`, `:122-127`).
private let adminAccount = AccountSnapshot(username: "admin", tenantID: 1, isAdministrator: true)
private let memberAccount = AccountSnapshot(username: "bob", tenantID: 7)

/// The two bodies this sheet has to satisfy are not the same shape, and the tests have to say so.
///
/// A create carries a `name` and no `enabled`; an update carries `enabled`, has nowhere to put a `name`, and
/// leaves every column the user did not touch off the body entirely (`ApiKeyCreateRequest.kt:8-26`,
/// `ApiKeyUpdateRequest.kt:6-21`). `expiresAt` is the sharpest of those: a `nil` means “keep the column”, an
/// empty string is the only way back to “never expires”, and the value itself has to arrive as
/// `ISO_LOCAL_DATE_TIME` because the service parses it with a `T` and nothing else
/// (`ApiKeyServiceImpl.kt:95`, `:130-132`). The raw key exists on this type for one reason only — the create
/// route hands it back and no other path ever does.
@MainActor
final class ApiKeyFormViewModelTests: XCTestCase {
    /// Rows are built the way the page answers them, so the decode decides the shape — `enabled` as the raw
    /// 0/1 column and `expiresAt` as the server's wall-clock text.
    private func existing(_ overrides: [String: Any] = [:]) throws -> ApiKeySummary {
        var fields: [String: Any] = [
            "id": 7,
            "name": "线上密钥",
            "scopes": "chat",
            "tenantId": 7,
            "rateLimit": 60,
            "enabled": 1,
            "creator": "admin",
        ]
        for (key, value) in overrides { fields[key] = value }
        let data = try JSONSerialization.data(withJSONObject: fields)
        return try JSONDecoder().decode(ApiKeySummary.self, from: data)
    }

    /// What the body actually looks like on the way out: a field the user never touched has to be *absent*,
    /// not zero, not empty, and not a repeat of what the row already holds.
    private func wire(_ body: some Encodable) throws -> [String: Any] {
        let object = try JSONSerialization.jsonObject(with: try JSONEncoder().encode(body))
        return try XCTUnwrap(object as? [String: Any])
    }

    private func date(_ year: Int, _ month: Int, _ day: Int, _ hour: Int = 3, _ minute: Int = 4, _ second: Int = 5) throws -> Date {
        var components = DateComponents()
        components.year = year
        components.month = month
        components.day = day
        components.hour = hour
        components.minute = minute
        components.second = second
        return try XCTUnwrap(Calendar.current.date(from: components))
    }

    private func made() -> ApiKeyCreatedSummary {
        ApiKeyCreatedSummary(
            id: 42,
            name: "线上密钥",
            rawKey: "hnx_sk_live_abcdefghijklmnopqrstuvwxyz",
            keyPrefix: "hnx_sk_live_ab...wxyz"
        )
    }

    /// A create the only way it can be posted: name in, one permission ticked.
    private func createForm(_ keys: FakeApiKeys = FakeApiKeys(), account: AccountSnapshot? = adminAccount) -> ApiKeyFormViewModel {
        let vm = ApiKeyFormViewModel(row: nil, account: account, catalog: keys)
        vm.name = "线上密钥"
        vm.selectedScopes = [.chat]
        return vm
    }

    private func editForm(
        _ overrides: [String: Any] = [:],
        account: AccountSnapshot? = adminAccount,
        _ keys: FakeApiKeys = FakeApiKeys()
    ) throws -> (ApiKeyFormViewModel, FakeApiKeys) {
        let row = try existing(overrides)
        return (ApiKeyFormViewModel(row: row, account: account, catalog: keys), keys)
    }

    // MARK: - what the sheet opens with

    func testACreateOpensWithTheConsolesOwnDefaultAndNoPermissionChosen() throws {
        let vm = ApiKeyFormViewModel(row: nil, account: memberAccount, catalog: FakeApiKeys())
        XCTAssertFalse(vm.isEditing)
        XCTAssertEqual(vm.rateLimitText, "60", "the same initial value the console seeds the form with")
        XCTAssertTrue(vm.enabled)
        XCTAssertFalse(vm.hasExpiry)
        XCTAssertTrue(vm.selectedScopes.isEmpty)
        XCTAssertFalse(vm.showsTenantField, "the service ignores what a non-admin sends for a tenant anyway")
        XCTAssertNil(vm.published)
        XCTAssertFalse(vm.saved)
        XCTAssertEqual(vm.validationErrorKey, "apikey.name.required", "the name gate is decided before the scope gate")
        XCTAssertFalse(vm.canSubmit)
    }

    func testTheTenantFieldBelongsToAnAdministratorAndToNobodyElse() throws {
        let keys = FakeApiKeys()
        XCTAssertTrue(ApiKeyFormViewModel(row: nil, account: adminAccount, catalog: keys).showsTenantField)
        XCTAssertFalse(ApiKeyFormViewModel(row: nil, account: memberAccount, catalog: keys).showsTenantField)
        XCTAssertFalse(
            ApiKeyFormViewModel(row: nil, account: nil, catalog: keys).showsTenantField,
            "with no account on screen there is no administrator to write as"
        )
    }

    func testAnEditOpensWithWhatTheRowCarriesRatherThanWhatThisFormWouldHaveChosen() throws {
        let (vm, _) = try editForm(
            ["scopes": "chat,metrics", "rateLimit": 300, "enabled": 0, "expiresAt": "2030-01-02 03:04:05"],
            account: memberAccount
        )
        XCTAssertTrue(vm.isEditing)
        XCTAssertEqual(vm.name, "线上密钥")
        XCTAssertEqual(vm.selectedScopes, [.chat])
        XCTAssertEqual(vm.submittedScopes, "chat,metrics", "a token with no switch here is still a fact about the row")
        XCTAssertEqual(vm.rateLimitText, "300", "the stored limit, not the create default")
        XCTAssertEqual(vm.tenantText, "7")
        XCTAssertFalse(vm.enabled, "`enabled` is the 0/1 column and 0 means disabled")
        XCTAssertTrue(vm.hasExpiry)
        XCTAssertEqual(vm.expiryDate, try date(2030, 1, 2), "the space form the response uses has to be readable")
        XCTAssertNil(vm.validationErrorKey, "an edit that changes nothing is a valid edit")
        XCTAssertFalse(vm.showsTenantField)
    }

    // MARK: - the gates that keep a request out

    func testAKeyWithoutANameIsRefusedBeforeAnythingGoesOut() async throws {
        let keys = FakeApiKeys()
        let vm = ApiKeyFormViewModel(row: nil, account: adminAccount, catalog: keys)
        vm.name = "   "
        vm.selectedScopes = [.chat]
        XCTAssertEqual(vm.validationErrorKey, "apikey.name.required")
        XCTAssertNil(vm.draft, "nothing is buildable while the name is blank")
        await vm.save()
        XCTAssertTrue(keys.createRequests.isEmpty, "a refused form does not reach the server at all")
        XCTAssertEqual(vm.errorText, hx("apikey.name.required"))
        XCTAssertFalse(vm.saved)
    }

    func testTheNameLimitIsTheColumnWidthNotARoundNumberBelowIt() async throws {
        let keys = FakeApiKeys()
        let vm = createForm(keys)
        vm.name = String(repeating: "长", count: 129)
        XCTAssertEqual(vm.validationErrorKey, "apikey.name.long")
        await vm.save()
        XCTAssertTrue(keys.createRequests.isEmpty)
        vm.name = String(repeating: "长", count: 128)
        XCTAssertNil(vm.validationErrorKey)
        keys.createReplies = [.success(made())]
        await vm.save()
        XCTAssertEqual(keys.createRequests.count, 1)
        XCTAssertEqual(keys.createRequests.last?.name, String(repeating: "长", count: 128))
    }

    func testAKeyWithNoPermissionChosenIsRefusedEvenWithAPerfectName() async throws {
        let keys = FakeApiKeys()
        let vm = createForm(keys)
        vm.selectedScopes = []
        XCTAssertEqual(vm.validationErrorKey, "apikey.scopes.required")
        await vm.save()
        XCTAssertTrue(keys.createRequests.isEmpty)
        XCTAssertEqual(vm.errorText, hx("apikey.scopes.required"))
    }

    func testThePermissionsGoOnTheBodyInCatalogueOrderWhateverTheTapOrderWas() throws {
        let vm = createForm()
        vm.selectedScopes = [.manager, .chat]
        XCTAssertEqual(vm.submittedScopes, "chat,manager", "the server stores no order, so the client fixes one")
    }

    func testABadNumberRefusesTheFormBeforeTheDatabaseGetsToSaySo() throws {
        let vm = createForm()
        vm.rateLimitText = "很多"
        XCTAssertEqual(vm.validationErrorKey, "apikey.rate.invalid")
        vm.rateLimitText = "0"
        XCTAssertEqual(vm.validationErrorKey, "apikey.rate.range")
        vm.rateLimitText = "10001"
        XCTAssertEqual(vm.validationErrorKey, "apikey.rate.range")
        vm.rateLimitText = "1"
        XCTAssertNil(vm.validationErrorKey, "the floor is inside the range the console allows")
        vm.rateLimitText = "10000"
        XCTAssertNil(vm.validationErrorKey, "and so is the ceiling")
        vm.tenantText = "0"
        XCTAssertEqual(vm.validationErrorKey, "apikey.tenant.invalid", "no tenant has id 0")
        vm.tenantText = "-1"
        XCTAssertEqual(vm.validationErrorKey, "apikey.tenant.invalid")
        vm.tenantText = "5"
        XCTAssertNil(vm.validationErrorKey)
        XCTAssertEqual(vm.draft?.tenantId, 5, "a positive tenant is the only one the field accepts")
    }

    // MARK: - what an edit is allowed to change

    func testAnEditCannotRenameAnythingBecauseTheRouteHasNowhereToPutIt() async throws {
        let (vm, keys) = try editForm()
        vm.name = "   "
        XCTAssertNil(vm.validationErrorKey, "the name is a fact on this screen, not a field")
        vm.name = "改过的名字"
        vm.selectedScopes = [.chat, .manager]
        keys.updateReplies = [.success(EmptyResponse())]
        await vm.save()
        XCTAssertEqual(keys.updateRequests.map(\.id), [7])
        let body = try wire(keys.updateRequests[0].change)
        XCTAssertNil(body["name"], "the update DTO has no such column to write")
        XCTAssertEqual(body["scopes"] as? String, "chat,manager")
    }

    func testAnUntouchedEditSendsAnEmptyBodyRatherThanRepeatingTheRow() throws {
        let (vm, _) = try editForm()
        let change = try XCTUnwrap(vm.change)
        XCTAssertTrue(try wire(change).isEmpty, "a column the user never touched stays off the request")
    }

    func testARowCarryingOnlyForeignTokensIsNotHeldToTheScopeRule() throws {
        let (vm, _) = try editForm(["scopes": "billing,metrics"])
        XCTAssertTrue(vm.selectedScopes.isEmpty)
        XCTAssertNil(vm.validationErrorKey, "the server only asks that the field not be blank")
        XCTAssertEqual(vm.submittedScopes, "billing,metrics", "and neither token may be dropped on the way back")
        XCTAssertNil(vm.change?.scopes, "which also means an untouched row is not rewritten")
    }

    func testAnEditCannotDropAScopeThisFormHasNoSwitchFor() throws {
        let (vm, _) = try editForm(["scopes": "chat,billing"])
        vm.selectedScopes = []
        XCTAssertEqual(vm.submittedScopes, "billing", "the remainder survives the un-tick of a known label")
        XCTAssertEqual(vm.change?.scopes, "billing")
    }

    func testAnEditOnlySendsALimitTheUserActuallyChanged() throws {
        let (vm, _) = try editForm()
        XCTAssertEqual(vm.rateLimitText, "60")
        XCTAssertNil(vm.submittedRateLimit)
        vm.rateLimitText = "120"
        XCTAssertEqual(vm.submittedRateLimit, 120)
        vm.rateLimitText = ""
        XCTAssertNil(vm.submittedRateLimit, "blank means “leave the column alone”, and never a zero")
        XCTAssertNil(vm.change?.rateLimit)
    }

    func testABlankLimitIsLeftOffTheCreateRatherThanSentAsZero() throws {
        let vm = createForm()
        vm.rateLimitText = ""
        let draft = try XCTUnwrap(vm.draft)
        XCTAssertNil(draft.rateLimit)
        let body = try wire(draft)
        XCTAssertNil(body["rateLimit"], "an absent field is what lets the server default apply")
        XCTAssertEqual(body["name"] as? String, "线上密钥")
        XCTAssertEqual(body["scopes"] as? String, "chat")
        XCTAssertNil(body["expiresAt"], "and no expiry means the field is off the body, not blank")
    }

    func testATenantThatIsAlreadyTheRowsIsNotResent() throws {
        let (vm, _) = try editForm(account: memberAccount)
        XCTAssertFalse(vm.showsTenantField)
        XCTAssertEqual(vm.tenantText, "7", "hidden does not mean empty: the field still holds the row's value")
        XCTAssertNil(vm.submittedTenantId)
        XCTAssertNil(vm.change?.tenantId, "so a non-admin's edit can never trip the tenant refusal")
        vm.tenantText = "8"
        XCTAssertEqual(vm.submittedTenantId, 8, "and were it changed, that is exactly what the server would be told")
    }

    // MARK: - the expiry column

    func testAnExpiryIsSentInTheOneFormTheServerCanParse() throws {
        let vm = createForm()
        vm.hasExpiry = true
        vm.expiryDate = try date(2027, 3, 4, 5, 6, 7)
        let draft = try XCTUnwrap(vm.draft)
        XCTAssertEqual(draft.expiresAt, "2027-03-04T05:06:07", "ISO_LOCAL_DATE_TIME, with the T")
        XCTAssertFalse(draft.expiresAt?.contains(" ") ?? true, "the space form the response uses would throw")
    }

    func testNoExpiryOnACreateLeavesTheColumnOffTheBody() throws {
        let vm = createForm()
        XCTAssertFalse(vm.hasExpiry)
        XCTAssertNil(vm.submittedExpiry, "never expires is the column being absent, not an empty string")
    }

    func testAnEditSendsOnlyAStampTheUserMovedAndClearingItIsTheOnlyWayBackToNever() throws {
        let (vm, _) = try editForm(["expiresAt": "2030-01-02 03:04:05"])
        XCTAssertNil(vm.submittedExpiry, "the stored stamp re-reads as the same instant, so it is not a change")
        vm.expiryDate = try date(2031, 6, 7, 8, 9, 10)
        XCTAssertEqual(vm.submittedExpiry, "2031-06-07T08:09:10")
        vm.hasExpiry = false
        XCTAssertEqual(vm.submittedExpiry, "", "the blank the service branches on is the one route to “never”")
        XCTAssertEqual(vm.change?.expiresAt, "")
    }

    func testClearingAnExpiryTheRowNeverHadSendsNothingButSettingOneSendsTheStamp() throws {
        let (vm, _) = try editForm()
        XCTAssertFalse(vm.hasExpiry)
        XCTAssertNil(vm.submittedExpiry, "there is nothing to clear, so the column stays out of the body")
        vm.hasExpiry = true
        XCTAssertEqual(vm.submittedExpiry, hxServerDateTimeString(vm.expiryDate))
        XCTAssertEqual(vm.change?.expiresAt, hxServerDateTimeString(vm.expiryDate))
    }

    // MARK: - the enabled column, which only exists on an edit

    func testTheEnabledColumnOnlyExistsOnAnEdit() throws {
        let create = createForm()
        create.enabled = false
        XCTAssertNil(create.submittedEnabled, "the create route always writes 1 (`ApiKeyServiceImpl.kt:94`)")
        XCTAssertNil(create.change, "and there is no update body to put the switch in")

        let (on, _) = try editForm()
        XCTAssertNil(on.submittedEnabled, "the switch as it opened is not a change")
        on.enabled = false
        XCTAssertEqual(on.submittedEnabled, 0)
        XCTAssertEqual(on.change?.enabled, 0)

        let (off, _) = try editForm(["enabled": 0])
        XCTAssertNil(off.submittedEnabled)
        off.enabled = true
        XCTAssertEqual(off.submittedEnabled, 1)
    }

    // MARK: - save

    func testACreateKeepsTheOneTimeKeyInTheSheetThatProducedIt() async throws {
        let keys = FakeApiKeys()
        let vm = createForm(keys)
        keys.createReplies = [.success(made())]
        await vm.save()
        XCTAssertEqual(keys.createRequests, [try XCTUnwrap(vm.draft)])
        XCTAssertTrue(vm.saved)
        XCTAssertEqual(vm.published, made())
        XCTAssertNil(vm.errorText)
        XCTAssertFalse(vm.isSaving, "the sheet is idle again once the answer has been read")
    }

    func testAKeyIsNotClaimedCreatedWhileTheStackIsStillAnswering() async throws {
        let keys = FakeApiKeys()
        let vm = createForm(keys)
        keys.gateWrites = true
        keys.createReplies = [.success(made())]
        let save = Task { await vm.save() }
        try await waitUntil { keys.createRequests.count == 1 }
        XCTAssertFalse(vm.saved, "a draft that has not landed must not close the sheet")
        XCTAssertNil(vm.published, "and no raw key may be shown before the server has minted one")
        keys.releaseWrites()
        await save.value
        XCTAssertTrue(vm.saved)
        XCTAssertEqual(vm.published?.rawKey, made().rawKey)
    }

    func testARefusedCreateShowsTheServersSentenceAndClaimsNothing() async throws {
        let keys = FakeApiKeys()
        let vm = createForm(keys)
        // The name is globally unique and the clash message names the key it hit
        // (`ApiKeyServiceImpl.kt:65-67`), so it is shown exactly as it arrived.
        keys.createReplies = [.failure(.business(code: 500, message: "API Key name already exists: 线上密钥"))]
        await vm.save()
        XCTAssertEqual(vm.errorText, "API Key name already exists: 线上密钥")
        XCTAssertFalse(vm.saved)
        XCTAssertNil(vm.published)
        XCTAssertFalse(vm.isSaving)
    }

    func testAnEditSuccessClosesTheSheetWithoutProducingASecondKey() async throws {
        let (vm, keys) = try editForm()
        vm.selectedScopes = [.chat, .manager]
        keys.updateReplies = [.success(EmptyResponse())]
        await vm.save()
        XCTAssertTrue(vm.saved)
        XCTAssertNil(vm.published, "`PUT /update/{id}` answers no key at all")
        XCTAssertNil(vm.errorText)
        XCTAssertTrue(keys.createRequests.isEmpty, "an edit never falls through to the create route")
    }

    func testARefusedEditLeavesTheSheetOpenWithTheReason() async throws {
        let (vm, keys) = try editForm()
        vm.rateLimitText = "30"
        keys.updateReplies = [
            .failure(.business(code: 500, message: "PERMANENT API Key cannot be modified, use regenerate instead"))
        ]
        await vm.save()
        XCTAssertFalse(vm.saved, "a sheet that dismissed on a refusal would hide the only actionable sentence")
        XCTAssertEqual(vm.errorText, "PERMANENT API Key cannot be modified, use regenerate instead")
        XCTAssertFalse(vm.isSaving)
    }

    func testASuccessIsOnlyClaimedForTheWriteThatLandedAndNotForTheOneAfterIt() async throws {
        let keys = FakeApiKeys()
        let vm = createForm(keys)
        keys.createReplies = [.success(made())]
        await vm.save()
        XCTAssertTrue(vm.saved)
        vm.name = ""
        await vm.save()
        XCTAssertFalse(vm.saved, "the flag follows the write in front of it, so the sheet cannot close twice")
        XCTAssertEqual(keys.createRequests.count, 1, "and the refused attempt never reached the server")
    }

    func testARowWithoutAnIdIsNeverSavedAsANewKey() async throws {
        let keys = FakeApiKeys()
        // A row the wire gave no id for cannot be addressed at all; the create route would mint a second key
        // for a row that is already on screen.
        let vm = ApiKeyFormViewModel(row: try existing(["id": NSNull()]), account: adminAccount, catalog: keys)
        XCTAssertTrue(vm.isEditing)
        await vm.save()
        XCTAssertTrue(keys.createRequests.isEmpty)
        XCTAssertTrue(keys.updateRequests.isEmpty)
        XCTAssertEqual(vm.errorText, hx("error.unpackable"))
        XCTAssertFalse(vm.saved)
        XCTAssertFalse(vm.isSaving)
    }

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }
}
