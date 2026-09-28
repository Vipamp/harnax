import Foundation

/// One row of `GET /api/admin/channels/page`
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ChannelResponse.kt:12-72`).
///
/// Every key can be absent: the DTO declares all of them nullable and the stack ships
/// `default-property-inclusion: non_null` (`harnax-admin/src/main/resources/application.yml:22-25`). The row
/// is therefore read through optional accessors everywhere below rather than through a forced unwrap.
///
/// Three things this row answers that are easy to misread:
/// - `status` and `enabled` are different columns. `status` is the on/off switch the list toggles
///   (`ChannelController.kt:79-89` takes it as the toggle's only argument), `enabled` is "start listening
///   when the service boots" (`ChannelServiceImpl.kt:82`) and the form edits that one.
/// - `configJson` is a *string holding JSON*, and what arrives is the masked display form of each
///   credential (`ChannelServiceImpl.kt:221`, `:293`). It is not a nested object.
/// - `callbackKey` is deliberately not here — only the derived `callbackUrl`, and only for a webhook row
///   (`ChannelResponse.kt:36-41`, `ChannelServiceImpl.kt:231-236`).
public struct ChannelSummary: Decodable, Identifiable, Equatable, Sendable {
    public let id: Int64?
    public let tenantId: Int64?
    public let name: String?
    public let type: String?
    /// Server-side Chinese-free label (`ChannelResponse.getTypeDisplayName`). Unused here: this screen
    /// localises the type itself, and the two would drift.
    public let typeDisplayName: String?
    public let agentId: Int64?
    public let agentName: String?
    /// `chn-` + UUID, minted at create and never changed again (`ChannelServiceImpl.kt:329`).
    public let sessionId: String?
    public let callbackUrl: String?
    public let communicationMode: String?
    /// Present on the wire, absent from the whole web console — iOS sends nothing for it and the server
    /// defaults it to `DEFAULT` (`ChannelServiceImpl.kt:81`).
    public let permissionMode: String?
    public let enabled: Int?
    public let configJson: String?
    public let enableThink: Int?
    public let enableSearch: Int?
    public let enablePlan: Int?
    public let description: String?
    /// Every row the service creates carries `system` (`harnax-entity/.../Channel.kt:67`, never assigned in
    /// `createChannel`), so on this domain the creator rule below only ever lets an administrator through.
    public let creator: String?
    public let status: Int?
    public let createTime: String?
    public let updateTime: String?

    public var title: String? { hxPresented(name) }
    /// A type code this side has never seen (`ChannelType.fromCode` is case-sensitive server-side, and the
    /// page filter passes the string straight through) still renders, as the code itself.
    public var channelType: ChannelType? { ChannelType(code: type) }
    public var rawTypeCode: String? { hxPresented(type) }
    public var mode: ChannelMode? { ChannelMode(code: communicationMode) }
    public var config: ChannelConfig { ChannelConfig(json: configJson) }

    /// The list's switch. A missing column counts as running, because the insert defaults `status` to 1
    /// (`ChannelServiceImpl.kt:87`).
    public var isRunning: Bool { status != 0 }
    /// The form's switch: auto-listen at service startup, defaulted on by the same insert (`:82`).
    public var autoStarts: Bool { enabled != 0 }

    /// The three channel-level capability switches (O6). `enableThink` is the one with a server-side opinion:
    /// a create that omits it gets whatever the bound model's `thinkingMode` says, and a required-thinking
    /// model refuses an explicit off (`ChannelServiceImpl.kt:thinkingFlagOrRefuse`).
    public var thinkEnabled: Bool { enableThink.hxFlag }
    public var searchEnabled: Bool { enableSearch.hxFlag }
    public var planEnabled: Bool { enablePlan.hxFlag }

    /// A personal-WeChat row has no credentials until a scan has written `botToken` into the blob
    /// (`WechatLoginService.kt:162-186`), which is the difference between "go scan it" and "it is bound".
    public var isWechatBound: Bool {
        guard let raw = hxPresented(config.text(for: WechatLoginCredential.botTokenKey)) else { return false }
        // The read path masks credentials, so an unusable-looking value is treated as absent.
        return !raw.contains("*")
    }

    public func manageable(by account: AccountSnapshot?) -> Bool {
        account?.canManage(creator: creator) ?? false
    }
}

/// The five channel types the console offers (`harnax-webui/src/pages/channel/index.tsx:46-52`).
///
/// The raw values are the wire codes and `ChannelType.fromCode` matches them case-sensitively, so filters
/// and writes send exactly these lower-case strings. A code outside the set is not a validation failure —
/// the list keeps showing the row and only its menus lose the entry.
public enum ChannelType: String, CaseIterable, Sendable {
    case wecom
    case wechat
    case feishu
    case dingtalk
    case http

    public init?(code: String?) {
        guard let code = hxPresented(code)?.lowercased() else { return nil }
        self.init(rawValue: code)
    }

    public var titleKey: String { "channel.type.\(rawValue)" }

    /// Which modes this type can actually run, in the console's order
    /// (`harnax-webui/src/pages/channel/components/channelModes.ts:16-22`, mirrored by
    /// `ChannelServiceImpl.kt:360-366`). A mode outside this list produces a channel that starts, receives
    /// nothing and is never stopped — so this is a runtime constraint, not a preference.
    public var modes: [ChannelMode] {
        switch self {
        case .feishu: return [.websocket, .webhook]
        case .dingtalk: return [.stream]
        case .wecom: return [.websocket]
        case .wechat: return [.longPolling]
        case .http: return [.webhook]
        }
    }

    public var defaultMode: ChannelMode { modes.first ?? .websocket }

    /// Personal WeChat has one mode and the service force-corrects it anyway
    /// (`ChannelServiceImpl.kt:113-115`), so the console hides the selector rather than showing a control
    /// with one legal value (`CreateForm.tsx:254-267`, `UpdateForm.tsx:308-320`).
    public var hidesModePicker: Bool { self == .wechat }

    /// The credential fields this type/mode pair owns, with the label the platform's own console uses.
    ///
    /// `appId` means "Bot ID" on WeCom and "App Key" on DingTalk, which is why the labels hang off the pair
    /// rather than off `ChannelConfigKey`.
    public func fields(mode: ChannelMode?) -> [ChannelField] {
        switch self {
        case .wecom:
            return [.init(key: .appId, labelKey: "channel.field.wecom.botId", placeholderKey: "channel.field.wecom.botId.placeholder", isSecret: false),
                    .init(key: .appSecret, labelKey: "channel.field.wecom.botSecret", placeholderKey: "channel.field.wecom.botSecret.placeholder", isSecret: true)]
        case .dingtalk:
            return [.init(key: .appId, labelKey: "channel.field.dingtalk.appKey", placeholderKey: "channel.field.dingtalk.appKey.placeholder", isSecret: false),
                    .init(key: .appSecret, labelKey: "channel.field.dingtalk.appSecret", placeholderKey: "channel.field.dingtalk.appSecret.placeholder", isSecret: true)]
        case .feishu:
            var fields = [ChannelField(key: .appId, labelKey: "channel.field.feishu.appId", placeholderKey: "channel.field.feishu.appId.placeholder", isSecret: false),
                          .init(key: .appSecret, labelKey: "channel.field.feishu.appSecret", placeholderKey: "channel.field.feishu.appSecret.placeholder", isSecret: true)]
            if mode == .webhook {
                fields.append(ChannelField(key: .encodingAesKey, labelKey: "channel.field.encodingAesKey", placeholderKey: "channel.field.encodingAesKey.placeholder", isSecret: true))
                fields.append(.init(key: .token, labelKey: "channel.field.token", placeholderKey: "channel.field.token.placeholder", isSecret: true))
            }
            return fields
        case .http:
            return [.init(key: .webhookUrl, labelKey: "channel.field.webhookUrl", placeholderKey: "channel.field.webhookUrl.placeholder", isSecret: false)]
        case .wechat:
            return []
        }
    }

    /// Which of the type's fields the server genuinely needs before it will accept a create
    /// (`CreateForm.tsx:109-186`, `channelModes.ts:55-57`). Everything is optional when editing an existing
    /// row, because a stored value already covers it (`UpdateForm.tsx:46-70`).
    public func isRequiredOnCreate(_ field: ChannelField, mode: ChannelMode?) -> Bool {
        switch self {
        case .wecom, .dingtalk, .feishu:
            // Feishu's callback pair is the one mode-conditional requirement: without the Encrypt Key the
            // platform refuses to sign, and the channel rejects callbacks with no visible reason.
            if field.key == .encodingAesKey || field.key == .token { return mode == .webhook }
            return true
        case .http, .wechat:
            return false
        }
    }

    /// The single-credential case that makes a type's form empty: personal WeChat binds by QR scan.
    public var showsScanHint: Bool { self == .wechat }
}

/// The four connection modes (`channelModes.ts`, `ChannelServiceImpl.kt:355-366`).
public enum ChannelMode: String, CaseIterable, Sendable {
    case websocket
    case stream
    case longPolling = "long_polling"
    case webhook

    public init?(code: String?) {
        guard let code = hxPresented(code) else { return nil }
        self.init(rawValue: code)
    }

    public var titleKey: String { "channel.mode.\(rawValue)" }

    /// Only a webhook row receives a platform push, so only one gets a callback URL.
    public var supportsCallback: Bool { self == .webhook }
}

/// One credential field of the conditional matrix: a `configJson` key plus how this screen words it.
public struct ChannelField: Equatable, Sendable {
    public let key: ChannelConfigKey
    public let labelKey: String
    /// Carried next to the label rather than built as `labelKey + ".placeholder"`: the catalogue gate only
    /// sees literal keys, and a concatenated one would miss its entry at runtime instead of at build time.
    public let placeholderKey: String
    /// Rendered as a password field. This is display only — the value is already masked by the server on
    /// the way in.
    public let isSecret: Bool

    public init(key: ChannelConfigKey, labelKey: String, placeholderKey: String, isSecret: Bool) {
        self.key = key
        self.labelKey = labelKey
        self.placeholderKey = placeholderKey
        self.isSecret = isSecret
    }
}

/// A `configJson` key this screen has a field for.
///
/// `botToken` is in the server's secret set but not in the console's managed list
/// (`ChannelServiceImpl.kt:386` against `UpdateForm.tsx:12`): it belongs to the WeChat scan, not to a form
/// field, so iOS reads it and never writes it.
public enum ChannelConfigKey: String, CaseIterable, Sendable {
    case appId
    case appSecret
    case token
    case encodingAesKey
    case webhookUrl
}

/// The `configJson` blob as an ordered set of string-keyed values.
///
/// Two rules make this a type rather than a `[String: String]`:
/// - Unknown keys survive. The scan path writes `botToken`, `userId`, `botId` and `baseUrl` into the same
///   blob (`WechatLoginService.kt:162-186`) and the console's own merge-over-stored exists precisely because
///   rewriting the whole blob erased them (`UpdateForm.tsx:110-121`). A dictionary of strings would drop them
///   on the first save.
/// - Non-string values survive too. The server parses the blob as `Map<String, Any?>`
///   (`ChannelServiceImpl.kt:344-349`), so a number or a nested object is legal, and it is kept here as the
///   exact JSON text it arrived as.
public struct ChannelConfig: Equatable, Sendable {
    /// One value: either a JSON string, or any other JSON value kept as raw text.
    public enum Value: Equatable, Sendable {
        case text(String)
        case raw(String)
    }

    /// The whole blob exceeds `ChannelServiceImpl.MAX_CONFIG_JSON_CHARS` = 20 000 characters.
    public static let maximumTextCount = 20_000

    private var values: [String: Value]

    public init() {
        values = [:]
    }

    /// A blank or unreadable blob is an empty config: the read path can hand back `null`, and a create has
    /// nothing stored yet.
    public init(json: String?) {
        guard let json = hxPresented(json), let data = json.data(using: .utf8) else {
            values = [:]
            return
        }
        guard let object = try? JSONSerialization.jsonObject(with: data),
              let dictionary = object as? [String: Any]
        else {
            // An array or a bare scalar is rejected by the server on the way in
            // (`ChannelServiceImpl.kt:285-287`), so treating it as empty loses nothing real.
            values = [:]
            return
        }
        values = dictionary.reduce(into: [:]) { partial, pair in
            if let string = pair.value as? String {
                partial[pair.key] = .text(string)
            } else if let raw = ChannelConfig.rawText(of: pair.value) {
                partial[pair.key] = .raw(raw)
            }
        }
    }

    public var keys: [String] { values.keys.sorted() }

    public var isEmpty: Bool { values.isEmpty }

    /// The value as this screen's field shows it. A non-string value reads as its JSON text, so the field is
    /// never blank for a key that is actually populated.
    public func text(for key: String) -> String? {
        guard let value = values[key] else { return nil }
        switch value {
        case let .text(string): return string
        case let .raw(raw): return raw
        }
    }

    public func text(for key: ChannelConfigKey) -> String? { text(for: key.rawValue) }

    /// Setting a blank or nil value removes the key, which is how the console reads "cleared the field"
    /// (`UpdateForm.tsx:114-118`).
    public mutating func setValue(_ text: String?, for key: ChannelConfigKey) {
        guard let text = hxPresented(text) else {
            values.removeValue(forKey: key.rawValue)
            return
        }
        values[key.rawValue] = .text(text)
    }

    /// The blob to send, or `nil` when there is nothing to send — which is what a create expects
    /// (`CreateForm.tsx:96`). An edit wants `"{}"` instead of a missing key so that clearing every credential
    /// actually clears them (`UpdateForm.tsx:138-141`); the caller picks.
    public func json(forWrite: Bool) -> String? {
        if values.isEmpty { return forWrite ? "{}" : nil }
        let body = values.sorted { $0.key < $1.key }.map { key, value -> String in
            let token = (try? JSONEncoder().encode(key)).flatMap { String(data: $0, encoding: .utf8) } ?? "\"\(key)\""
            return "\(token): \(jsonText(of: value))"
        }
        let json = "{" + body.joined(separator: ",") + "}"
        return json.count > Self.maximumTextCount ? nil : json
    }

    private func jsonText(of value: Value) -> String {
        switch value {
        case let .text(string):
            return (try? JSONEncoder().encode(string)).flatMap { String(data: $0, encoding: .utf8) } ?? "\"\""
        case let .raw(raw):
            return raw
        }
    }

    /// Re-serialise one decoded JSON value back to text so it can be put back unchanged. Fragments are
    /// allowed because a top-level number, flag or `null` is a value this can be handed.
    private static func rawText(of value: Any) -> String? {
        if value is NSNull { return "null" }
        guard JSONSerialization.isValidJSONObject([ "__v": value ]) else { return nil }
        guard let data = try? JSONSerialization.data(withJSONObject: [ "__v": value ], options: [.fragmentsAllowed]),
              let text = String(data: data, encoding: .utf8)
        else { return nil }
        // `{"__v":X}` -> `X`. `String.index(endIndex, offsetBy: -1, limitedBy: endIndex)` returns nil — a
        // negative walk needs its limit behind it — so the wrapper is checked and trimmed instead.
        guard text.hasPrefix(#"{"__v":"#), text.hasSuffix("}") else { return nil }
        return String(text.dropFirst(7).dropLast())
    }
}

/// The create body (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ChannelCreateRequest.kt`).
///
/// `name`, `type` and `agentId` are the three `@NotBlank`/`@NotNull` fields; everything else has a server-side
/// default. `enableThink` is the one field where "not sent" carries meaning: the bound model decides
/// (`ChannelServiceImpl.kt:83`).
public struct ChannelDraft: Encodable, Equatable, Sendable {
    public let name: String
    public let type: String
    public let agentId: Int64
    public let communicationMode: String?
    public let enabled: Int?
    public let configJson: String?
    public let enableThink: Int?
    public let enableSearch: Int?
    public let enablePlan: Int?
    public let description: String?

