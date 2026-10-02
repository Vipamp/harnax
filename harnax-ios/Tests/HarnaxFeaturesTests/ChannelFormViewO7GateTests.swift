import XCTest

/// O7 — the `http` chip on the channel form, pinned in source the way the system domain's other view gates
/// are (`SystemDomainViewGateTests`).
///
/// The submit guard itself is a view-model behaviour and has its own tests
/// (`ChannelFormViewModelTests`); this file only holds the presentation half, because the other half of the
/// ruling is what the operator *sees*: the option stays on the row, does not take a tap, and says why. A view
/// is not reachable from `swift test` — the sheets are instantiated by `Harnax.xcodeproj` through
/// `App/HarnaxDebugScreens.swift` — so a chip that quietly became selectable again would be neither a type
/// error nor a failing assertion.
///
/// DESIGN.md §15 O7 is deliberate divergence from the web console, which lists HTTP as a plain enabled option
/// (`harnax-webui/src/pages/channel/index.tsx:44-50`), so these three assertions are the only thing standing
/// between a future "just match the web" edit and a form that lets an operator POST a channel the runtime
/// answers with `no adaptor registered`.
final class ChannelFormViewO7GateTests: XCTestCase {
    private let channelFormFile = "HarnaxFeatures/SystemDomain/ChannelFormView.swift"

    func testTheUnsupportedTypeStaysVisibleButTakesNoTap() throws {
        let text = try SystemDomainSources.contents(of: channelFormFile)
        let chips = try SystemDomainSources.block(text, from: "private var typeChips: some View")

        XCTAssertTrue(
            chips.contains("ForEach(ChannelType.allCases"),
            "O7 keeps the option in the list rather than dropping the case — a stored http row has to show its"
                + " own type when it is opened"
        )
        XCTAssertTrue(
            chips.contains(".disabled(!type.hasRuntimeAdaptor)"),
            "and loses only the tap, on the same fact the view model's submit guard reads"
        )
        XCTAssertTrue(
            chips.contains(#"marker: type.hasRuntimeAdaptor ? nil : hx("channel.type.unsupported")"#),
            "and the not-supported marker sits on the chip itself, resolved through a catalogue key"
        )
    }

    /// The disabled chip alone explains nothing on a stored row whose every field is already filled: the reason
    /// Save stays dead has to be on the sheet, not only in the operator's memory of the enum.
    func testTheSheetSaysWhyTheTypeCannotBeSaved() throws {
        let text = try SystemDomainSources.contents(of: channelFormFile)
        let fields = try SystemDomainSources.block(text, from: "private var fields: some View")

        XCTAssertTrue(
            fields.contains("vm.showsUnsupportedNotice"),
            "the notice hangs off the view model's own state, so it cannot disagree with the guard"
        )
        XCTAssertTrue(
            fields.contains(#""channel.type.unsupported.note""#),
            "…and says it in catalogue copy that also serves as the refusal line when save() is called directly"
        )
    }
}
