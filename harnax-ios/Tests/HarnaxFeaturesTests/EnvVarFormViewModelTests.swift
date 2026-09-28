import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// S3's form, on the two things that can only be checked from the outside: what refuses to go out at all,
/// and what does go out.
///
/// The validation cases matter because the backend enforces the same rules as annotations (`@NotBlank`,
/// `@Pattern`, `@Size` on `EnvVariableCreateRequest.kt:9-30` and `EnvVariableUpdateRequest.kt:8-24`) — a
/// local pass would not be a convenience but a server round trip answered by a message nobody can act on.
/// The shape cases matter more, because a sensitive row's value column is a mask
/// (`EnvVariableServiceImpl.kt:221-243`) and re-posting it would either be swallowed by `isUnchangedMask`
/// or, on a row that has since been switched to non-sensitive, store the asterisks as the value
/// (`:137-161`, `:288-291`). `EnvVarContractTests` reads the encoders; this reads the decisions feeding them.
@MainActor
final class EnvVarFormViewModelTests: XCTestCase {
    /// A row as the wire hands it over: a column the service left out is a column that is absent, not a nil
    /// argument, and a sensitive row's `envValue` is already the mask.
    private func storedRow(
        key: String = "LOG_LEVEL",
        value: String? = "debug",
        note: String? = nil,
        sensitive: Bool = false,
        enabled: Bool = true,
        id: Any? = 32
    ) throws -> EnvVarSummary {
        var fields: [String: Any] = ["envKey": key, "sensitive": sensitive ? 1 : 0, "enabled": enabled ? 1 : 0]
        if let value { fields["envValue"] = value }
        if let note { fields["description"] = note }
        if let id { fields["id"] = id }
        let data = try JSONSerialization.data(withJSONObject: fields)
        return try JSONDecoder().decode(EnvVarSummary.self, from: data)
    }

    private func createForm() -> (EnvVarFormViewModel, FakeEnvVars) {
        let catalog = FakeEnvVars()
        return (EnvVarFormViewModel(row: nil, catalog: catalog), catalog)
    }

    private func editForm(_ row: EnvVarSummary) -> (EnvVarFormViewModel, FakeEnvVars) {
        let catalog = FakeEnvVars()
        return (EnvVarFormViewModel(row: row, catalog: catalog), catalog)
    }

    // MARK: - create

    func testAnEmptyFormIsRefusedBeforeAnyRequest() async throws {
        let (vm, catalog) = createForm()
        XCTAssertEqual(vm.validationErrorKey, "env.var.key.required")
        XCTAssertFalse(vm.canSubmit, "the primary button is dead until the form can be posted")
        XCTAssertNil(vm.draft, "and there is no body to post yet")
        await vm.save()
        XCTAssertTrue(catalog.createRequests.isEmpty)
        XCTAssertEqual(vm.errorText, hx("env.var.key.required"))
        XCTAssertFalse(vm.saved)
        XCTAssertFalse(vm.isSaving)
    }

    func testAKeyOfOnlySpacesCountsAsNoKeyAtAll() async throws {
        let (vm, catalog) = createForm()
        vm.key = "   "
        vm.value = "v"
        XCTAssertEqual(vm.validationErrorKey, "env.var.key.required", "the column is @NotBlank; spaces are not a key")
        await vm.save()
        XCTAssertTrue(catalog.createRequests.isEmpty)
    }

    func testAKeyThatIsNotAnIdentifierIsRefusedWithoutARequest() async throws {
        let (vm, catalog) = createForm()
        vm.value = "v"
        for attempt in ["LOG-LEVEL", "1LEADING_DIGIT", "WITH SPACE", "with.dot"] {
            vm.key = attempt
            XCTAssertEqual(vm.validationErrorKey, "env.var.key.invalid", "\(attempt) would be refused by @Pattern")
            await vm.save()
        }
        XCTAssertTrue(catalog.createRequests.isEmpty)
        vm.key = "_PRIVATE"
        XCTAssertNil(vm.validationErrorKey, "an underscore start is exactly what the annotation allows")
    }

