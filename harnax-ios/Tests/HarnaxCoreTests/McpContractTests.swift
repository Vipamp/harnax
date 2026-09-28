import XCTest

@testable import HarnaxCore

/// The MCP domain's contract layer: fixtures shaped the way `harnax-admin` really answers, decoded through
/// the types the MCP screens read.
///
/// The bodies are `ResultVo<Page<McpServerResponse>>` (`McpServerController.kt:55-61`),
/// `ResultVo<McpServerResponse>` (`:67-70`), `ResultVo<List<McpToolResponse>>` (`:152-172`) and the three
/// per-user OAuth bodies (`McpOAuthController.kt:83,117,131`), all against
/// `dto/McpServerResponse.kt:14-44` and its siblings.
///
/// Two rules from the backend shape every fixture in this file:
/// - `harnax-admin/src/main/resources/application.yml:25` sets `default-property-inclusion: non_null`, so a
///   null DTO property is a *missing key* rather than `"key": null`;
/// - a column with a non-null Kotlin default (`entity/McpServer.kt:33-108`) always arrives, and it arrives
///   empty — an unused `command` or `url` reads `""`, because create writes `""` there
///   (`McpServerServiceImpl.kt:131-132`) and a transport switch blanks the other half (`:196-208`).
///
/// Empty arrays do get sent: `Page.kt:13` defaults `records` to `emptyList()`, `McpToolResponse.kt:14`
/// defaults `parameters` to one, and the backend's own contract test asserts a shipped
/// `"env_bindings":[]` while nulls stay dropped (`InternalApiControllerTest.kt:874`). That is why those two
/// arrays are non-optional in Swift, and why `testToolRowNeedsItsParametersKey` and
/// `testOAuthStatusNeedsItsScopesKey` assert the other direction too.
final class McpContractTests: XCTestCase {
    private var page: Page<McpServerRow>!
    /// id 41 — a healthy shared streamablehttp row with masked secrets in its headers.
    private var headed: McpServerRow!
    /// id 42 — an enabled private OAUTH2 row; this is the one `mcp-server-detail.json` repeats.
    private var oauthRow: McpServerRow!
    /// id 43 — a legacy stdio + BASIC row no create can produce.
    private var legacy: McpServerRow!
    /// id 44 — the row the operator switched off.
    private var paused: McpServerRow!

    override func setUpWithError() throws {
        page = try Fixture.decode(Envelope<Page<McpServerRow>>.self, "mcp-servers-page").data!
        XCTAssertEqual(page.records.count, 4)
        headed = page.records[0]
        oauthRow = page.records[1]
        legacy = page.records[2]
        paused = page.records[3]
    }

    // MARK: - the page

    /// Rows come back in the list's own order, `ORDER BY status DESC, update_time DESC`
    /// (`McpServerMapper.xml:113`), which is why row 44 — updated most recently of all — is still last: the
    /// status column sorts first, so a disabled row never rises above an enabled one however fresh it is.
    func testRealPageDecodesRowByRow() throws {
        XCTAssertEqual(page.pageNum, 1)
        XCTAssertEqual(page.pageSize, 20)
        XCTAssertEqual(page.total, 4)

        XCTAssertEqual(headed.id, 41)
        XCTAssertEqual(headed.name, "project-fs")
        XCTAssertEqual(headed.description, "Streamable HTTP 上的项目文件服务")
        XCTAssertEqual(headed.url, "https://mcp.example.com/fs/mcp")
        XCTAssertEqual(headed.creator, "admin")
        XCTAssertEqual(headed.createTime, "2026-09-20 15:02:11")
        XCTAssertEqual(headed.updateTime, "2026-09-28 09:14:02")

        XCTAssertEqual(oauthRow.id, 42)
        XCTAssertEqual(oauthRow.creator, "alice")
        XCTAssertEqual(legacy.id, 43)
        XCTAssertEqual(paused.id, 44)
    }

    /// The envelope's own keys, as the bytes make them: `data` sits between `message` and `timestamp`
    /// (`ResultVo.kt:10-22`) and the serialiser adds `isSuccess` from `ResultVo.isSuccess()` (`:61`), the
    /// `isXXX` getter recognition `JacksonConfig.kt:11` exists for. iOS declares four keys and ignores the
    /// fifth, which this decode proves.
    func testEnvelopeCarriesTheComputedSuccessKey() throws {
        XCTAssertEqual(
            Set(try topLevel("mcp-servers-page").keys),
            ["code", "message", "data", "timestamp", "isSuccess"]
        )
        XCTAssertEqual(try topLevel("mcp-servers-page")["isSuccess"] as? Bool, true)
    }

    /// `Page` also serialises the three computed getters (`Page.kt:18-31`, non-null so always emitted). The
    /// fixture keeps them because they are on the real bytes, and iOS derives `hasMore` from
    /// `records.count` against `total` instead (`Contract/Page.swift:6-9`).
    func testComputedPageFlagsAreOnTheWireAndIgnored() throws {
        let body = try body("mcp-servers-page")
        XCTAssertEqual(body["pages"] as? Int, 1)
        XCTAssertEqual(body["hasPrevious"] as? Bool, false)
        XCTAssertEqual(body["hasNext"] as? Bool, false)
        XCTAssertEqual(page.total, page.records.count, "one page holds every row")
    }

    /// `status` and `isPublic` are 0/1 columns, not JSON booleans (`McpServerResponse.kt:31-34`).
    func testStatusAndPublicityReadAsZeroOneColumns() throws {
        XCTAssertEqual(headed.status, 1)
        XCTAssertTrue(headed.isEnabled)
        XCTAssertTrue(headed.isShared)

        XCTAssertEqual(paused.status, 0)
        XCTAssertFalse(paused.isEnabled)
        XCTAssertTrue(paused.isShared, "a switched-off row is still shared")

        XCTAssertEqual(oauthRow.isPublic, 0)
        XCTAssertFalse(oauthRow.isShared)
        XCTAssertTrue(oauthRow.isEnabled)
        XCTAssertFalse(legacy.isShared)
    }

