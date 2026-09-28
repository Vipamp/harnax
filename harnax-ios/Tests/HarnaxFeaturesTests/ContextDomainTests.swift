import XCTest
import HarnaxKit
import HarnaxFeatures

/// The five columns and their order are this tab's information architecture, and the copy keys are the
/// only label the segment row can show. Both are contract with the mockup, so they are pinned here.
final class ContextDomainTests: XCTestCase {
    func testTheFiveDomainsKeepTheMockupOrder() {
        XCTAssertEqual(
            ContextDomain.allCases.map(\.titleKey),
            [
                "context.domain.model",
                "context.domain.tool",
                "context.domain.mcp",
                "context.domain.skill",
                "context.domain.cli",
            ]
        )
    }

    func testEverySegmentResolvesToItsOwnCopy() {
        for domain in ContextDomain.allCases {
            let label = hx(domain.titleKey)
            XCTAssertFalse(label.isEmpty, "\(domain.titleKey) resolved to nothing")
            XCTAssertNotEqual(label, domain.titleKey, "\(domain.titleKey) is missing from the catalogue")
        }
    }

    func testTheSegmentIdsMatchTheRawDomainSoTheRowCanRestoreSelection() {
        XCTAssertEqual(ContextDomain.segments.map(\.id), ContextDomain.allCases.map(\.rawValue))
    }
}