    func testTheKeyLimitIsTheColumnWidthNotARoundNumber() async throws {
        let (vm, catalog) = createForm()
        vm.value = "v"
        vm.key = String(repeating: "A", count: 201)
        XCTAssertEqual(vm.validationErrorKey, "env.var.key.long")
        await vm.save()
        XCTAssertTrue(catalog.createRequests.isEmpty)
        vm.key = String(repeating: "A", count: 200)
        XCTAssertNil(vm.validationErrorKey, "@Size(max = 200) counts the boundary as inside the column")
    }

    func testABlankValueIsRefusedBecauseTheColumnIsNotBlank() async throws {
        let (vm, catalog) = createForm()
        vm.key = "LOG_LEVEL"
        XCTAssertEqual(vm.validationErrorKey, "env.var.value.required")
        await vm.save()
        XCTAssertTrue(catalog.createRequests.isEmpty)
    }

    func testTheValueLimitIsTheDtoSizeNotARoundNumber() async throws {
        let (vm, catalog) = createForm()
        vm.key = "LOG_LEVEL"
        vm.value = String(repeating: "x", count: 8193)
        XCTAssertEqual(vm.validationErrorKey, "env.var.value.long")
        await vm.save()
        XCTAssertTrue(catalog.createRequests.isEmpty)
        vm.value = String(repeating: "x", count: 8192)
        catalog.createReplies = [.success(EmptyResponse())]
        await vm.save()
        XCTAssertEqual(catalog.createRequests.count, 1, "the longest value the column takes must still go out")
    }

    func testADescriptionPastTheColumnIsRefusedAndFiveHundredStillGoOut() async throws {
        let (vm, catalog) = createForm()
        vm.key = "LOG_LEVEL"
        vm.value = "debug"
        vm.note = String(repeating: "中", count: 501)
        XCTAssertEqual(vm.validationErrorKey, "env.var.note.long")
        await vm.save()
        XCTAssertTrue(catalog.createRequests.isEmpty)
        vm.note = String(repeating: "中", count: 500)
        catalog.createReplies = [.success(EmptyResponse())]
        await vm.save()
        XCTAssertEqual(catalog.createRequests.first?.description, String(repeating: "中", count: 500))
        XCTAssertTrue(vm.saved)
    }

    func testACreatePostsADraftWithTheKeyTrimmedAndBothFlagsAsZeroOneColumns() async throws {
        let (vm, catalog) = createForm()
        vm.key = "  OPENAI_API_KEY  "
        vm.value = "sk-1"
        XCTAssertNil(vm.validationErrorKey)
        XCTAssertEqual(
            vm.draft,
            EnvVarDraft(envKey: "OPENAI_API_KEY", envValue: "sk-1", description: nil, sensitive: 0, enabled: 1),
            "a blank note stays off the body and an untouched switch goes out as the column's default"
        )
        catalog.createReplies = [.success(EmptyResponse())]
        await vm.save()
        XCTAssertEqual(catalog.createRequests, [try XCTUnwrap(vm.draft)])
        XCTAssertTrue(catalog.updateRequests.isEmpty, "a create never touches the update route")
        XCTAssertTrue(vm.saved)
        XCTAssertNil(vm.errorText)
        XCTAssertFalse(vm.isSaving)
    }

    func testTheCreateFormsOwnSwitchesRideOnTheBody() async throws {
        let (vm, catalog) = createForm()
        vm.key = "LOG_LEVEL"
        vm.value = "debug"
        vm.sensitive = true
        vm.enabled = false
        XCTAssertEqual(
            vm.draft,
            EnvVarDraft(envKey: "LOG_LEVEL", envValue: "debug", description: nil, sensitive: 1, enabled: 0)
        )
        catalog.createReplies = [.success(EmptyResponse())]
        await vm.save()
        XCTAssertEqual(catalog.createRequests.first?.sensitive, 1)
        XCTAssertEqual(catalog.createRequests.first?.enabled, 0)
    }