    /// `headers`, `envParams` and `oauthConfig` are the only three keys the server can genuinely drop: the
    /// columns are `String?` on the entity (`McpServer.kt:72,115,122`) and each reader returns null for a
    /// blank or unparseable column (`McpServerResponse.kt:86,106,134`), so `non_null` omits the key whole.
    /// Rows 42/43/44 each answer without one, and the readings still have to be a list rather than a crash.
    func testRowsDecodeWhenAWholeKeyIsMissing() throws {
        XCTAssertNil(oauthRow.headers)
        XCTAssertEqual(oauthRow.headerEntries, [])
        XCTAssertNil(oauthRow.envParams)
        XCTAssertEqual(oauthRow.envEntries, [])

        XCTAssertNil(paused.headers)
        XCTAssertTrue(paused.maskedHeaderKeys.isEmpty)

        XCTAssertNil(legacy.oauthConfig)
        XCTAssertNil(headed.oauthConfig, "a STATIC_HEADER row has no oauth_config to read (`McpServerServiceImpl.kt:134`)")
        XCTAssertNil(headed.envParams, "a network row's envParams column is nulled on create (`:147-151`)")
    }

    /// The tolerance `McpServerRow.swift:28-31` claims for itself — an absent `status` reads as enabled —
    /// asserted rather than assumed. Today's bytes cannot actually omit it (the column defaults to `1` and
    /// MyBatis leaves the setter alone for a NULL, `McpServer.kt:78` with
    /// `application.yml:58 call-setters-on-nulls: false`), and the server would reach the same reading for
    /// the same reason. `isPublic` deliberately does *not* mirror that default here: an absent share flag
    /// stays "not shared" on this side, which is the conservative answer for a row the list may show.
    func testBareRowDecodesAndReadsEnabled() throws {
        let bare = try row(#"{"id":45,"name":"fresh","type":"sse","url":"https://x.example.com/sse"}"#)
        XCTAssertNil(bare.status)
        XCTAssertTrue(bare.isEnabled)
        XCTAssertNil(bare.isPublic)
        XCTAssertFalse(bare.isShared)
        XCTAssertEqual(bare.transport, .sse)
        XCTAssertEqual(bare.auth, .none, "no authType key is NONE, server-side and here (`McpServerServiceImpl.kt:394-395`)")
        XCTAssertNil(bare.description)
        XCTAssertNil(bare.updateTime)
    }

    /// A blank name is not a name, and the card's fallback is `title == nil` (`hxPresented`).
    func testTitleTrimsAndFallsBackToNil() throws {
        XCTAssertEqual(headed.title, "project-fs")
        XCTAssertEqual(try row(#"{"name":"  padded  "}"#).title, "padded")
        XCTAssertNil(try row(#"{"name":"   "}"#).title)
        XCTAssertNil(try row(#"{"name":""}"#).title)
    }

    // MARK: - transport and endpoint

    /// An stdio row shows its command line and every other row its URL, which is the console's own rule
    /// (`harnax-webui/src/pages/mcp/index.tsx:44`). The unused half of the pair is not absent but empty —
    /// `command` and `url` are non-null columns (`McpServer.kt:51,57`), and both write paths put `""` in the
    /// half the transport does not use (`McpServerServiceImpl.kt:131-132,196-208`).
    func testEndpointFollowsTheTransport() throws {
        XCTAssertEqual(headed.transport, .streamablehttp)
        XCTAssertEqual(headed.endpoint, "https://mcp.example.com/fs/mcp")
        XCTAssertEqual(headed.command, "", "the unused half arrives empty, not absent")
        XCTAssertFalse(headed.transport.usesCommand)

        XCTAssertEqual(legacy.transport, .stdio)
        XCTAssertTrue(legacy.transport.usesCommand)
        XCTAssertEqual(legacy.endpoint, "npx -y @harnax/fs-mcp --root /srv/data")
        XCTAssertEqual(legacy.url, "")

        XCTAssertEqual(oauthRow.transport, .sse)
        XCTAssertEqual(paused.transport, .sse)
    }

    /// A stored value outside the three the server will accept (`validateTypeAndFields`,
    /// `McpServerServiceImpl.kt:371-389`) is still a row that has to render: iOS keeps its text, labels
    /// nothing (`titleKey == nil`) and sends the same string back rather than inventing a canonical one —
    /// an edit that silently rewrote the transport would then fail its own validation.
    func testUnknownTransportKeepsItsOwnText() throws {
        let odd = try row(#"{"id":46,"type":"streamable-http","url":"https://x","command":"ignored"}"#)
        XCTAssertEqual(odd.transport, .other(raw: "streamable-http"))
        XCTAssertNil(odd.transport.titleKey)
        XCTAssertEqual(odd.transport.wireValue, "streamable-http")
        XCTAssertFalse(odd.transport.isStdio)
        XCTAssertEqual(odd.endpoint, "https://x")
    }

    /// Recognition trims and lowercases, the same way `McpStdioPolicy.isStdio` does
    /// (`McpStdioPolicy.kt:25`), while the value that goes back on the wire is the canonical lowercase the
    /// server matches *exactly* — both in `validateTypeAndFields` (`McpServerServiceImpl.kt:371-389`, a
    /// `when` on the raw string) and in the list filter's `type = #{type}` (`McpServerMapper.xml:108`).
    /// These three strings are what the list screen's type filter puts in the query.
    func testTransportNamesRoundTripToTheFilterStrings() throws {
        XCTAssertEqual(McpTransport.known.map(\.wireValue), ["stdio", "sse", "streamablehttp"])
        XCTAssertEqual(McpTransport(raw: "SSE").wireValue, "sse")
        XCTAssertEqual(McpTransport(raw: " StreamableHTTP ").wireValue, "streamablehttp")
        XCTAssertEqual(McpTransport(raw: "Stdio"), .stdio)
        XCTAssertEqual(McpTransport(raw: nil), .other(raw: nil))
        XCTAssertNil(McpTransport(raw: nil).wireValue)

        // stdio is the one the deployment refuses to create (McpStdioPolicy.kt:20-31), so it is absent from
        // the create form's list while still being a filter value.
        XCTAssertEqual(McpTransport.creatable.map(\.wireValue), ["sse", "streamablehttp"])
        XCTAssertFalse(McpTransport.creatable.contains(.stdio))
    }

    /// `endpoint` is `nil` — not `""` — for a row whose transport has nothing to show. A stdio row with a
    /// blank command cannot be created (`McpServerServiceImpl.kt:373-377`), so this is the shape of a row
    /// someone wrote straight into the table; the screen then shows no endpoint rather than an empty one.
    func testHalfWrittenRowShowsNoEndpoint() throws {
        let half = try row(#"{"id":47,"type":"stdio","command":"   ","url":"https://not-mine"}"#)
        XCTAssertNil(half.endpoint)
        XCTAssertEqual(half.url, "https://not-mine", "an stdio row's url is ignored rather than shown")
    }

    // MARK: - auth

    /// `BASIC` is declared by the schema (`entity/McpAuthTypes.kt:18`) and refused on write
    /// (`McpServerServiceImpl.kt:396-403`, with its own "declared but not wired" reason), so it only ever
    /// reaches a read on a legacy row: it keeps a label and gets no picker entry, which is exactly the pair
    /// of claims at `McpServerRow.swift:105-107` and `:116-117`.
    func testAuthReadingsIncludingTheLegacyBasicRow() throws {
        XCTAssertEqual(headed.auth, .staticHeader)
        XCTAssertEqual(oauthRow.auth, .oauth2)
        XCTAssertTrue(oauthRow.requiresPerUserOAuth)
        XCTAssertFalse(headed.requiresPerUserOAuth)
        XCTAssertEqual(paused.auth, .none)

        XCTAssertEqual(legacy.auth, .basic)
        XCTAssertEqual(legacy.auth.wireValue, "BASIC")
        XCTAssertEqual(legacy.auth.titleKey, "mcp.auth.basic")
        XCTAssertFalse(legacy.requiresPerUserOAuth)
        XCTAssertFalse(McpAuthKind.selectable.contains(.basic))
        XCTAssertEqual(McpAuthKind.selectable.map(\.wireValue), ["NONE", "STATIC_HEADER", "OAUTH2"])
    }

    /// The legacy row is the one no create could produce: stdio is refused while
    /// `harnax.mcp.stdio-enabled=false` (`application.yml:146-151`, `McpStdioPolicy.kt:20-31`) and BASIC is
    /// refused always (`McpServerServiceImpl.kt:396-403`), and the two refusals are independent — so
    /// `stdio` + `BASIC` + a populated `env_params` column is reachable only through a row written before
    /// either rule, or by hand. It still has to render, and its readings are the ones the card uses.
    func testLegacyRowIsOneNoCreateCouldProduce() throws {
        XCTAssertEqual(legacy.transport, .stdio)
        XCTAssertEqual(legacy.auth, .basic)
        XCTAssertEqual(legacy.envEntries.count, 3, "a stdio row keeps its env params (`McpServerServiceImpl.kt:147-151`)")
        XCTAssertNil(legacy.oauthConfig)
        XCTAssertTrue(legacy.isEnabled)
        XCTAssertEqual(legacy.endpoint, "npx -y @harnax/fs-mcp --root /srv/data")
    }

    /// An omitted, empty or whitespace auth type all read as NONE, mirroring
    /// `raw?.takeIf { hasText(it) } ?: McpAuthTypes.NONE` (`McpServerServiceImpl.kt:394-395`); a stored value
    /// in any case reads the same, and one the server never heard of keeps its own uppercased text so the
    /// detail screen can show it verbatim.
    func testAuthKindReadsBlankAsNoneAndKeepsUnknownText() throws {
        XCTAssertEqual(McpAuthKind(raw: nil), .none)
        XCTAssertEqual(McpAuthKind(raw: ""), .none)
        XCTAssertEqual(McpAuthKind(raw: "   "), .none)
        XCTAssertEqual(McpAuthKind(raw: "oauth2"), .oauth2)
        XCTAssertEqual(McpAuthKind(raw: " static_header ").wireValue, "STATIC_HEADER")
        XCTAssertEqual(McpAuthKind(raw: "SASL"), .other(raw: "SASL"))
        XCTAssertNil(McpAuthKind(raw: "SASL").titleKey)
    }

    // MARK: - masked config entries

    /// Both mask shapes the service computes (`McpServerResponse.kt:156-167`): `前3****后4` for a value it
    /// could open, and the fixed `******` for one too short or undecryptable with the current key. The
    /// third entry is a plain header, which stays verbatim. `maskedHeaderKeys` is not a display detail —
    /// those are the strings the edit form has to echo back for the stored ciphertext to survive the save
    /// (`SecretFieldEncryptor.kt:46-49`).
    func testMaskedHeaderEntriesAreRecognised() throws {
        let entries = headed.headerEntries
        XCTAssertEqual(entries.count, 3)
        XCTAssertEqual(entries.map(\.key), ["X-Trace-Id", "Authorization", "X-API-Key"])
        XCTAssertEqual(entries[0].value, "on")
        XCTAssertFalse(entries[0].isMasked)
        XCTAssertEqual(entries[1].value, "Bea****T2c7")
        XCTAssertTrue(entries[1].isMasked)
        XCTAssertEqual(entries[2].value, "******")
        XCTAssertTrue(entries[2].isMasked)
        XCTAssertEqual(headed.maskedHeaderKeys, ["Authorization", "X-API-Key"])
    }

    /// The `secret` flag is part of the test, not just the value: `isMasked` requires both
    /// (`McpServerRow.swift:170-172`), and the server's own rule is the same `contains("****")` applied only
    /// to entries marked secret (`SecretFieldEncryptor.kt:62`, `McpServerResponse.kt:111-115`). An entry
    /// carrying a mask without the flag is the case the server refuses outright
    /// (`SecretFieldEncryptor.kt:75-85`), so iOS must not treat it as unchanged.
    func testMaskNeedsTheSecretFlag() throws {
        XCTAssertFalse(McpConfigEntry(key: "X", value: "a****b", secret: false).isMasked)
        XCTAssertTrue(McpConfigEntry(key: "X", value: "a****b", secret: true).isMasked)
        XCTAssertFalse(McpConfigEntry(key: "X", value: "", secret: true).isMasked)
        XCTAssertNil(McpConfigEntry(key: "   ", value: "v").name)
        XCTAssertEqual(McpConfigEntry().key, "")
    }

    /// `envParams` are stdio-only, and their keys are `envParamName` / `defaultValue`
    /// (`ToolEnvParamEntry.kt:19,31`), not the `key` / `value` the headers use
    /// (`McpConfigEntry.kt:12-16`). One entry arrives with no `description` key and one with no
    /// `defaultValue` key — both are nullable on the DTO (`:22,31`) — and `required` / `secret` are the
    /// JSON booleans in this contract rather than 0/1 integers (`:25,28`).
    func testEnvParamEntriesOnTheStdioRow() throws {
        let entries = legacy.envEntries
        XCTAssertEqual(entries.map(\.envParamName), ["FS_ROOT", "FS_TOKEN", "FS_DEBUG"])
        XCTAssertEqual(entries[0].id, 7)
        XCTAssertEqual(entries[0].defaultValue, "/srv/data")
        XCTAssertFalse(entries[0].secret)

        XCTAssertTrue(entries[1].required)
        XCTAssertTrue(entries[1].secret)
        XCTAssertEqual(entries[1].defaultValue, "******")
        XCTAssertNil(entries[1].description, "a null description is a dropped key")

        XCTAssertNil(entries[2].defaultValue, "a secret saved without a value has no defaultValue key at all")
        XCTAssertTrue(entries[2].secret)
        XCTAssertEqual(entries.requiredNames, ["FS_TOKEN"])
        XCTAssertEqual(legacy.envParams?.count, 3)
    }

    // MARK: - the OAuth block on a row

    /// Only an OAUTH2 row can carry `oauthConfig`: create writes the column through `writeOAuthConfig`,
    /// which refuses any other auth type (`McpServerServiceImpl.kt:417-420`), and an update off OAUTH2 nulls
    /// it again (`:234-238`). Inside the block, `scopes` and `resourceIndicator` are non-null defaults
    /// (`McpOAuthConfig.kt:21,30`) so they always arrive, while `authorizationServer` and `audience` are
    /// nullable (`:18,24`) and dropped when unset.
    func testOAuthConfigOnlyArrivesOnAnOAuthRow() throws {
        let config = try XCTUnwrap(oauthRow.oauthConfig)
        XCTAssertEqual(config.authorizationServer, "https://auth.example.com/realms/harnax")
        XCTAssertEqual(config.issuer, "https://auth.example.com/realms/harnax")
        XCTAssertEqual(config.audience, "https://crm.example.com")
        XCTAssertEqual(config.scopes, ["mcp:tools", "offline_access"])
        XCTAssertEqual(config.scopeList, ["mcp:tools", "offline_access"])
        XCTAssertTrue(config.resourceIndicator)

        XCTAssertNil(headed.oauthConfig)
        XCTAssertNil(legacy.oauthConfig)
        XCTAssertNil(paused.oauthConfig)
    }

    /// The defaults iOS offers a new form, and the one reading the update form depends on: an empty
    /// `authorizationServer` is not an issuer. Discovery writes that field, and the OAuth service treats a
    /// blank column as unset (`McpOAuthUserServiceImpl.kt:936-940`), so `""` and an absent key have to land
    /// in the same place here.
    func testOAuthConfigDefaultsAndBlankIssuer() throws {
        XCTAssertEqual(McpOAuthConfigValues.defaults.resourceIndicator, true)
        XCTAssertTrue(McpOAuthConfigValues.defaults.scopes.isEmpty)
        XCTAssertNil(McpOAuthConfigValues.defaults.issuer)
        XCTAssertNil(McpOAuthConfigValues.defaults.audience)

        let blank = try row(
            #"{"authType":"OAUTH2","oauthConfig":{"authorizationServer":"","scopes":["","mcp:tools"],"resourceIndicator":false}}"#
        )
        let config = try XCTUnwrap(blank.oauthConfig)
        XCTAssertNil(config.issuer, "blank is not an issuer")
        XCTAssertEqual(config.scopeList, ["mcp:tools"], "a blank scope entry does not become a chip")
        XCTAssertFalse(config.resourceIndicator)
        XCTAssertNil(config.audience, "no audience key at all")
    }

    // MARK: - the single-row read

    /// `McpServerRow.swift:3` claims `GET /api/admin/mcp/{id}` answers "the same shape" as the page, and the
    /// backend upholds it: `McpServerController.kt:57` and `:70` both hand the entity to
    /// `convertToResponse`, which is one `McpServerResponse.fromEntity` (`McpServerServiceImpl.kt:439`,
    /// `McpServerResponse.kt:50-74`), and both reads select the whole row
    /// (`McpServerMapper.xml:29-31` `SELECT *`, `:97` `SELECT *`). So the detail route carries **no field the
    /// page does not** — the asymmetry is per row (whether `oauth_config` / `headers` / `env_params` hold
    /// anything), not per route. What the detail read *is* for is the OAuth panel: it is the call that hands
    /// back the `oauthConfig` block the panel reads scopes and the resource indicator from before it asks
    /// for an authorize URL.
    func testDetailReadCarriesNothingThePageRowLacks() throws {
        let detail = try Fixture.decode(Envelope<McpServerRow>.self, "mcp-server-detail").data!
        XCTAssertEqual(detail, oauthRow)
        XCTAssertEqual(Set(try body("mcp-server-detail").keys), Set(try recordJSON(1).keys))
        XCTAssertEqual(detail.oauthConfig?.scopeList, ["mcp:tools", "offline_access"])
        XCTAssertEqual(detail.headerEntries, [])
        XCTAssertTrue(detail.requiresPerUserOAuth)
    }

    /// A row outside the tenant, or one the caller may not see, answers `ResultVo.error(404, …)`
    /// (`McpServerController.kt:69`, `getVisibleMcpServer` at `:93` with the list's own predicate at `:98-99`)
    /// and `non_null` then drops the whole `data` key — the backend asserts the same for a null payload in
    /// its own contract test (`AgentTaskForwardingContractTest.kt:585`, `jsonPath("$.data").doesNotExist()`).
    /// iOS reads that as `data == nil` rather than as a decoding failure.
    func testMissingRowAnswersWithoutData() throws {
        let envelope = try JSONDecoder().decode(
            Envelope<McpServerRow>.self,
            from: Data(#"{"code":404,"message":"MCP server not found","timestamp":1759026030000,"isSuccess":false}"#.utf8)
        )
        XCTAssertNil(envelope.data)
        XCTAssertEqual(envelope.code, 404)
        XCTAssertFalse(envelope.message.isEmpty)
    }

    // MARK: - the tool list

    /// `list_tools` flattens the upstream `inputSchema.properties` into `{name, type, description}` and
    /// defaults a missing type to `"string"` (`McpServerController.kt:157-168`); a tool whose schema has no
    /// `properties` answers an empty `parameters` array (`:169`, and the backend's own case
    /// `McpServerControllerTest.kt:516-533`). `name` is copied verbatim, so a nameless upstream tool arrives
    /// as the empty string (`McpToolResponse.kt:11`).
    func testToolListFlattensTheInputSchema() throws {
        let tools = try Fixture.decode(Envelope<[McpToolRow]>.self, "mcp-tools-list").data!
        XCTAssertEqual(tools.count, 4)

        XCTAssertEqual(tools[0].name, "read_file")
        XCTAssertEqual(tools[0].parameters.count, 2)
        XCTAssertEqual(tools[0].parameters[0].label, "path — Absolute path inside the allowed root")
        XCTAssertEqual(tools[0].parameters[1].type, "integer", "a declared type survives the flattening")
        XCTAssertEqual(tools[0].parameters[1].label, "offset", "an empty description falls back to the name")

        XCTAssertTrue(tools[1].parameters.isEmpty)
        XCTAssertEqual(tools[1].id, "list_dir")
        XCTAssertEqual(tools[1].displayName, "list_dir")

        XCTAssertEqual(tools[2].parameters[0].type, "string", "the controller's default for a schema that "
            + "declared no type (`McpServerController.kt:161`) is indistinguishable from a real string param")
        XCTAssertEqual(tools[2].parameters[0].label, "lines")

        XCTAssertEqual(tools[3].name, "")
        XCTAssertNil(tools[3].displayName, "no name is no title")
    }

    /// Nothing else from the schema survives: `required`, `enum` and nesting are gone server-side, which is
    /// the "规格修正" note at `McpToolRow.swift:6-11` and why the detail screen shows one chip per parameter
    /// instead of an expandable tree. Asserted against the bytes, not against the Swift type.
    func testParametersCarryOnlyThreeKeys() throws {
        let data = try XCTUnwrap(topLevel("mcp-tools-list")["data"] as? [[String: Any]])
        XCTAssertEqual(Set(data[0].keys), ["name", "parameters"])
        let parameters = try XCTUnwrap(data[0]["parameters"] as? [[String: Any]])
        XCTAssertEqual(Set(parameters[0].keys), ["name", "type", "description"])
        XCTAssertTrue(
            try XCTUnwrap(data[1]["parameters"] as? [Any]).isEmpty,
            "a zero-parameter tool ships an empty array, not a dropped key"
        )
    }

    /// The direction the optionality is only safe in: `parameters` is declared non-optional in Swift because
    /// the DTO always serialises it (`McpToolResponse.kt:14` defaults to `emptyList()` and empty arrays are
    /// not dropped), so a body without the key is not this contract.
    func testToolRowNeedsItsParametersKey() throws {
        XCTAssertThrowsError(try JSONDecoder().decode(McpToolRow.self, from: Data(#"{"name":"x"}"#.utf8)))
        let bare = try JSONDecoder().decode(McpToolRow.self, from: Data(#"{"name":"x","parameters":[]}"#.utf8))
        XCTAssertTrue(bare.parameters.isEmpty)
        XCTAssertEqual(bare.id, "x")
    }

    /// `McpToolParameter`'s three columns are non-null `String`s with `""` defaults
    /// (`McpToolResponse.kt:20-29`), so all three keys always arrive — and the Swift defaults in its init
    /// (`McpToolRow.swift:29-33`) are what a hand-built row in this app has to agree with.
    func testParameterDefaultsMatchTheKotlinDefaults() throws {
        XCTAssertEqual(McpToolParameter().type, "string")
        XCTAssertEqual(McpToolParameter().name, "")
        XCTAssertEqual(McpToolParameter().label, "")
        XCTAssertEqual(McpToolParameter(name: "q", type: "string", description: "  ").label, "q")
    }

    // MARK: - per-user OAuth status

    /// A user who never authorized has no `mcp_user_credential` row, and the service answers
    /// `McpOAuthStatusResponse(authorized = false)` and nothing else (`McpOAuthUserServiceImpl.kt:232`).
    /// `authorized` and `scopes` are the two non-null defaults that always arrive
    /// (`McpOAuthStatusResponse.kt:18,24`); `status`, `accessExpiresAt`, `lastRefreshedAt` and `lastError`
    /// are nullable (`:21,27,30,33`) and therefore absent.
    func testNeverAuthorizedArrivesWithNoStatusKey() throws {
        let status = try Fixture.decode(
            Envelope<McpOAuthStatus>.self,
            "mcp-oauth-status-never-authorized"
        ).data!
        XCTAssertFalse(status.authorized)
        XCTAssertNil(status.status)
        XCTAssertEqual(status.scopes, [])
        XCTAssertTrue(status.grantedScopes.isEmpty)
        XCTAssertNil(status.accessExpiresAt)
        XCTAssertNil(status.lastRefreshedAt)
        XCTAssertNil(status.error)
        XCTAssertEqual(Set(try body("mcp-oauth-status-never-authorized").keys), ["authorized", "scopes"])
    }

    /// The three spellings of "no grant" have to read the same way, because the detail screen offers one
    /// 需要授权 entry whichever it is (`McpOAuth.swift:39-42`): the absent key, an expired token whose status
    /// column still says ACTIVE (`McpOAuthUserServiceImpl.kt:236-237` — usable needs both the ACTIVE status
    /// and a future expiry), and a REVOKED row (`entity/McpUserCredential.kt:STATUS_REVOKED`).
    /// The reading is `authorized` alone; the payload's own `status` is kept as a fact and never turned
    /// into "expired".
    func testEveryNoGrantAnswerReadsTheSame() throws {
        let never = try Fixture.decode(
            Envelope<McpOAuthStatus>.self,
            "mcp-oauth-status-never-authorized"
        ).data!
        let expired = try status(
            #"{"authorized":false,"status":"ACTIVE","scopes":["mcp:tools"],"accessExpiresAt":"2026-09-27 08:00:00","lastRefreshedAt":"2026-09-20 08:00:12"}"#
        )
        let revoked = try status(#"{"authorized":false,"status":"REVOKED","scopes":[]}"#)
        for reading in [never, expired, revoked] {
            XCTAssertFalse(reading.authorized)
        }
        XCTAssertEqual(expired.status, "ACTIVE", "the row still calls itself ACTIVE")
        XCTAssertEqual(expired.grantedScopes, ["mcp:tools"], "granted scopes survive a refusal to spend them")
        XCTAssertNil(never.status)
        XCTAssertEqual(revoked.status, "REVOKED", "kept as a fact, never turned into a verdict")
    }

    /// A `null` for an optional key is what `default-property-inclusion: non_null` prevents
    /// (`application.yml:25`), so the bytes do not contain this spelling today. It is asserted anyway, and
    /// against the fixture rather than in isolation: a deployment that answers the old way has to land on
    /// the very same reading as the one that omits the keys.
    func testExplicitNullReadsLikeAnAbsentKey() throws {
        let absent = try Fixture.decode(
            Envelope<McpOAuthStatus>.self,
            "mcp-oauth-status-never-authorized"
        ).data!
        let nulled = try status(#"{"authorized":false,"status":null,"scopes":[],"lastError":null}"#)
        XCTAssertEqual(nulled, absent)
    }

    /// `scopes` is the one key iOS declares non-optional on this body, and the reason is the DTO's own
    /// `= emptyList()` default (`McpOAuthStatusResponse.kt:24`) combined with empty arrays surviving
    /// `non_null` — so the key is always there, and a body without it is not this contract.
    func testOAuthStatusNeedsItsScopesKey() throws {
        XCTAssertThrowsError(try JSONDecoder().decode(McpOAuthStatus.self, from: Data(#"{"authorized":true}"#.utf8)))
        XCTAssertTrue(try status(#"{"authorized":true,"scopes":[]}"#).grantedScopes.isEmpty)
    }

    /// The happy path the badge turns green on: a usable grant, its scopes and the expiry the screen may
    /// show as a fact without interpreting it. `lastError` is absent because nothing failed
    /// (`McpOAuthStatusResponse.kt:33`).
    func testAuthorizedGrantCarriesScopesAndExpiry() throws {
        let status = try Fixture.decode(Envelope<McpOAuthStatus>.self, "mcp-oauth-status-authorized").data!
        XCTAssertTrue(status.authorized)
        XCTAssertEqual(status.status, "ACTIVE")
        XCTAssertEqual(status.grantedScopes, ["mcp:tools", "offline_access"])
        XCTAssertEqual(status.accessExpiresAt, "2026-09-28 10:12:00")
        XCTAssertEqual(status.lastRefreshedAt, "2026-09-28 08:12:03")
        XCTAssertNil(status.lastError)
        XCTAssertNil(status.error)
        XCTAssertEqual(Set(try body("mcp-oauth-status-authorized").keys), [
            "authorized", "status", "scopes", "accessExpiresAt", "lastRefreshedAt",
        ])
    }

    /// A blank `lastError` is no error — the same `hxPresented` rule the names follow
    /// (`McpOAuth.swift:63`), so the block does not render an empty failure line.
    func testLastErrorReadsBlankAsAbsent() throws {
        XCTAssertNil(try status(#"{"authorized":true,"status":"ACTIVE","scopes":[],"lastError":"   "}"#).error)
        XCTAssertEqual(
            try status(#"{"authorized":false,"scopes":[],"lastError":"resource mismatch"}"#).error,
            "resource mismatch"
        )
    }

    // MARK: - revoke

    /// `revoked = true` when there was nothing to revoke is the server's own answer
    /// (`McpOAuthUserServiceImpl.kt:253-257`), and its `message` is the only part that says which of the two
    /// happened — the claim at `McpOAuth.swift:68-70`, upheld by the payload. All three keys are non-null on
    /// the DTO (`McpOAuthRevokeResponse.kt:15,18,21`) so none of them is ever dropped.
    func testRevokeAnswersTrueEvenWhenThereWasNothingToRevoke() throws {
        let result = try Fixture.decode(
            Envelope<McpOAuthRevokeResult>.self,
            "mcp-oauth-revoke-no-grant"
        ).data!
        XCTAssertTrue(result.revoked)
        XCTAssertFalse(result.upstreamRevoked)
        XCTAssertEqual(
            result.message,
            "You have no authorization on this MCP server, so there was nothing to revoke"
        )
        XCTAssertEqual(result.explanation, result.message)
        XCTAssertEqual(Set(try body("mcp-oauth-revoke-no-grant").keys), ["revoked", "upstreamRevoked", "message"])
    }

    /// The branch the design insists on saying out loud: the local copy is gone while the authorization
    /// server's own copy stays valid until it expires, because it publishes no RFC 7009 endpoint
    /// (`McpOAuthUserServiceImpl.kt:287-289`). `revoked` is still true, so the verdict has to come from the
    /// message.
    func testRevokeWithoutAnUpstreamEndpointIsStillRevokedButSaysMore() throws {
        let result = try JSONDecoder().decode(
            McpOAuthRevokeResult.self,
            from: Data(
                #"{"revoked":true,"upstreamRevoked":false,"message":"The authorization here is cleared. This authorization server publishes no revocation endpoint (RFC 7009), so its own copy stays valid until it expires"}"#
                    .utf8
            )
        )
        XCTAssertTrue(result.revoked)
        XCTAssertFalse(result.upstreamRevoked)
        XCTAssertTrue(result.explanation?.contains("RFC 7009") ?? false)
    }

    // MARK: - the authorize hand-off

    /// The URL is the whole hand-off: `state`, `code_challenge` and the RFC 8707 `resource` ride inside it,
    /// in the order the service assembles them (`McpOAuthUserServiceImpl.kt:149-159`), and the body carries
    /// no `state` field at all — the claim at `McpOAuth.swift:81-83`, which is also why this app hands the
    /// URL over instead of parsing a state of its own.
    func testAuthorizeUrlCarriesItsOwnState() throws {
        let grant = try Fixture.decode(Envelope<McpOAuthAuthorization>.self, "mcp-oauth-authorize-url").data!
        XCTAssertEqual(Set(try body("mcp-oauth-authorize-url").keys), ["authorizeUrl", "issuer", "scopes", "expiresIn"])
        XCTAssertNil(try body("mcp-oauth-authorize-url")["state"], "no second place to invent a state from")

        let url = try XCTUnwrap(grant.url)
        let items = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems)
        XCTAssertEqual(
            items.map(\.name),
            [
                "response_type", "client_id", "redirect_uri", "state", "code_challenge",
                "code_challenge_method", "scope", "resource", "audience",
            ]
        )
        XCTAssertEqual(items.first(where: { $0.name == "response_type" })?.value, "code")
        XCTAssertEqual(items.first(where: { $0.name == "client_id" })?.value, "harnax-crm-client-7f3a")
        XCTAssertEqual(
            items.first(where: { $0.name == "redirect_uri" })?.value,
            "http://127.0.0.1:28081/mcp/oauth/callback",
            "the web console's callback page (`McpOAuthServiceImpl.kt:508` CALLBACK_PATH on the frontend base)"
        )
        XCTAssertEqual(items.first(where: { $0.name == "code_challenge_method" })?.value, "S256")
        XCTAssertEqual(items.first(where: { $0.name == "resource" })?.value, "https://crm.example.com/sse")
        XCTAssertEqual(
            items.first(where: { $0.name == "state" })?.value?.count,
            24 * 4 / 3,
            "24 random bytes as base64url without padding (`McpOAuthUserServiceImpl.kt:1014,958-962`)"
        )
        XCTAssertEqual(items.first(where: { $0.name == "code_challenge" })?.value?.count, 43, "SHA-256 of the verifier, same alphabet")
        // The service form-encodes (`McpOAuthUserServiceImpl.kt:952-954`), so the scope separator reaches
        // the wire as `+`. iOS passes the string through untouched: "fixing" it would send the
        // authorization server something other than what was promised.
        XCTAssertTrue(grant.authorizeUrl.contains("scope=mcp%3Atools+offline_access"))
    }

    /// `expiresIn` is `McpOAuthStateStore.TTL` in seconds (`McpOAuthStateStore.kt:106` = five minutes,
    /// sent as `TTL.seconds` at `McpOAuthUserServiceImpl.kt:166`): after that the state is gone and a new
    /// URL has to be asked for, which is the only reason the field exists. `issuer` and `scopes` are what the
    /// panel says the user is being sent to and what will be asked of them
    /// (`McpOAuthAuthorizeResponse.kt:16-25`, all four non-null so all four always arrive).
    func testAuthorizationPromiseIsMeasurable() throws {
        let grant = try Fixture.decode(Envelope<McpOAuthAuthorization>.self, "mcp-oauth-authorize-url").data!
        XCTAssertEqual(grant.expiresIn, 300)
        XCTAssertEqual(grant.issuer, "https://auth.example.com/realms/harnax")
        XCTAssertEqual(grant.requestedScopes, ["mcp:tools", "offline_access"])
        XCTAssertEqual(grant.scopes.count, 2, "requested, not granted — the server may answer narrower")
    }

    // MARK: - the write bodies

    /// `type` is the only non-null default on the create DTO (`McpServerCreateRequest.kt:26`), an omitted
    /// `authType` is NONE and an omitted `status`/`isPublic` is 1 (`McpServerServiceImpl.kt:135-136`), so a
    /// plain create really does send three fields and nothing else — the claim at
    /// `McpRequests.swift:5-8`.
    func testPlainCreateSendsThreeFieldsAndNothingElse() throws {
        let json = try JSONObject(
            McpServerDraft(name: "project-fs", type: "streamablehttp", url: "https://mcp.example.com/fs/mcp")
        )
        XCTAssertEqual(Set((json ?? [:]).keys), ["name", "type", "url"])
        XCTAssertEqual(json?["type"] as? String, "streamablehttp")
        XCTAssertNil(json?["status"], "let the server default to 1")
        XCTAssertNil(json?["isPublic"], "let the server default to public")
        XCTAssertNil(json?["authType"], "let the server default to NONE")
    }

    /// An OAuth create has to say `OAUTH2` in the same body as its config: a config on its own is refused,
    /// because `writeOAuthConfig` checks the resolved auth type (`McpServerServiceImpl.kt:417-420`) and an
    /// omitted one resolves to NONE (`:394-395`).
    func testOAuthCreatePairsTheConfigWithItsAuthType() throws {
        let json = try JSONObject(
            McpServerDraft(
                name: "crm-sse",
                type: "sse",
                url: "https://crm.example.com/sse",
                authType: McpAuthKind.oauth2.wireValue,
                oauthConfig: McpOAuthConfigValues(
                    authorizationServer: "https://auth.example.com/realms/harnax",
                    scopes: ["mcp:tools"],
                    resourceIndicator: true
                )
            )
        )
        XCTAssertEqual(json?["authType"] as? String, "OAUTH2")
        let config = try XCTUnwrap(json?["oauthConfig"] as? [String: Any])
        XCTAssertEqual(Set(config.keys), ["authorizationServer", "scopes", "resourceIndicator"])
        XCTAssertEqual(config["resourceIndicator"] as? Bool, true)
        XCTAssertNil(config["audience"], "a null optional does not become a key")

        // The defaults block: `scopes` and `resourceIndicator` are non-null on the DTO
        // (`McpOAuthConfig.kt:21,30`) so they go on the wire even when the operator touched nothing.
        let minimal = try JSONObject(McpOAuthConfigValues.defaults)
        XCTAssertEqual(Set((minimal ?? [:]).keys), ["scopes", "resourceIndicator"])
        XCTAssertEqual((minimal?["scopes"] as? [Any])?.count, 0, "an empty list is still a list on the wire")
    }

    /// `headers: []` clears the column while an omitted `headers` leaves it alone
    /// (`McpServerServiceImpl.kt:241-243` plus `SecretFieldEncryptor.kt:35`, where an empty or null list
    /// serialises to null), and the same pair holds for `envParams` (`:244-252`). Two different intents that
    /// only the presence of the key can express.
    func testEmptyArrayIsTheOnlyWayToClearTheEntryLists() throws {
        XCTAssertTrue(try XCTUnwrap(JSONObject(McpServerPatch(headers: []))?["headers"] as? [Any]).isEmpty)
        XCTAssertTrue(try XCTUnwrap(JSONObject(McpServerPatch(envParams: []))?["envParams"] as? [Any]).isEmpty)

        let untouched = try JSONObject(McpServerPatch(name: "renamed"))
        XCTAssertNil(untouched?["headers"], "omitted means the stored entries stay")
        XCTAssertNil(untouched?["envParams"])
        XCTAssertEqual(Set((untouched ?? [:]).keys), ["name"])
    }

    /// Absent means unchanged: the service applies each column inside `?.let`
    /// (`McpServerServiceImpl.kt:175-252`), so an edit form that touched nothing must send no keys at all.
    /// And `id` is deliberately not encoded — the controller takes it from the path
    /// (`McpServerController.kt:87-95`) even though the Kotlin DTO declares it (`McpServerUpdateRequest.kt`),
    /// the one claim at `McpRequests.swift:102-107` this file can check mechanically.
    func testPatchSendsOnlyWhatMovedAndNeverAnId() throws {
        XCTAssertTrue(try XCTUnwrap(JSONObject(McpServerPatch())).isEmpty)

        var patch = McpServerPatch()
        patch.status = 0
        patch.isPublic = 0
        patch.type = McpTransport.sse.wireValue
        patch.authType = McpAuthKind.none.wireValue
        patch.headers = [McpConfigEntry(key: "Authorization", value: "Bea****T2c7", secret: true)]
        let json = try JSONObject(patch)
        XCTAssertEqual(
            Set((json ?? [:]).keys),
            ["status", "isPublic", "type", "authType", "headers"]
        )
        XCTAssertNil(json?["id"], "the path owns the id")
        XCTAssertEqual(json?["status"] as? Int, 0, "a zero is a value here, not an absent field")
        XCTAssertEqual(json?["isPublic"] as? Int, 0)

        let entries = try XCTUnwrap(json?["headers"] as? [[String: Any]])
        XCTAssertEqual(Set(entries[0].keys), ["key", "value", "secret"])
        XCTAssertEqual(entries[0]["value"] as? String, "Bea****T2c7", "the mask goes back verbatim, which "
            + "is what makes the server keep the stored ciphertext (`SecretFieldEncryptor.kt:46-49`)")
    }

    /// An env-param row a form has not filled yet encodes as `envParamName` plus the two booleans — the
    /// non-null defaults of `ToolEnvParamEntry.kt:19,25,28` — and no `id` (`:13`, "only in response") and no
    /// `defaultValue` (`:31`). Sending an id this side never invented would be a claim about a stored row.
    func testNewEnvParamEntrySendsNameAndFlagsOnly() throws {
        let json = try JSONObject(EnvParamEntry(envParamName: "FS_TOKEN", required: true, secret: true))
        XCTAssertEqual(Set((json ?? [:]).keys), ["envParamName", "required", "secret"])
        XCTAssertEqual(json?["required"] as? Bool, true)
        XCTAssertNil(json?["id"])
        XCTAssertNil(json?["defaultValue"])
    }

    // MARK: - helpers

    /// A row the way the wire makes it: a column the service left out is a key that is absent, not a nil
    /// argument (`application.yml:25`).
    private func row(_ json: String) throws -> McpServerRow {
        try JSONDecoder().decode(McpServerRow.self, from: Data(json.utf8))
    }

    private func status(_ json: String) throws -> McpOAuthStatus {
        try JSONDecoder().decode(McpOAuthStatus.self, from: Data(json.utf8))
    }

    private func topLevel(_ name: String) throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: try Fixture.data(name)) as? [String: Any])
    }

    /// The `data` object of a fixture whose payload is a single object.
    private func body(_ name: String) throws -> [String: Any] {
        try XCTUnwrap(topLevel(name)["data"] as? [String: Any])
    }

    /// One record of the page fixture, as raw JSON — the decode side cannot show that a key was dropped
    /// rather than sent as null, so the assertions about inclusion go to the bytes.
    private func recordJSON(_ index: Int) throws -> [String: Any] {
        let records = try XCTUnwrap(body("mcp-servers-page")["records"] as? [[String: Any]])
        return records[index]
    }

    private func JSONObject<T: Encodable>(_ value: T) throws -> [String: Any]? {
        let data = try JSONEncoder().encode(value)
        return try JSONSerialization.jsonObject(with: data) as? [String: Any]
    }
}