    public init(
        name: String,
        type: String,
        agentId: Int64,
        communicationMode: String? = nil,
        enabled: Int? = nil,
        configJson: String? = nil,
        enableThink: Int? = nil,
        enableSearch: Int? = nil,
        enablePlan: Int? = nil,
        description: String? = nil
    ) {
        self.name = name
        self.type = type
        self.agentId = agentId
        self.communicationMode = communicationMode
        self.enabled = enabled
        self.configJson = configJson
        self.enableThink = enableThink
        self.enableSearch = enableSearch
        self.enablePlan = enablePlan
        self.description = description
    }

    public func encode(to encoder: any Encoder) throws {
        var box = encoder.container(keyedBy: Key.self)
        try box.encode(name, forKey: .name)
        try box.encode(type, forKey: .type)
        try box.encode(agentId, forKey: .agentId)
        try box.encodeIfPresent(communicationMode, forKey: .communicationMode)
        try box.encodeIfPresent(enabled, forKey: .enabled)
        try box.encodeIfPresent(configJson, forKey: .configJson)
        try box.encodeIfPresent(enableThink, forKey: .enableThink)
        try box.encodeIfPresent(enableSearch, forKey: .enableSearch)
        try box.encodeIfPresent(enablePlan, forKey: .enablePlan)
        try box.encodeIfPresent(description, forKey: .description)
    }

