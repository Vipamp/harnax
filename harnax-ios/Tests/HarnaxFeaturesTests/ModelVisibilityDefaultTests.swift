import XCTest
import HarnaxCore
@testable import HarnaxFeatures

/// Which side of the wall a new row starts on.
///
/// Both forms send `isPublic` on every create, so the value they seed is the value the row is born with — the
/// server's own `?: 1` never gets a chance to speak. That makes the seed a cross-client contract rather than a
/// local default, and the two forms do not agree with each other: the console seeds a model private
/// (`harnax-webui/src/pages/model/components/ModelForm.tsx:60`) and a provider public
/// (`ProviderForm.tsx:56`). The asymmetry is the console's, and these cases exist so a later "let's make both
/// forms consistent" edit has to break one of them on purpose.
@MainActor
final class ModelVisibilityDefaultTests: XCTestCase {
    private func modelForm() -> ModelFormModel {
        ModelFormModel(catalog: FakeModelCatalog(), providerID: 3, editing: nil, account: AccountSnapshot(username: "alice"))
    }

    private func providerForm() -> ModelProviderFormModel {
        ModelProviderFormModel(catalog: FakeModelCatalog(), editing: nil, account: AccountSnapshot(username: "alice"))
    }

    func testANewModelIsBornPrivateAndSaysSoOnTheWire() {
        let form = modelForm()
        XCTAssertFalse(form.isPublic, "the console seeds a create behind the wall")
        XCTAssertEqual(form.body().isPublic, 0, "the switch is never omitted, so this seed is the row's birth value")
    }

    func testANewProviderIsBornPublished() {
        let form = providerForm()
        XCTAssertTrue(form.isPublic)
        XCTAssertEqual(form.body().isPublic, 1)
    }

    /// Private by default is a seed, not a lock: a create is never narrowed by the visibility rule, so the
    /// operator who wants the row shared still reaches it from this same screen.
    func testThePrivateSeedLeavesTheSwitchFreeToFlip() {
        let form = modelForm()
        XCTAssertTrue(form.canChangeVisibility)
        form.isPublic = true
        XCTAssertEqual(form.body().isPublic, 1)
    }
}
