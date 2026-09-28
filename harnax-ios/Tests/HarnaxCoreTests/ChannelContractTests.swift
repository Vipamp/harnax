import XCTest

@testable import HarnaxCore

/// E1/E2's contract layer. Two things make this domain worth a test file of its own rather than a copy of
/// the environment-variable one: the credentials live inside a JSON *string* that the server masks on read and
/// merges over on write, and the row carries two different on/off columns that the console wires to two
/// different controls.
final class ChannelContractTests: XCTestCase {

    // MARK: - the row

    /// Every column is optional on the wire (`default-property-inclusion: non_null`), so a row that leaves
    /// half of them out still decodes.
    func testABareRowDecodes() throws {
        let row = try channel(#"{"id":9,"name":"助理","type":"feishu","agentId":3}"#)
        XCTAssertEqual(row.id, 9)
        XCTAssertEqual(row.title, "助理")
        XCTAssertEqual(row.channelType, .feishu)
        XCTAssertEqual(row.agentId, 3)
        XCTAssertNil(row.configJson)
        XCTAssertTrue(row.config.isEmpty)
        XCTAssertNil(row.mode)
    }

    /// `status` is the list's switch and `enabled` is auto-listen at service startup. A row that omits either
    /// reads as on, because both columns default to 1 in the insert.
    func testStatusAndEnabledAreDifferentColumns() throws {
        let stopped = try channel(#"{"status":0,"enabled":1}"#)
        XCTAssertFalse(stopped.isRunning)
        XCTAssertTrue(stopped.autoStarts)

        let noAutoStart = try channel(#"{"status":1,"enabled":0}"#)
        XCTAssertTrue(noAutoStart.isRunning)
        XCTAssertFalse(noAutoStart.autoStarts)

        let bare = try channel("{}")
        XCTAssertTrue(bare.isRunning, "a missing status column is the insert's default 1")
        XCTAssertTrue(bare.autoStarts)
    }

    /// The three capability switches are 0/1 columns and the server only ever writes 0 or 1, so a row without
    /// them reads as off rather than as "unset".
    func testCapabilitySwitchesReadNilAsOff() throws {
        let bare = try channel("{}")
        XCTAssertFalse(bare.thinkEnabled)
        XCTAssertFalse(bare.searchEnabled)
        XCTAssertFalse(bare.planEnabled)

        let on = try channel(#"{"enableThink":1,"enableSearch":1,"enablePlan":0}"#)
        XCTAssertTrue(on.thinkEnabled)
        XCTAssertTrue(on.searchEnabled)
        XCTAssertFalse(on.planEnabled)
    }

    /// A blank column is not a value: the list falls back to its own copy rather than drawing whitespace.
    func testBlankColumnsReadAsAbsent() throws {
        let row = try channel(#"{"name":"   ","type":"","description":"","agentName":""}"#)
        XCTAssertNil(row.title)
        XCTAssertNil(row.channelType)
        XCTAssertNil(row.rawTypeCode)
        XCTAssertNil(row.mode)
    }

    /// A type code outside the five still renders as a row (`fromCode` is case-sensitive server-side, but the
    /// page passes the filter string straight through), and iOS reads it as its own label.
    func testTypeCodesMatchIgnoringCaseAndUnknownOnesAreNotAnError() throws {
        XCTAssertEqual(ChannelType(code: "FEISHU"), .feishu)
        XCTAssertEqual(ChannelType(code: " wechat "), .wechat)
        XCTAssertNil(ChannelType(code: "slack"))
        XCTAssertNil(ChannelType(code: nil))
    }

    /// The mode codes are the exception: the service compares them verbatim, so this side must not be more
    /// forgiving than the server. `long_polling` is the only code that is not one lower-case word.
    func testModeCodesAreCaseSensitiveAndTheWireSpellingsDecode() throws {
        XCTAssertEqual(ChannelMode(code: "long_polling"), .longPolling)
        XCTAssertEqual(ChannelMode(code: "websocket"), .websocket)
        XCTAssertNil(ChannelMode(code: "WEBSOCKET"))
        XCTAssertNil(ChannelMode(code: "longPolling"))
        XCTAssertTrue(ChannelMode.allCases.allSatisfy { ChannelMode(code: $0.rawValue) == $0 })
    }

    func testOnlyAWebhookRowHasACallback() {
        XCTAssertTrue(ChannelMode.webhook.supportsCallback)
        XCTAssertFalse(ChannelMode.websocket.supportsCallback)
        XCTAssertFalse(ChannelMode.stream.supportsCallback)
        XCTAssertFalse(ChannelMode.longPolling.supportsCallback)
    }

    /// Every row the service creates carries `creator = system`, so on this domain the generic creator rule
    /// lets only an administrator through.
    func testManageFlagFollowsTheAccount() throws {
        let row = try channel(#"{"creator":"system"}"#)
        XCTAssertTrue(row.manageable(by: AccountSnapshot(username: "admin", isAdministrator: true)))
        XCTAssertFalse(row.manageable(by: AccountSnapshot(username: "alice")))
        XCTAssertFalse(row.manageable(by: nil))
    }

    // MARK: - the type/mode matrix

    /// A mode outside the type's list produces a channel that starts, receives nothing and is never stopped,
    /// so the list is a runtime constraint and the form must not offer anything else.
    func testEachTypeOffersOnlyModesTheServiceCanRun() {
        XCTAssertEqual(ChannelType.wecom.modes, [.websocket])
        XCTAssertEqual(ChannelType.dingtalk.modes, [.stream])
        XCTAssertEqual(ChannelType.feishu.modes, [.websocket, .webhook])
        XCTAssertEqual(ChannelType.wechat.modes, [.longPolling])
        XCTAssertEqual(ChannelType.http.modes, [.webhook])
        for type in ChannelType.allCases {
            XCTAssertEqual(type.defaultMode, type.modes.first)
        }
    }

    /// Personal WeChat has exactly one legal mode, so the console hides the selector instead of showing a
    /// control with one option.
    func testOnlyWechatHidesTheModePicker() {
        XCTAssertTrue(ChannelType.wechat.hidesModePicker)
        XCTAssertEqual(ChannelType.allCases.filter { !$0.hidesModePicker },
                       [.wecom, .feishu, .dingtalk, .http])
    }

    func testOnlyWechatHasNoCredentialFields() {
        XCTAssertEqual(Set(ChannelType.allCases.filter { $0.fields(mode: nil).isEmpty }), [.wechat])
        XCTAssertTrue(ChannelType.wechat.showsScanHint)
        XCTAssertFalse(ChannelType.feishu.showsScanHint)
    }

    /// `appId` means "Bot ID" on WeCom and "App Key" on DingTalk — the labels hang off the type, not the key.
    func testTheSameKeyIsNamedDifferentlyPerType() {
        XCTAssertEqual(ChannelType.wecom.fields(mode: nil).map { $0.labelKey },
                       ["channel.field.wecom.botId", "channel.field.wecom.botSecret"])
        XCTAssertEqual(ChannelType.dingtalk.fields(mode: nil).map { $0.labelKey },
                       ["channel.field.dingtalk.appKey", "channel.field.dingtalk.appSecret"])
        XCTAssertEqual(ChannelType.feishu.fields(mode: nil).map { $0.key }, [.appId, .appSecret])
        XCTAssertEqual(ChannelType.http.fields(mode: nil).map { $0.key }, [.webhookUrl])
    }

    /// Feishu webhook is the one type whose field list grows with the mode: the callback pair is needed to
    /// verify and decrypt a push.
    func testFeishuGainsTheCallbackPairOnlyInWebhookMode() {
        XCTAssertEqual(ChannelType.feishu.fields(mode: .websocket).count, 2)
        XCTAssertEqual(ChannelType.feishu.fields(mode: nil).count, 2)
        XCTAssertEqual(ChannelType.feishu.fields(mode: .webhook).map { $0.key },
                       [.appId, .appSecret, .encodingAesKey, .token])
    }

    /// The catalogue gate only scans literal keys, so every field carries its placeholder key as a field
    /// rather than building one at runtime.
    func testEveryFieldCarriesItsOwnPlaceholderKey() {
        for type in ChannelType.allCases {
            for field in type.fields(mode: .webhook) {
                XCTAssertEqual(field.placeholderKey, field.labelKey + ".placeholder")
            }
        }
    }

    /// Secret fields are the ones the server masks (`ChannelServiceImpl`'s secret set), and the count is the
    /// same three plus Feishu's callback pair.
    func testSecretFieldsAreTheCredentialOnes() {
        XCTAssertEqual(ChannelType.wecom.fields(mode: nil).map { $0.isSecret }, [false, true])
        XCTAssertEqual(ChannelType.feishu.fields(mode: .webhook).map { $0.isSecret }, [false, true, true, true])
        XCTAssertEqual(ChannelType.http.fields(mode: nil).map { $0.isSecret }, [false])
    }

    /// Everything is optional when editing: a stored value already covers it. The only mode-conditional
    /// requirement is Feishu's callback pair.
    func testRequiredOnCreateFollowsTypeAndMode() {
        for type in [ChannelType.wecom, .dingtalk] {
            for field in type.fields(mode: nil) {
                XCTAssertTrue(type.isRequiredOnCreate(field, mode: nil), "\(type)/\(field.key)")
            }
        }
        let feishu = ChannelType.feishu
        for field in feishu.fields(mode: .websocket) {
            XCTAssertTrue(feishu.isRequiredOnCreate(field, mode: .websocket), field.key.rawValue)
        }
        XCTAssertTrue(feishu.isRequiredOnCreate(feishu.fields(mode: .webhook)[2], mode: .webhook))
        XCTAssertTrue(feishu.isRequiredOnCreate(feishu.fields(mode: .webhook)[3], mode: .webhook))
        for field in ChannelType.http.fields(mode: .webhook) {
            XCTAssertFalse(ChannelType.http.isRequiredOnCreate(field, mode: .webhook))
        }
    }

    // MARK: - configJson

    func testAnAbsentOrUnreadableBlobIsAnEmptyConfig() {
        XCTAssertTrue(ChannelConfig(json: nil).isEmpty)
        XCTAssertTrue(ChannelConfig(json: "  ").isEmpty)
        XCTAssertTrue(ChannelConfig(json: "[1,2]").isEmpty)
        XCTAssertTrue(ChannelConfig(json: "\"a\"").isEmpty)
        XCTAssertTrue(ChannelConfig(json: "not json").isEmpty)
    }

    /// The server parses the blob as `Map<String, Any?>`, so anything it has in there must come back out
    /// unchanged — including the keys this screen has no field for and the values that are not strings. The
    /// console's merge-over-stored exists because rewriting the whole blob erased the scan's tokens.
    func testUnknownKeysAndNonStringValuesSurviveARoundTrip() throws {
        let config = ChannelConfig(
            json: #"{"appId":"a","botToken":"tok","retry":5,"nested":{"x":1},"dead":null,"flag":true}"#)
        XCTAssertEqual(config.text(for: .appId), "a")
        XCTAssertEqual(config.text(for: "botToken"), "tok")
        XCTAssertEqual(config.text(for: "retry"), "5")
        XCTAssertEqual(config.text(for: "nested"), #"{"x":1}"#)
        XCTAssertEqual(config.text(for: "dead"), "null")
        XCTAssertEqual(config.text(for: "flag"), "true")

        let written = try XCTUnwrap(config.json(forWrite: true))
        let again = ChannelConfig(json: written)
        XCTAssertEqual(again.keys, config.keys)
        XCTAssertEqual(again.text(for: "botToken"), "tok")
        XCTAssertEqual(again.text(for: "retry"), "5")
        XCTAssertEqual(again.text(for: "nested"), #"{"x":1}"#)
        XCTAssertEqual(again.text(for: "dead"), "null")
        XCTAssertEqual(again.text(for: "flag"), "true")
    }

    func testKeysAreReadInSortedOrder() {
        let config = ChannelConfig(json: #"{"token":"t","appId":"a","encodingAesKey":"k"}"#)
        XCTAssertEqual(config.keys, ["appId", "encodingAesKey", "token"])
        XCTAssertTrue(try XCTUnwrap(config.json(forWrite: true)).hasPrefix(#"{"appId": "a""#))
    }

    /// A create sends nothing rather than an empty object, and an edit sends `{}` so that clearing every
    /// credential actually clears them.
    func testAnEmptyConfigWritesNilOnCreateAndAnObjectOnEdit() {
        var config = ChannelConfig()
        config.setValue("  ", for: .appId)
        XCTAssertNil(config.json(forWrite: false))
        XCTAssertEqual(config.json(forWrite: true), "{}")
    }

    /// Setting a field blank removes the key outright — which is how the console reads "cleared the field".
    func testSettingABlankValueRemovesTheKey() {
        var config = ChannelConfig(json: #"{"appId":"a","appSecret":"s"}"#)
        config.setValue(nil, for: .appId)
        XCTAssertNil(config.text(for: .appId))
        XCTAssertEqual(config.keys, ["appSecret"])

        config.setValue("new", for: .appId)
        XCTAssertEqual(config.text(for: .appId), "new")
    }

    func testQuotedTextIsEscapedAndReadsBackIdentically() throws {
        var config = ChannelConfig()
        config.setValue("say \"hi\"\\n", for: .appSecret)
        let written = try XCTUnwrap(config.json(forWrite: true))
        XCTAssertEqual(ChannelConfig(json: written).text(for: .appSecret), "say \"hi\"\\n")
    }

    /// `MAX_CONFIG_JSON_CHARS` = 20 000 on the service side, and it counts the whole blob. One `appId` entry
    /// costs 13 fixed characters (`{`, `"appId"`, `": "`, the value's two quotes, `}`).
    func testTheWholeBlobIsRefusedPastTwentyThousandCharacters() {
        var config = ChannelConfig()
        config.setValue(String(repeating: "x", count: ChannelConfig.maximumTextCount - 13), for: .appId)
        XCTAssertEqual(try XCTUnwrap(config.json(forWrite: true)).count, ChannelConfig.maximumTextCount)

        config.setValue(String(repeating: "x", count: ChannelConfig.maximumTextCount - 12), for: .appId)
        XCTAssertNil(config.json(forWrite: true))
    }

    /// The masked form of a credential is display data. A WeChat row counts as bound only once a scan has put
    /// a usable token in the blob.
    func testWechatBoundReadsTheScanTokenPastTheMask() throws {
        XCTAssertFalse(try channel(#"{"configJson":"{\"appId\":\"wx\"}"}"#).isWechatBound)
        XCTAssertFalse(
            try channel(#"{"configJson":"{\"botToken\":\"ab****cd\"}"}"#).isWechatBound,
            "a masked token is a token the reader cannot use"
        )
        XCTAssertTrue(try channel(#"{"configJson":"{\"botToken\":\"real-token\"}"}"#).isWechatBound)
    }

    // MARK: - the write bodies

    /// The three `@NotBlank`/`@NotNull` fields always go out; the rest are left off the JSON when unset,
    /// because a missing `enableThink` is what lets the bound model decide.
    func testDraftAlwaysSendsTheThreeRequiredFields() throws {
        let json = try JSONObject(ChannelDraft(name: "助理", type: "feishu", agentId: 3))
        XCTAssertEqual(json?["name"] as? String, "助理")
        XCTAssertEqual(json?["type"] as? String, "feishu")
        XCTAssertEqual(json?["agentId"] as? Int64, 3)
        for key in ["communicationMode", "enabled", "configJson", "enableThink", "enableSearch", "enablePlan",
                    "description"] {
            XCTAssertNil(json?[key], "\(key) must stay off the body when unset")
        }
    }

    func testDraftCarriesTheThreeCapabilitySwitchesAsIntegers() throws {
        let json = try JSONObject(
            ChannelDraft(name: "n", type: "wecom", agentId: 1, communicationMode: "websocket", enabled: 0,
                         configJson: "{\"appId\":\"a\"}", enableThink: 1, enableSearch: 0, enablePlan: 1,
                         description: nil)
        )
        XCTAssertEqual(json?["enabled"] as? Int, 0, "auto-start is a 0/1 column, not a JSON boolean")
        XCTAssertEqual(json?["enableThink"] as? Int, 1)
        XCTAssertEqual(Set((json ?? [:]).keys),
                       ["name", "type", "agentId", "communicationMode", "enabled", "configJson",
                        "enableThink", "enableSearch", "enablePlan"])
    }

    /// Every field of the update DTO is optional and a `nil` means "unchanged", so an untouched form must send
    /// an empty object rather than ten nulls.
    func testAnEmptyChangeEncodesNoKeysAtAll() throws {
        XCTAssertTrue(try XCTUnwrap(JSONObject(ChannelChange())).isEmpty)
    }

    func testChangeCarriesOnlyTheColumnsThatMoved() throws {
        let json = try JSONObject(ChannelChange(name: nil, enablePlan: 0))
        XCTAssertEqual(Set((json ?? [:]).keys), ["enablePlan"])
        XCTAssertNil(json?["enableThink"], "0 is a real answer and nil is not sent at all")
    }

    // MARK: - the sandbox read

    /// `active` is untyped on the runtime side, so an entry this side cannot read is "no answer" — and no
    /// answer must never be drawn as "stopped".
    func testSandboxAnswersSplitIntoRunningIdleAndUnknown() throws {
        let map = try JSONDecoder().decode(SandboxStatusMap.self, from: Data(
            #"{"chn-a":{"active":true},"chn-b":{"active":false},"chn-c":{"active":"yes"},"chn-d":{},"chn-e":"up"}"#
                .utf8))
        XCTAssertEqual(map.status(for: "chn-a"), .running)
        XCTAssertEqual(map.status(for: "chn-b"), .idle)
        XCTAssertEqual(map.status(for: "chn-c"), .unknown)
        XCTAssertEqual(map.status(for: "chn-d"), .unknown)
        XCTAssertEqual(map.status(for: "chn-e"), .unknown)
        XCTAssertEqual(map.status(for: "chn-missing"), .unknown)
        XCTAssertEqual(map.status(for: nil), .unknown)
        XCTAssertEqual(map.status(for: "  "), .unknown)
    }

    func testANumericActiveFlagStillReadsAsRunning() throws {
        let map = try JSONDecoder().decode(SandboxStatusMap.self,
                                          from: Data(#"{"chn-a":{"active":1}}"#.utf8))
        XCTAssertEqual(map.status(for: "chn-a"), .running)
    }

    // MARK: - the WeChat scan

    /// `data` is the bare `<img src>` string, so the type decodes through a single value container.
    func testTheQRPayloadIsTheDataUrlTheConsoleUsesVerbatim() throws {
        let qr = try JSONDecoder().decode(WechatQrCode.self,
                                         from: Data(#""data:image/png;base64,aGVsbG8=""#.utf8))
        XCTAssertEqual(qr.base64Body, "aGVsbG8=")
        XCTAssertEqual(qr.pngData, Data("hello".utf8))
    }

    /// A payload with no prefix still yields its own text, and a blank one yields nothing rather than a decode
    /// that the image view would then show as an empty frame.
    func testTheQRPayloadToleratesAPrefixlessOrBlankBody() throws {
        XCTAssertEqual(WechatQrCode(dataUrl: "aGVsbG8=").base64Body, "aGVsbG8=")
        XCTAssertNil(WechatQrCode(dataUrl: "").base64Body)
        XCTAssertNil(WechatQrCode(dataUrl: "data:image/png;base64,").pngData)
        XCTAssertNil(WechatQrCode(dataUrl: "not-base64!!!").pngData)
    }

    /// An unknown status code keeps polling rather than ending the flow, and a missing message is an empty
    /// string rather than a blank row.
    func testThePollFallsBackToWaiting() throws {
        XCTAssertEqual(try update(#"{"status":"SCANNED","message":"已扫码"}"#).phase, .scanned)
        XCTAssertEqual(try update(#"{"status":"expired","message":"过期"}"#).phase, .expired)
        XCTAssertEqual(try update(#"{"status":"BOGUS"}"#).phase, .waiting)
        XCTAssertEqual(try update(#"{"status":null,"message":null}"#).phase, .waiting)
        XCTAssertEqual(try update(#"{"status":"WAITING"}"#).message, "")
        XCTAssertEqual(try update(#"{"status":"LOGGED_IN","message":"ok"}"#).message, "ok")
    }

    // MARK: - helpers

    private func channel(_ json: String) throws -> ChannelSummary {
        try JSONDecoder().decode(ChannelSummary.self, from: Data(json.utf8))
    }

    private func update(_ json: String) throws -> WechatLoginUpdate {
        try JSONDecoder().decode(WechatLoginUpdate.self, from: Data(json.utf8))
    }

    private func JSONObject<T: Encodable>(_ value: T) throws -> [String: Any]? {
        let data = try JSONEncoder().encode(value)
        return try JSONSerialization.jsonObject(with: data) as? [String: Any]
    }
}