    private enum Key: String, CodingKey {
        case name, type, agentId, communicationMode, enabled, configJson
        case enableThink, enableSearch, enablePlan, description
    }
}

/// The edit body (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ChannelUpdateRequest.kt`).
///
/// Every field is optional and a `nil` means "unchanged" (`ChannelServiceImpl.kt:102-131`). The one
/// asymmetry worth naming: `communicationMode` is only re-validated when either it or `type` is present, so
/// a type change alone still re-resolves the mode (`:107-111`).
public struct ChannelChange: Encodable, Equatable, Sendable {
    public let name: String?
    public let type: String?
    public let agentId: Int64?
    public let communicationMode: String?
    public let enabled: Int?
    public let configJson: String?
    public let enableThink: Int?
    public let enableSearch: Int?
    public let enablePlan: Int?
    public let description: String?

    public init(
        name: String? = nil,
        type: String? = nil,
        agentId: Int64? = nil,
        communicationMode: String? = nil,
        enabled: Int? = nil,
        configJson: String? = nil,
        enableThink: Int? = nil,
        enableSearch: Int? = nil,
        enablePlan: Int? = nil,
        description: String? = nil
    ) {
        self.name = name
        self.type = type
        self.agentId = agentId
        self.communicationMode = communicationMode
        self.enabled = enabled
        self.configJson = configJson
        self.enableThink = enableThink
        self.enableSearch = enableSearch
        self.enablePlan = enablePlan
        self.description = description
    }

    public func encode(to encoder: any Encoder) throws {
        var box = encoder.container(keyedBy: Key.self)
        try box.encodeIfPresent(name, forKey: .name)
        try box.encodeIfPresent(type, forKey: .type)
        try box.encodeIfPresent(agentId, forKey: .agentId)
        try box.encodeIfPresent(communicationMode, forKey: .communicationMode)
        try box.encodeIfPresent(enabled, forKey: .enabled)
        try box.encodeIfPresent(configJson, forKey: .configJson)
        try box.encodeIfPresent(enableThink, forKey: .enableThink)
        try box.encodeIfPresent(enableSearch, forKey: .enableSearch)
        try box.encodeIfPresent(enablePlan, forKey: .enablePlan)
        try box.encodeIfPresent(description, forKey: .description)
    }

    private enum Key: String, CodingKey {
        case name, type, agentId, communicationMode, enabled, configJson
        case enableThink, enableSearch, enablePlan, description
    }
}
