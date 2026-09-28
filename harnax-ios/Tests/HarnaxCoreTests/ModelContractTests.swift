import XCTest

@testable import HarnaxCore

/// The model domain's contract, pinned against the two real reply shapes.
///
/// What is worth pinning here is not field names but the three places where the backend's
/// `default-property-inclusion: non_null` (`harnax-admin/src/main/resources/application.yml`) means a key can
/// be *absent* rather than null, and where a value is derived instead of stored:
/// - the provider row's `apiKey` is a mask the server computed, never a secret;
/// - `tags` is computed from the five capability columns
///   (`ModelResponse.kt:79-87`), so iOS reads the columns and must agree with the tag list;
/// - `thinkingMode` may be missing on a row written before the column existed, in which case
///   `supportReasoning` is the answer (`harnax-webui/src/pages/model/components/ModelForm.tsx:33-65`).
final class ModelContractTests: XCTestCase {
    // MARK: - provider rows

    func testProviderPageDecodesBothNullShapes() throws {
        let page = try Fixture.decode(Envelope<Page<ModelProviderSummary>>.self, "model-providers-page").data!
        XCTAssertEqual(page.total, 2)
        XCTAssertEqual(page.records.count, 2)

        let owned = page.records[0]
        XCTAssertEqual(owned.providerID, 3)
        XCTAssertEqual(owned.id, 3, "the optional `id` view is what `PagedState.removeRow` keys on")
        XCTAssertEqual(owned.name, "阿里云百炼")
        XCTAssertEqual(owned.title, "阿里云百炼")
        XCTAssertTrue(owned.isEnabled)
        XCTAssertTrue(owned.isShared)
        XCTAssertEqual(owned.maskedCredential, "sk****3f9c")
        XCTAssertTrue(owned.hasCredential)
        XCTAssertEqual(owned.baseUrl, "https://dashscope.aliyuncs.com/compatible-mode/v1")

        // Row two is a borrowed provider: a whitespace name, an explicit null description, and — because the
        // server drops null keys — no `apiKey` and no `baseUrl` at all.
        let borrowed = page.records[1]
        XCTAssertEqual(borrowed.providerID, 4)
        XCTAssertNil(borrowed.title, "a blank name is not a name")
        XCTAssertNil(borrowed.description)
        XCTAssertNil(borrowed.apiKeyMasked)
        XCTAssertFalse(borrowed.hasCredential)
        XCTAssertNil(borrowed.baseUrl)
        XCTAssertFalse(borrowed.isEnabled)
        XCTAssertFalse(borrowed.isShared)
    }

    /// `ModelProviderResponse.kt:13-43` declares `type`, `name`, `status`, `isPublic`, `creator` and the two
    /// timestamps non-null, so the minimal row the service can actually produce still decodes.
    func testMinimalProviderRowDecodes() throws {
        let row = try decode(
            ModelProviderSummary.self,
            """
            {"id": 9, "type": "openai", "name": "OpenAI", "status": 1, "isPublic": 0,
             "creator": "heqingsong", "createTime": "2026-09-01 10:00:00", "updateTime": "2026-09-01 10:00:00"}
            """
        )
        XCTAssertEqual(row.providerID, 9)
        XCTAssertEqual(row.technicalType, "openai")
        XCTAssertNil(row.description)
        XCTAssertNil(row.apiKeyMasked)
        XCTAssertTrue(row.isEnabled)
        XCTAssertFalse(row.isShared)
    }

