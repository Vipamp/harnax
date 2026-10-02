import XCTest

/// Two wiring gates for the 系统 tab's screens: the retry on a field that failed to load, and the surface
/// that holds a secret shown exactly once.
///
/// Both defects are invisible to the compiler, which is why these read the source text the way the skill
/// navigation gates and the kit's colour and copy gates do. A view is not reachable from `swift test` — the
/// screens are only instantiated by `Harnax.xcodeproj` through `App/HarnaxDebugScreens.swift` — so an absent
/// control is not a type error and not a failing assertion either; it is a screen that quietly stops being
/// usable. `FeatureSources` belongs to the skill tests and `TestSources` to the kit target, so the locator
/// here is local and only reads the two files these gates care about.
enum SystemDomainSources {
    static let sourcesRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .appendingPathComponent("Sources")

    static func contents(of relative: String) throws -> String {
        let url = sourcesRoot.appendingPathComponent(relative)
        return try String(contentsOf: url, encoding: .utf8)
    }

    /// The text from `marker` up to and including the brace that closes it, so a gate can be scoped to one
    /// declaration instead of the whole file.
    static func block(_ text: String, from marker: String) throws -> String {
        guard let found = text.range(of: marker) else {
            throw XCTSkip("nothing in the file matches \(marker)")
        }
        var depth = 0
        var opened = false
        var body = ""
        var index = found.lowerBound
        while index < text.endIndex {
            let character = text[index]
            if character == "{" {
                depth += 1
                opened = true
            } else if character == "}" {
                depth -= 1
            }
            body.append(character)
            if opened && depth == 0 { return body }
            index = text.index(after: index)
        }
        return body
    }
}

final class SystemDomainViewGateTests: XCTestCase {
    private let channelFormFile = "HarnaxFeatures/SystemDomain/ChannelFormView.swift"
    private let rawKeyFile = "HarnaxFeatures/SystemDomain/ApiKeyRawKeySheet.swift"
    private let meFile = "HarnaxFeatures/Me/MeView.swift"
    private let monitorFile = "HarnaxFeatures/TokenMonitor/TokenMonitorView.swift"

