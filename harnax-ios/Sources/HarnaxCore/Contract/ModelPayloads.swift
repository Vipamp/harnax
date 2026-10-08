import Foundation

/// Body of `POST /api/admin/model-providers` and `PUT /api/admin/model-providers/update/{id}`.
///
/// One struct covers both routes, because the update DTO
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ModelProviderUpdateRequest.kt:11-37`) is
/// the create DTO with every constraint dropped to "null means leave it alone". `JSONEncoder` drops a nil
/// optional, so a field the operator did not touch simply does not appear.
///
/// Two rules the service imposes and the form has to respect:
/// - a blank `apiKey` must be left off rather than sent — the update path re-uses the masked value as the
///   signal to keep the stored key (`ModelProviderServiceImpl.kt:108-112`), and echoing `sk-****abcd`
///   back would otherwise overwrite the credential with its own mask;
/// - a blank `baseUrl` must also be left off: the create pattern allows an empty string
///   (`ModelProviderCreateRequest.kt:30-34`) while the update pattern refuses it
///   (`ModelProviderUpdateRequest.kt:29-32`), so an address once set can only be replaced, never cleared.
public struct ModelProviderSaveRequest: Encodable, Equatable, Sendable {
    /// Only sent on create; the console locks the technical type once a provider exists.
    public var type: String?
    public var name: String
    /// Always sent: the service writes the description whenever the key is present
    /// (`ModelProviderServiceImpl.kt:104-107`), so omitting it would make "clear the description" impossible.
    public var description: String
    public var apiKey: String?
    public var baseUrl: String?
    public var isPublic: Int?

    public init(
        type: String?,
        name: String,
        description: String,
        apiKey: String?,
        baseUrl: String?,
        isPublic: Bool
    ) {
        self.type = hxPresented(type)
        self.name = name
        self.description = description
        self.apiKey = hxPresented(apiKey)
        self.baseUrl = hxPresented(baseUrl)
        self.isPublic = isPublic.hxInt
    }
}

/// Body of `POST /api/admin/models` and `PUT /api/admin/models/update/{id}`.
///
/// The five capability bits and `thinkingMode` are non-optional on purpose. The update path is a
/// partial update — `request.supportInternet?.let { … }` and friends
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ModelServiceImpl.kt:158-172`) —
/// so a switch flipped off must travel as an explicit `0`; leaving it out would strand the old value and
/// the capability would silently stay on.
///
/// `supportReasoning` is never a switch of its own: it is derived from `thinkingMode` here, exactly as the
/// service derives it when the key arrives (`ModelServiceImpl.kt:160-167`), so the row and the computed
/// `tags` cannot disagree.
public struct ModelSaveRequest: Encodable, Equatable, Sendable {
    public var name: String
    public var modelName: String
    public var providerId: Int64
    public var description: String
    public var modelType: String
    public var supportInternet: Int
    public var supportReasoning: Int
    public var thinkingMode: Int
    public var supportTool: Int
    public var supportMcp: Int
    public var supportVision: Int
    /// Token budget of one call. Optional and nil-dropped on purpose: an empty field means *no answer*, which
    /// a create reads as "infer it from the model name" (`ModelServiceImpl.kt:132`) and an update reads as
    /// "leave the stored window alone" (`:186`). A 0 in its place would be refused outright
    /// (`@field:Min(value = 1)` on both DTOs).
    public var contextWindow: Int?
    public var price: Double
    public var isPublic: Int

    public init(
        name: String,
        modelName: String,
        providerId: Int64,
        description: String,
        modelType: String,
        thinking: ThinkingMode,
        supportsInternet: Bool,
        supportsTool: Bool,
        supportsMcp: Bool,
        supportsVision: Bool,
        price: Double,
        contextWindow: Int?,
        isPublic: Bool
    ) {
        self.name = name
        self.modelName = modelName
        self.providerId = providerId
        self.description = description
        self.modelType = modelType
        // A non-chat model has no capability to carry, and the console zeroes the bits when the type moves
        // off `chat` (`harnax-webui/src/pages/model/components/ModelForm.tsx:67-78`).
        let isChat = modelType == ModelType.chat.rawValue
        self.thinkingMode = isChat ? thinking.rawValue : ThinkingMode.off.rawValue
        self.supportReasoning = isChat ? thinking.supportReasoning : 0
        self.supportInternet = isChat ? supportsInternet.hxInt : 0
        self.supportTool = isChat ? supportsTool.hxInt : 0
        self.supportMcp = isChat ? supportsMcp.hxInt : 0
        self.supportVision = isChat ? supportsVision.hxInt : 0
        self.price = price
        self.contextWindow = contextWindow
        self.isPublic = isPublic.hxInt
    }

    /// Whether this body's type carries capability bits at all.
    public var supportsCapabilities: Bool { modelType == ModelType.chat.rawValue }
}

/// The model types the console offers. The backend column is free text with no pattern
/// (`ModelCreateRequest.kt:28-30`), so this is exactly the web form's list
/// (`harnax-webui/src/pages/model/components/ModelForm.tsx:20-23`) — a row stored under any other value is
/// still readable, it just is not a name the picker can offer.
public enum ModelType: String, CaseIterable, Identifiable, Sendable {
    case chat
    case embedding

    public var id: String { rawValue }

    /// The web form defaults a new row to `chat`, and the capability switches only appear for it.
    public static let defaultValue: ModelType = .chat
}

/// The provider types the console offers, in its own order
/// (`harnax-webui/src/pages/model/components/ProviderForm.tsx:16-20`). These are wire values: the create
/// DTO pins `type` to `^[a-z0-9_]+$` (`ModelProviderCreateRequest.kt:11-14`), and both the icon and the
/// copy hang off the choice.
public enum ModelProviderType: String, CaseIterable, Identifiable, Sendable {
    case dashscope
    case openai
    case ollama

    public var id: String { rawValue }

    /// A stored type this list does not name. It has to render as itself rather than be rewritten to a
    /// known value on the next save.
    public static func resolve(_ raw: String?) -> ModelProviderType? {
        guard let raw = hxPresented(raw) else { return nil }
        return ModelProviderType(rawValue: raw)
    }
}
