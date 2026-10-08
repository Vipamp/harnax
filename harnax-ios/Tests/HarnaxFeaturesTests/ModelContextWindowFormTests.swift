import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// The context window on the model form.
///
/// The number is the denominator of the occupancy readout and of the auto-compaction trigger, and the two
/// routes read a missing key differently: a create without it lets the runtime infer a value from the model
/// name (`ModelServiceImpl.kt:132`) while an update without it leaves the stored one alone (`:186`,
/// `request.contextWindow?.let`). Blank is therefore a real answer on both routes and has to stay a real
/// answer on the wire — and both DTOs pin `@field:Min(value = 1)` on it, so the one value the form must
/// never invent for an empty field is a 0.
@MainActor
final class ModelContextWindowFormTests: XCTestCase {
    private func form(window: Int?) throws -> ModelFormModel {
        var fields: [String: Any] = ["id": 21, "name": "通义千问 Max", "modelName": "qwen-max"]
        if let window { fields["contextWindow"] = window }
        return ModelFormModel(
            catalog: FakeModelCatalog(),
            providerID: 3,
            editing: try ModelSummary.stub(fields),
            account: AccountSnapshot(username: "alice")
        )
    }

    /// Both names are filled in because `validate` answers the first rule it finds, and these cases are about
    /// the window rule at the end of that list.
    private func blankForm() -> ModelFormModel {
        let form = ModelFormModel(
            catalog: FakeModelCatalog(),
            providerID: 3,
            editing: nil,
            account: AccountSnapshot(username: "alice")
        )
        form.name = "演示模型"
        form.technicalName = "demo-model"
        return form
    }

    /// The row's own window opens in the field, so reading it off the phone answers the question the readout
    /// raises: what size this model is being measured against.
    func testAStoredWindowOpensTheFieldWithData() throws {
        let form = try form(window: 128_000)
        XCTAssertEqual(form.contextWindowText, "128000")
        XCTAssertEqual(form.body().contextWindow, 128_000)
    }

    func testACreateStartsWithNoWindow() {
        let form = blankForm()
        XCTAssertEqual(form.contextWindowText, "")
        XCTAssertNil(form.body().contextWindow, "a create nobody sized is left to the runtime's inference")
    }

    /// The dangerous edit is an unrelated one — retyping the price on a row that carries a window. The field
    /// then holds the row's value, and clearing it deliberately has to read as "leave it alone", not as 0.
    func testClearingTheFieldLeavesTheStoredWindowUnsent() throws {
        let form = try form(window: 128_000)
        form.contextWindowText = ""
        XCTAssertNil(form.body().contextWindow)
        XCTAssertNil(form.validate(), "blank is a legal answer on both routes")
    }

    func testATypedWindowTravelsAsACount() {
        let form = blankForm()
        form.contextWindowText = "32000"
        XCTAssertEqual(form.parsedContextWindow, 32_000)
        XCTAssertEqual(form.body().contextWindow, 32_000)
        XCTAssertNil(form.validate())
    }

    /// The server's own sentence is `Context window must be a positive token count`, and a 0 that reached it
    /// would fail the request after the operator had already left the screen.
    func testAWindowBelowOneIsRefusedBeforeTheRequest() {
        let form = blankForm()
        form.contextWindowText = "0"
        XCTAssertEqual(form.validate(), hx("model.validation.contextWindow"))
    }

    /// Paste puts letters in a numberPad field, and `Int("12.8")` answers nothing — an unparsed value has to
    /// be refused here rather than travel as a key the create would take as an inference.
    func testAnUnreadableWindowIsRefusedRatherThanSentAsNothing() {
        let form = blankForm()
        form.contextWindowText = "12.8k"
        XCTAssertNil(form.parsedContextWindow)
        XCTAssertEqual(form.validate(), hx("model.validation.contextWindow"))
    }

    /// A field the screen never mounts is a feature nobody can find, and `swift test` cannot reach a `View` —
    /// so the mount is read off source, the way every other view gate here does it. The console puts it
    /// between the price and the description (`harnax-webui/src/pages/model/components/ModelForm.tsx:178-204`).
    func testTheWindowRowSitsBetweenThePriceAndTheDescription() throws {
        let text = try ToolbarSources.contents(of: "HarnaxFeatures/Models/ModelForm.swift")
        let field = #"HXField("model.field.contextWindow", text: $vm.contextWindowText"#
        let price = #"HXField("model.field.price""#
        let description = #"HXField("model.field.description""#
        guard let priceAt = text.range(of: price),
              let fieldAt = text.range(of: field),
              let descriptionAt = text.range(of: description),
              fieldAt.lowerBound > priceAt.lowerBound,
              fieldAt.lowerBound < descriptionAt.lowerBound else {
            return XCTFail("the window row is missing, or it is not the console's third field")
        }
        let row = text[fieldAt.upperBound..<descriptionAt.lowerBound]
        XCTAssertTrue(row.contains("kind: .number"), "the field owes a digits keyboard")
        XCTAssertTrue(
            text.contains(#"HXText("model.field.contextWindow.note")"#),
            "the sentence saying what a blank field does has to travel with the field"
        )
    }
}
