import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// The detail sheet's address. `CliDetailSheet.swift:151` wrote `cli.id ?? 0` for a row whose id the server
/// left out, which turns "this row cannot be looked up" into a request for package zero and a 404 the
/// operator has no way to interpret (`CliResponse.kt` declares the id as a nullable `Long?`).
///
/// The invented address is a type decision the compiler cannot see — `0` is a perfectly good `Int64` — so the
/// first two gates read the source, and the read that must not go out is pinned from the model side.
final class CliDetailAddressTests: XCTestCase {
    private let sheetFile = "HarnaxFeatures/Cli/CliDetailSheet.swift"

    /// A made-up id is worse than no id: the route exists, so the sheet spends a request on a package nobody
    /// registered and reports the server's refusal as if the row had gone away.
    func testTheSheetNeverInventsAnAddressForARow() throws {
        let text = try FeatureSources.contents(of: sheetFile)
        XCTAssertFalse(
            text.contains("?? 0"),
            "the sheet may not substitute an address for a row that has none"
        )
    }

    /// A row without an address needs its own state, and the model needs to reach it: `loading` would spin
    /// forever and `failed` would quote a server that was never asked.
    func testARowWithoutAnAddressHasAStateOfItsOwn() throws {
        let text = try FeatureSources.contents(of: sheetFile)
        let declared = try FeatureSources.captures(#"case ([a-zA-Z]+)"#, in: try FeatureSources.block(text, from: "public enum Phase"))
        XCTAssertTrue(
            declared.contains("unaddressable"),
            "the phase list has to name the case where there is nothing to read: \(declared)"
        )
        let handled = try FeatureSources.captures(
            #"case (?:let )?\.([a-zA-Z]+)"#,
            in: try FeatureSources.block(text, from: "private var content")
        )
        XCTAssertTrue(
            handled.contains("unaddressable"),
            "and the sheet has to draw it, or the state is invisible: \(handled)"
        )
    }

    /// The general rule behind the two above: whatever the model can reach has to be on screen.
    func testEveryPhaseTheModelCanReachHasABranchOnScreen() throws {
        let text = try FeatureSources.contents(of: sheetFile)
        let declared = try FeatureSources.captures(#"case ([a-zA-Z]+)"#, in: try FeatureSources.block(text, from: "public enum Phase"))
        let handled = try FeatureSources.captures(
            #"case (?:let )?\.([a-zA-Z]+)"#,
            in: try FeatureSources.block(text, from: "private var content")
        )
        XCTAssertEqual(
            Set(handled),
            Set(declared),
            "a phase with no branch is a blank sheet, and the compiler will not say so"
        )
    }
}
