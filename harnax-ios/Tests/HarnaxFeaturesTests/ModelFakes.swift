import Foundation
import HarnaxCore

/// The model surface, with the same reply-queue discipline as `FakeAgents`: a request nobody queued answers
/// as a failure and still shows up in the recorded calls, so an extra round trip reads as a wrong count.
///
/// Lives in its own file because the shared `Fakes.swift` is a merge point for every domain.
final class FakeModelCatalog: ModelCataloging, @unchecked Sendable {
    // level one
    private(set) var providerRequests: [(num: Int, size: Int)] = []
    private(set) var providerFilters: [(name: String?, type: String?, status: Int?)] = []
    var providerReplies: [Result<Page<ModelProviderSummary>, APIError>] = []

    private(set) var statsRequests: [Int64] = []
    var statsReply: Result<ModelProviderStats, APIError> = .success(ModelProviderStats(totalModels: 2, enabledModels: 1, disabledModels: 1))

    private(set) var providerStatusCalls: [(id: Int64, enabled: Bool)] = []
    var providerStatusReplies: [Result<EmptyResponse, APIError>] = []
    private(set) var providerDeleteCalls: [Int64] = []
    var providerDeleteReplies: [Result<EmptyResponse, APIError>] = []
    private(set) var providerSaveCalls: [(id: Int64?, body: ModelProviderSaveRequest)] = []
    var providerSaveReplies: [Result<EmptyResponse, APIError>] = []

    private(set) var testCalls: [Int64] = []
    /// The stack answers a plain boolean today, and `connectivityTest` answers `true` for everything
    /// (`ModelProviderServiceImpl.kt:163-169`), so a test that wants the other answer has to queue it.
    var testReplies: [Result<Bool, APIError>] = []

    // level two
    private(set) var modelRequests: [(providerID: Int64, num: Int, size: Int)] = []
    private(set) var modelFilters: [(name: String?, status: Int?, tags: [String])] = []
    var modelReplies: [Result<Page<ModelSummary>, APIError>] = []
    private(set) var modelStatusCalls: [(id: Int64, enabled: Bool)] = []
    var modelStatusReplies: [Result<EmptyResponse, APIError>] = []
    private(set) var modelDeleteCalls: [Int64] = []
    var modelDeleteReplies: [Result<EmptyResponse, APIError>] = []
    private(set) var modelSaveCalls: [(id: Int64?, body: ModelSaveRequest)] = []
    var modelSaveReplies: [Result<EmptyResponse, APIError>] = []

    func providerPage(
        name: String?,
        type: String?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<ModelProviderSummary>, APIError> {
        providerRequests.append((num: num, size: size))
        providerFilters.append((name: name, type: type, status: status))
        return providerReplies.isEmpty ? .failure(.decoding) : providerReplies.removeFirst()
    }

    func providerStats(id: Int64) async -> Result<ModelProviderStats, APIError> {
        statsRequests.append(id)
        return statsReply
    }

    func setProviderStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> {
        providerStatusCalls.append((id: id, enabled: enabled))
        return providerStatusReplies.isEmpty ? .success(EmptyResponse()) : providerStatusReplies.removeFirst()
    }

    func deleteProvider(id: Int64) async -> Result<EmptyResponse, APIError> {
        providerDeleteCalls.append(id)
        return providerDeleteReplies.isEmpty ? .success(EmptyResponse()) : providerDeleteReplies.removeFirst()
    }

    func saveProvider(id: Int64?, request: ModelProviderSaveRequest) async -> Result<EmptyResponse, APIError> {
        providerSaveCalls.append((id: id, body: request))
        return providerSaveReplies.isEmpty ? .success(EmptyResponse()) : providerSaveReplies.removeFirst()
    }

    func testProvider(id: Int64) async -> Result<Bool, APIError> {
        testCalls.append(id)
        return testReplies.isEmpty ? .success(true) : testReplies.removeFirst()
    }

    func modelPage(
        providerID: Int64,
        name: String?,
        status: Int?,
        tags: [String],
        num: Int,
        size: Int
    ) async -> Result<Page<ModelSummary>, APIError> {
        modelRequests.append((providerID: providerID, num: num, size: size))
        modelFilters.append((name: name, status: status, tags: tags))
        return modelReplies.isEmpty ? .failure(.decoding) : modelReplies.removeFirst()
    }

    func setModelStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> {
        modelStatusCalls.append((id: id, enabled: enabled))
        return modelStatusReplies.isEmpty ? .success(EmptyResponse()) : modelStatusReplies.removeFirst()
    }

    func deleteModel(id: Int64) async -> Result<EmptyResponse, APIError> {
        modelDeleteCalls.append(id)
        return modelDeleteReplies.isEmpty ? .success(EmptyResponse()) : modelDeleteReplies.removeFirst()
    }

    func saveModel(id: Int64?, request: ModelSaveRequest) async -> Result<EmptyResponse, APIError> {
        modelSaveCalls.append((id: id, body: request))
        return modelSaveReplies.isEmpty ? .success(EmptyResponse()) : modelSaveReplies.removeFirst()
    }
}

extension ModelProviderSummary {
    static func stub(_ fields: [String: Any]) throws -> ModelProviderSummary {
        let data = try JSONSerialization.data(withJSONObject: fields)
        return try JSONDecoder().decode(ModelProviderSummary.self, from: data)
    }

    /// `ModelProviderResponse.kt` gives `type`, `name`, `status`, `isPublic`, `creator` and both timestamps
    /// non-null defaults, so a row that is missing one of them is not a row the stack can answer.
    static func stub(_ id: Int, name: String = "厂商", status: Int = 1, isPublic: Int = 1) throws -> ModelProviderSummary {
        try stub([
            "id": id,
            "type": "dashscope",
            "name": name,
            "status": status,
            "isPublic": isPublic,
            "creator": "heqingsong",
            "createTime": "2026-09-01 10:00:00",
            "updateTime": "2026-09-01 10:00:00",
        ])
    }
}

extension ModelSummary {
    static func stub(_ fields: [String: Any]) throws -> ModelSummary {
        let data = try JSONSerialization.data(withJSONObject: fields)
        return try JSONDecoder().decode(ModelSummary.self, from: data)
    }

    /// Every field here is nullable on the wire, so the only key a row needs is its id.
    static func stub(_ id: Int, name: String = "模型", status: Int = 1) throws -> ModelSummary {
        try stub(["id": id, "name": name, "status": status])
    }
}