    /// S1 — the agent field is the one load in the system domain whose failure has no way back on screen:
    /// `ChannelFormViewModel.swift:63-64` promises "the retry belongs on the field", the console swallows the
    /// same failure into `console.error` (`harnax-webui/src/pages/channel/index.tsx:91-93`) because a desktop
    /// operator reloads the page, and `agentId` is `@NotNull` on the create body, so an empty picker leaves
    /// `canSubmit` false and the sheet has nothing but Cancel. The caption under the label is not an affordance.
    func testTheFailedAgentFieldOffersItsOwnRetry() throws {
        let text = try SystemDomainSources.contents(of: channelFormFile)
        let picker = try SystemDomainSources.block(text, from: "private var agentPicker")
        XCTAssertTrue(
            picker.contains("vm.agentErrorText"),
            "the retry belongs to the failed read alone, so the picker has to read the field's own error line"
        )
        XCTAssertTrue(
            picker.contains("Button"),
            "a caption cannot be tapped: the field needs a control that re-issues the read"
        )
        XCTAssertTrue(
            picker.contains("vm.loadAgents()"),
            "and that control has to call the same entry point `.task` calls, which the view model re-opens "
                + "whenever the list is still empty"
        )
        XCTAssertTrue(
            picker.contains(#"HXText("common.retry")"#),
            "with the catalog's retry word — the same key `HXStateView`'s retry button uses"
        )
    }

    /// S3 — the one-time secret. `POST /api/admin/api-keys` and `POST /{id}/regenerate` are the only routes
    /// that ever carry a usable key and the database keeps only its digest (`ApiKeyServiceImpl.kt:80-82`), so
    /// closing this surface destroys the only copy the device ever had (`api-key/index.tsx:431-433` empties
    /// `rawKeyData` on close). The console therefore refuses the backdrop — `maskClosable={false}` at
    /// `RawKeyModal.tsx:28` — while the X at `:27` stays an explicit act, and `specs/03-system-domain.md:340`
    /// names the iOS remedy as `interactiveDismissDisabled(true)`. A sheet the user can flick away mid-glance
    /// is the accidental version of that destruction.
    func testTheRawKeySurfaceRefusesAnAccidentalDismissal() throws {
        let text = try SystemDomainSources.contents(of: rawKeyFile)
        XCTAssertTrue(
            text.contains(".interactiveDismissDisabled(true)"),
            "\(rawKeyFile) presents a secret that cannot be recovered; both call sites attach it to a sheet, "
                + "so the drag gesture has to be refused by the surface itself"
        )
        XCTAssertTrue(
            text.contains(#"HXBanner("apikey.published.once""#),
            "…and the notice that says so stays on screen"
        )
    }

    // MARK: - 系统 tab 并入「我的」

    /// The merged screen is the only home the four admin rows have now, and it has to keep the gate that came
    /// with them: the row list is what an account may open, not `allCases`. A missing `navigationDestination`
    /// would leave every row tappable and going nowhere, which the compiler cannot see.
    func testTheAccountScreenIsNowTheOnlyHomeForTheAdminRows() throws {
        let text = try SystemDomainSources.contents(of: meFile)
        XCTAssertTrue(
            text.contains("SystemRoute.visible(for:"),
            "the group is built from the per-account row list, so a member still loses only the key list"
        )
        XCTAssertTrue(
            text.contains("navigationDestination(for: SystemRoute.self)"),
            "and the rows ride this screen's own stack — a link with no destination pushes nothing"
        )
    }

    /// 永久 Key 不再显示：撤的是这一屏上的展示，不是那个域的读接口。文件留在仓里，`MeKeySources` 那几条
    /// 源码闸照旧守着它不写盘、不复制明文。
    func testTheAccountScreenStopsShowingThePermanentKey() throws {
        let text = try SystemDomainSources.contents(of: meFile)
        XCTAssertFalse(text.contains("MePermanentKey"), "\(meFile) no longer mounts the key card")
        XCTAssertFalse(text.contains("me.section.key"), "…nor the section header that held it")
    }

    /// The three picks that used to cost a navigation — theme, language, tenant — are rows on this screen, in
    /// the same left-label/right-menu shape the session detail panel uses. A dropdown is also what makes the
    /// tenant switch reachable at all: the entry used to appear only once the account had a second tenant.
    func testTheAccountScreenSetsThemeLanguageAndTenantInPlace() throws {
        let text = try SystemDomainSources.contents(of: meFile)
        for marker in ["private var tenantRow", "private var themeRow", "private var languageRow"] {
            let row = try SystemDomainSources.block(text, from: marker)
            XCTAssertTrue(row.contains("HXRow("), "\(marker) is a labelled row, not a bare control")
            XCTAssertTrue(row.contains(".pickerStyle(.menu)"), "\(marker) picks from a dropdown")
        }
        XCTAssertTrue(
            text.contains("vm.tenantChoices"),
            "the tenant menu offers the rows the switch route can address, and only those"
        )
        XCTAssertTrue(
            try SystemDomainSources.block(text, from: "private var tenantRow").contains("tenantLabel(tenant)"),
            "the menu text for a row comes from one helper…"
        )
        XCTAssertTrue(
            try SystemDomainSources.block(text, from: "private func tenantLabel").contains("me.tenant.disabled"),
            "…and that helper says so when the tenant is disabled — the server still issues a token for it, so "
                + "the row is selectable and only its status can announce that"
        )
        XCTAssertFalse(text.contains("HarnaxRoute.appearance"), "the preferences sub-screen is gone")
    }

    /// E5's three filters move from a strip of segments to the same row shape as the rest, and the two facts
    /// this screen must keep survive the move: only the window and the bucket reach the network, and the
    /// measure picks a column out of rows already in hand.
    func testTheTokenFiltersAreLeftLabelRightMenu() throws {
        let text = try SystemDomainSources.contents(of: monitorFile)
        let filters = try SystemDomainSources.block(text, from: "private var filters")
        XCTAssertTrue(
            filters.contains(#""monitor.filter.range""#),
            "the three filters stay the first block on the screen"
        )
        XCTAssertFalse(filters.contains("HXSegmented"), "as dropdowns rather than a strip of segments")
        let row = try SystemDomainSources.block(text, from: "private func filterRow")
        XCTAssertTrue(row.contains(".pickerStyle(.menu)"), "one labelled row, one menu")
    }
}