    func testACreateFormAlwaysHasSomethingToSend() throws {
        let (vm, _) = createForm()
        XCTAssertFalse(vm.isEditing)
        XCTAssertTrue(vm.hasChanges, "the “nothing changed” line belongs to the edit form only")
        XCTAssertNil(vm.maskedDisplay)
        XCTAssertFalse(vm.isValueBlankBecauseMasked)
    }

    // MARK: - edit: what is kept

    func testAnEditOpensFromTheStoredRowAndCarriesNothingItDidNotTouch() async throws {
        let row = try storedRow(note: "调试开关")
        let (vm, catalog) = editForm(row)
        XCTAssertTrue(vm.isEditing)
        XCTAssertEqual(vm.key, "LOG_LEVEL")
        XCTAssertEqual(vm.value, "debug")
        XCTAssertEqual(vm.note, "调试开关")
        XCTAssertFalse(vm.hasChanges)
        XCTAssertNil(vm.submittedKey)
        XCTAssertNil(vm.submittedValue, "the stored text the form can see is not a change")
        XCTAssertNil(vm.submittedNote)
        XCTAssertNil(vm.submittedSensitive)
        catalog.updateReplies = [.success(EmptyResponse())]
        await vm.save()
        XCTAssertEqual(
            catalog.updateRequests.map(\.change),
            [EnvVarChange()],
            "an untouched edit still saves, and the server reads an empty body as “keep everything”"
        )
        XCTAssertEqual(catalog.updateRequests.first?.id, 32)
        XCTAssertTrue(vm.saved)
    }

    func testASensitiveRowOpensWithValuelessAndKeepsTheMaskAsAHintOnly() async throws {
        let row = try storedRow(key: "OPENAI_API_KEY", value: "sk-****c7", sensitive: true)
        let (vm, catalog) = editForm(row)
        XCTAssertEqual(vm.value, "", "the mask is display data, never an editable value")
        XCTAssertEqual(vm.maskedDisplay, "sk-****c7")
        XCTAssertTrue(vm.isValueBlankBecauseMasked)
        XCTAssertNil(vm.submittedValue)
        catalog.updateReplies = [.success(EmptyResponse())]
        await vm.save()
        XCTAssertEqual(
            catalog.updateRequests.map(\.change),
            [EnvVarChange()],
            "sending what the list showed would store the asterisks as the credential"
        )
    }

    func testAValueThatReadsBackWhatTheRowArrivedWithIsStillUnchanged() async throws {
        let masked = try storedRow(key: "OPENAI_API_KEY", value: "sk-****c7", sensitive: true)
        let (maskForm, _) = editForm(masked)
        maskForm.value = "sk-****c7"
        XCTAssertNil(maskForm.submittedValue, "even a mask typed in by hand is not a new value")

        let plain = try storedRow(key: "LOG_LEVEL", value: "debug")
        let (plainForm, _) = editForm(plain)
        plainForm.value = "debug"
        XCTAssertNil(plainForm.submittedValue, "and the same rule holds for a row that carries its own text")
    }

    func testTheSensitiveFlagIsSentOnlyWhenItActuallyMoved() async throws {
        let (vm, _) = editForm(try storedRow())
        XCTAssertNil(vm.submittedSensitive)
        vm.sensitive = true
        XCTAssertEqual(
            vm.submittedSensitive,
            1,
            "a lone flip makes the server re-encode the stored text in the other form (:156-161, :326-330)"
        )
        XCTAssertEqual(vm.change, EnvVarChange(sensitive: 1))
        vm.value = "release"
        XCTAssertEqual(vm.change, EnvVarChange(envValue: "release", sensitive: 1))
    }

    func testTheEditFormSendsNoEnabledColumnHoweverItsSwitchIsSet() async throws {
        let (vm, catalog) = editForm(try storedRow())
        vm.enabled = false
        XCTAssertFalse(vm.hasChanges, "the switch is not a column of the update DTO")
        catalog.updateReplies = [.success(EmptyResponse())]
        await vm.save()
        XCTAssertEqual(
            catalog.updateRequests.map(\.change),
            [EnvVarChange()],
            "the row's own PUT /{id}/toggle owns that change; the form must not fake it"
        )
    }