    func testProviderStatsDecode() throws {
        let stats = try decode(ModelProviderStats.self, #"{"totalModels": 4, "enabledModels": 3, "disabledModels": 1}"#)
        XCTAssertEqual(stats.totalModels, 4)
        XCTAssertEqual(stats.enabledModels, 3)
        XCTAssertEqual(stats.disabledModels, 1)
    }

    // MARK: - model rows

    func testModelPageDerivesCapabilitiesAndThinking() throws {
        let page = try Fixture.decode(Envelope<Page<ModelSummary>>.self, "models-page").data!
        XCTAssertEqual(page.records.count, 3)

        let chat = page.records[0]
        XCTAssertEqual(chat.id, 21)
        XCTAssertEqual(chat.title, "通义千问 Max")
        XCTAssertEqual(chat.technicalName, "qwen-max")
        XCTAssertEqual(chat.providerTitle, "阿里云百炼")
        XCTAssertEqual(chat.thinking, .required)
        XCTAssertEqual(chat.capabilities, [.internet, .reasoning, .tool], "the tag order the server computes")
        XCTAssertEqual(chat.price, 12.5)
        XCTAssertTrue(chat.isEnabled)

        // An embedding row: every capability key absent, so nothing is set and thinking is off.
        let embedding = page.records[1]
        XCTAssertEqual(embedding.id, 22)
        XCTAssertNil(embedding.description)
        XCTAssertNil(embedding.price)
        XCTAssertTrue(embedding.capabilities.isEmpty)
        XCTAssertEqual(embedding.thinking, .off)
        XCTAssertFalse(embedding.isShared)
        XCTAssertEqual(embedding.tags, [], "the server answers an empty tag list, it does not drop the key")

        // A row older than the `thinkingMode` column: the reasoning bit is the only answer it carries, and
        // both its names are unusable, so the presenter has to fall back.
        let legacy = page.records[2]
        XCTAssertEqual(legacy.id, 23)
        XCTAssertNil(legacy.title)
        XCTAssertNil(legacy.technicalName, "whitespace is not a name either")
        XCTAssertNil(legacy.providerName, "fromEntity never sets it; this row's provider key is absent")
        XCTAssertEqual(legacy.thinking, .optional)
        XCTAssertEqual(legacy.capabilities, [.reasoning])
        XCTAssertEqual(legacy.price, 0)
        XCTAssertFalse(legacy.isEnabled)
    }

    /// Every field of `ModelResponse.kt:11-51` is `var x: T? = null`, so a row with nothing but an id is a
    /// legal row.
    func testModelRowOfOnlyAnIdDecodes() throws {
        let row = try decode(ModelSummary.self, #"{"id": 1}"#)
        XCTAssertEqual(row.id, 1)
        XCTAssertNil(row.name)
        XCTAssertNil(row.status)
        XCTAssertTrue(row.isEnabled, "only an explicit 0 means stopped")
        XCTAssertFalse(row.isShared)
    }

    func testThinkingModeFallsBackForUnreadableStoredValues() {
        XCTAssertEqual(ThinkingMode.resolve(stored: 2, supportReasoning: 0), .required)
        XCTAssertEqual(ThinkingMode.resolve(stored: 1, supportReasoning: 0), .optional)
        XCTAssertEqual(ThinkingMode.resolve(stored: 0, supportReasoning: 1), .off, "a stored 0 wins")
        XCTAssertEqual(ThinkingMode.resolve(stored: 7, supportReasoning: 1), .optional, "out of range reads as the old row it is")
        XCTAssertEqual(ThinkingMode.resolve(stored: nil, supportReasoning: nil), .off)
        XCTAssertEqual(ThinkingMode.required.supportReasoning, 1)
        XCTAssertEqual(ThinkingMode.off.supportReasoning, 0)
    }

    // MARK: - request bodies

    /// The two rules that lose an operator's API key if they are wrong: a blank credential is left off
    /// entirely, and the type is only sent on create (`ModelProviderServiceImpl.kt:108-112`).
    func testProviderBodySendsOnlyWhatChanged() throws {
        let body = ModelProviderSaveRequest(
            type: nil,
            name: "  renamed  ",
            description: "",
            apiKey: "   ",
            baseUrl: nil,
            isPublic: false
        )
        let json = try jsonDictionary(body)
        XCTAssertEqual(Set(json.keys), ["name", "description", "isPublic"])
        XCTAssertEqual(json["description"] as? String, "", "an empty description must travel, or it can never be cleared")
        XCTAssertEqual(json["isPublic"] as? Int, 0)

        let created = try jsonDictionary(
            ModelProviderSaveRequest(
                type: "ollama",
                name: "Local",
                description: "dev box",
                apiKey: "sk-local",
                baseUrl: "http://127.0.0.1:11434",
                isPublic: true
            )
        )
        XCTAssertEqual(created["type"] as? String, "ollama")
        XCTAssertEqual(created["apiKey"] as? String, "sk-local")
        XCTAssertEqual(created["isPublic"] as? Int, 1)
    }

    /// `supportReasoning` is a projection of `thinkingMode`, never its own switch
    /// (`ModelServiceImpl.kt:160-167`), and a non-chat body carries zeroed bits
    /// (`harnax-webui/src/pages/model/components/ModelForm.tsx:67-78`).
    func testModelBodyDerivesTheReasoningBit() throws {
        func bits(_ thinking: ThinkingMode, type: String = "chat") throws -> [String: Any] {
            try jsonDictionary(
                ModelSaveRequest(
                    name: "演示",
                    modelName: "demo-model",
                    providerId: 3,
                    description: "",
                    modelType: type,
                    thinking: thinking,
                    supportsInternet: true,
                    supportsTool: true,
                    supportsMcp: true,
                    supportsVision: true,
                    price: 0.0001,
                    isPublic: true
                )
            )
        }

        let required = try bits(.required)
        XCTAssertEqual(required["thinkingMode"] as? Int, 2)
        XCTAssertEqual(required["supportReasoning"] as? Int, 1)
        XCTAssertEqual(required["supportInternet"] as? Int, 1)
        XCTAssertEqual(required["price"] as? Double, 0.0001)

        let off = try bits(.off)
        XCTAssertEqual(off["thinkingMode"] as? Int, 0)
        XCTAssertEqual(off["supportReasoning"] as? Int, 0, "turning thinking off has to write the 0, not omit the key")

        let embedding = try bits(.required, type: "embedding")
        for key in ["thinkingMode", "supportReasoning", "supportInternet", "supportTool", "supportMcp", "supportVision"] {
            XCTAssertEqual(embedding[key] as? Int, 0, "\(key) must be off for a non-chat model")
        }
    }

    // MARK: - helpers

    private func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try JSONDecoder().decode(type, from: Data(json.utf8))
    }

    private func jsonDictionary<T: Encodable>(_ value: T) throws -> [String: Any] {
        let data = try JSONEncoder().encode(value)
        guard let dictionary = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            XCTFail("\(value) did not encode to a JSON object")
            return [:]
        }
        return dictionary
    }
}