    func testAClearedDescriptionIsSentAsAnEmptyColumnNotAsAnOmission() async throws {
        let (vm, catalog) = editForm(try storedRow(note: "调试开关"))
        vm.note = ""
        XCTAssertEqual(
            vm.submittedNote,
            "",
            "leaving the field blank is a cleared description, while an omitted one is read as “keep it”"
        )
        XCTAssertEqual(vm.change, EnvVarChange(description: ""))
        catalog.updateReplies = [.success(EmptyResponse())]
        await vm.save()
        XCTAssertEqual(catalog.updateRequests.map(\.change), [EnvVarChange(description: "")])
    }

    // MARK: - edit: the rename

    func testARenamedKeyGoesOutAloneAndIsStillPatternChecked() async throws {
        let (vm, catalog) = editForm(try storedRow())
        vm.key = "NEW_KEY"
        XCTAssertEqual(vm.submittedKey, "NEW_KEY")
        XCTAssertEqual(vm.change, EnvVarChange(envKey: "NEW_KEY"))
        vm.key = "NEW-KEY"
        XCTAssertEqual(
            vm.validationErrorKey,
            "env.var.key.invalid",
            "the update DTO carries the same @Pattern, so a local pass only moves the refusal to the server"
        )
        XCTAssertNil(vm.change)
        await vm.save()
        XCTAssertTrue(catalog.updateRequests.isEmpty)
        vm.key = String(repeating: "A", count: 201)
        XCTAssertEqual(vm.validationErrorKey, "env.var.key.long")
        await vm.save()
        XCTAssertTrue(catalog.updateRequests.isEmpty)
    }

    // MARK: - save

    func testARowWithNoIdCannotBeWrittenAtAll() async throws {
        let (vm, catalog) = editForm(try storedRow(id: nil))
        vm.value = "release"
        await vm.save()
        XCTAssertTrue(catalog.updateRequests.isEmpty, "the id is this route's only address")
        XCTAssertEqual(vm.errorText, ErrorMessage.text(for: .unpackable))
        XCTAssertFalse(vm.saved)
        XCTAssertFalse(vm.isSaving)
    }

    func testARefusedWriteKeepsTheFormOpenWithTheServersSentence() async throws {
        let (vm, catalog) = createForm()
        vm.key = "OPENAI_API_KEY"
        vm.value = "sk-1"
        // A clash names the key that clashed (`EnvVariableServiceImpl.kt:108-117`).
        catalog.createReplies = [.failure(.business(code: 500, message: "环境变量Key已存在：OPENAI_API_KEY"))]
        await vm.save()
        XCTAssertFalse(vm.saved, "the sheet watches saved to dismiss itself")
        XCTAssertFalse(vm.isSaving, "…and the button has to come back for the retry")
        XCTAssertEqual(vm.errorText, "环境变量Key已存在：OPENAI_API_KEY")
        XCTAssertTrue(vm.canSubmit)
    }

    func testASecondTapWhileTheStackIsAnsweringSendsNothing() async throws {
        let (vm, catalog) = createForm()
        vm.key = "LOG_LEVEL"
        vm.value = "debug"
        catalog.gateWrites = true
        catalog.createReplies = [.success(EmptyResponse())]
        let first = Task { await vm.save() }
        try await waitUntil { catalog.createRequests.count == 1 }
        XCTAssertTrue(vm.isSaving)
        XCTAssertFalse(vm.canSubmit, "the primary button is dead while the write is in flight")
        await vm.save()
        XCTAssertEqual(catalog.createRequests.count, 1, "a second tap cannot post the key twice")
        catalog.releaseWrites()
        await first.value
        XCTAssertTrue(vm.saved)
        XCTAssertFalse(vm.isSaving)
        XCTAssertEqual(catalog.createRequests.count, 1, "one save, one POST")
    }

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }
}
