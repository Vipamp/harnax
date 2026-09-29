import Foundation
import Combine
import HarnaxCore
import HarnaxKit

// The two editable list rows of the form.
//
// `McpConfigEntry` and `EnvParamEntry` are the wire shapes and stay immutable; these carry what the
// operator has typed so far, including half-finished rows. A masked secret keeps the mask text in
// `value`/`defaultValue` on purpose: the backend carries the stored ciphertext forward only when the
// submitted secret still reads as a mask, keyed by the entry's name
// (`SecretFieldEncryptor.kt:39-53`, `:182-195`), so echoing the mask is what "I did not change this
// credential" means on the wire. Blank is the opposite: it clears the stored value.

public struct McpHeaderDraft: Equatable, Sendable {
    public var key: String
    public var value: String
    public var secret: Bool

    public init(key: String = "", value: String = "", secret: Bool = false) {
        self.key = key
        self.value = value
        self.secret = secret
    }

    init(_ entry: McpConfigEntry) {
        self.init(key: entry.key, value: entry.value, secret: entry.secret)
    }

    /// Derived, not stored: four asterisks is the same test the server applies
    /// (`SecretFieldEncryptor.kt:62`).
    public var isMasked: Bool { secret && value.contains("****") }

    var entry: McpConfigEntry {
        McpConfigEntry(key: hxPresented(key) ?? "", value: value, secret: secret)
    }
}

public struct McpEnvParamDraft: Equatable, Sendable {
    public var id: Int64?
    public var name: String
    public var detail: String
    public var required: Bool
    public var secret: Bool
    public var defaultValue: String

    public init(
        id: Int64? = nil,
        name: String = "",
        detail: String = "",
        required: Bool = false,
        secret: Bool = false,
        defaultValue: String = ""
    ) {
        self.id = id
        self.name = name
        self.detail = detail
        self.required = required
        self.secret = secret
        self.defaultValue = defaultValue
    }

    init(_ entry: EnvParamEntry) {
        self.init(
            id: entry.id,
            name: entry.envParamName,
            detail: entry.description ?? "",
            required: entry.required,
            secret: entry.secret,
            defaultValue: entry.defaultValue ?? ""
        )
    }

    public var isMasked: Bool { secret && defaultValue.contains("****") }

    var entry: EnvParamEntry {
        EnvParamEntry(
            id: id,
            envParamName: hxPresented(name) ?? "",
            description: hxPresented(detail),
            required: required,
            secret: secret,
            defaultValue: defaultValue.isEmpty ? nil : defaultValue
        )
    }
}

/// Which control an issue belongs to, so the sheet can put the words next to the field.
public enum McpFormField: Equatable, Sendable {
    case name
    case url
    case command
    case issuer
    case headers
    case envParams
}

public struct McpFormIssue: Equatable, Sendable {
    public let field: McpFormField
    public let message: String
}

/// The answer `save()` gave.
public enum McpFormResult: Equatable, Sendable {
    case saved
    /// Nothing went out; the sheet shows the issues and stays open.
    case invalid([McpFormIssue])
    /// An OAUTH2 row whose URL moved: every user grant issued for the old address dies with it, so the
    /// sheet asks once and calls again with the acknowledgement
    /// (`McpServerServiceImpl.kt:213-216`).
    case needsURLConfirmation
    case failed(message: String)
}

/// C1 — the create/edit form for one MCP server.
///
/// The rules that make this more than a struct of text fields:
/// - stdio is not offered on create and only stays selectable on a row that already is stdio, because the
///   runtime refuses anything else (`McpStdioPolicy.kt:20-31`);
/// - the update body is "omitted means unchanged" (`McpServerServiceImpl.kt:174-252`), so an edit sends
///   only the fields the operator actually touched — sending the whole form would look harmless and would
///   still be the only way to erase something;
/// - an entry list is the exception: `nil` leaves the column alone and `[]` clears it, so the form tracks
///   the two lists as touched/untouched rather than inferring it from emptiness.
@MainActor
public final class McpFormViewModel: ObservableObject {
    public enum Mode: Equatable {
        case create
        case edit(McpServerRow)
    }

    @Published public var name: String
    @Published public var detail: String
    @Published public var transport: McpTransport {
        didSet { if transport != oldValue { followTransportChange(from: oldValue) } }
    }
    @Published public var command: String
    @Published public var endpointURL: String
    @Published public var headers: [McpHeaderDraft]
    @Published public var envParams: [McpEnvParamDraft]
    @Published public var auth: McpAuthKind {
        didSet { if auth != oldValue { followAuthChange(from: oldValue) } }
    }
    @Published public var issuer: String
    /// One line, comma or space separated; the wire shape is a `[String]`
    /// (`McpOAuthConfig.kt:13-30`).
    @Published public var scopes: String
    @Published public var audience: String
    @Published public var usesResourceIndicator: Bool
    @Published public var isPublic: Bool
    @Published public var enabled: Bool
    /// The last probe's answer, shown inside the sheet. A form test is the same endpoint as the list's and
    /// can only answer for a row that already exists.
    @Published public private(set) var testOutcome: McpTestOutcome?
    @Published public private(set) var isTesting = false
    @Published public private(set) var isSaving = false

    public let mode: Mode
    /// Whether this account may move the public/private switch at all — the same rule the two model forms
    /// apply (`permissionUtil.ts:29-75`).
    public let canChangeVisibility: Bool

    private let mcp: any McpCataloging
    private let original: McpServerRow?
    /// The two lists answer "leave the column alone" with an absent key, which is not something an empty
    /// text field can tell apart from a cleared one — so the edits are tracked here.
    private var headersTouched = false
    private var envParamsTouched = false

    public init(mcp: any McpCataloging, mode: Mode, account: AccountSnapshot? = nil) {
        self.mcp = mcp
        self.mode = mode
        switch mode {
        case .create:
            self.original = nil
            name = ""
            detail = ""
            transport = .streamablehttp
            command = ""
            endpointURL = ""
            headers = []
            envParams = []
            auth = .none
            issuer = ""
            scopes = ""
            audience = ""
            usesResourceIndicator = true
            // `isPublic` omitted is 1 server-side (`McpServerServiceImpl.kt:136`), which is not the default
            // an operator expects from a switch that starts off; the console sends an explicit 0 too.
            isPublic = false
            enabled = true
        case let .edit(row):
            self.original = row
            name = row.name ?? ""
            detail = row.description ?? ""
            transport = row.transport
            command = row.command ?? ""
            endpointURL = row.url ?? ""
            headers = row.headerEntries.map(McpHeaderDraft.init)
            envParams = row.envEntries.map(McpEnvParamDraft.init)
            auth = row.auth
            let config = row.oauthConfig ?? .defaults
            issuer = config.authorizationServer ?? ""
            scopes = config.scopes.joined(separator: ", ")
            audience = config.audience ?? ""
            usesResourceIndicator = config.resourceIndicator
            isPublic = row.isShared
            enabled = row.isEnabled
        }
        canChangeVisibility = account?.canChangeVisibility(
            creator: original?.creator,
            currentlyPublic: original?.isShared ?? false,
            isCreate: original == nil
        ) ?? true
    }

    /// The transports this sheet may offer.
    ///
    /// Create never lists stdio. Edit keeps exactly one stdio entry when the stored row is stdio, so
    /// changing the name does not silently move the transport out from under it
    /// (`harnax-webui/src/pages/mcp/components/UpdateForm.tsx:79-85`).
    public var availableTransports: [McpTransport] {
        guard case let .edit(row) = mode, row.transport.isStdio else { return McpTransport.creatable }
        return [.stdio] + McpTransport.creatable
    }

    /// stdio + OAUTH2 is refused by the backend (`McpServerServiceImpl.kt:407-414`), so the option that
    /// cannot be stored is not offered either.
    public var availableAuthKinds: [McpAuthKind] {
        transport.isStdio ? McpAuthKind.selectable.filter { $0 != .oauth2 } : McpAuthKind.selectable
    }

    /// A legacy BASIC row still has to render its own value, or the sheet would look like it agreed with
    /// the server that the row says NONE.
    public var shownAuthKinds: [McpAuthKind] {
        availableAuthKinds.contains(auth) ? availableAuthKinds : [auth] + availableAuthKinds
    }

    /// Environment parameters are the stdio process's environment: both writes drop the column for a
    /// network transport (`McpServerServiceImpl.kt:147-151`, `:244-251`), so the section only appears where
    /// the value would survive.
    public var showsEnvParams: Bool { transport.usesCommand }
    public var showsEndpoint: Bool { !transport.usesCommand }
    public var showsHeaders: Bool { !transport.usesCommand }
    public var showsOAuthFields: Bool { auth == .oauth2 }

    /// The issuer already on the row. Leaving the field empty does not clear it — the server carries the
    /// stored one forward when the submitted block has a blank issuer
    /// (`McpServerServiceImpl.kt:221-233`) — so the sheet says whose value will survive.
    public var originalIssuer: String? { hxPresented(original?.oauthConfig?.authorizationServer) }

    /// Whether the URL about to be sent differs from the stored one on an OAUTH2 row.
    public var movesAuthorizedResource: Bool {
        guard let original, original.auth == .oauth2, !transport.usesCommand else { return false }
        return (hxPresented(endpointURL) ?? "") != (hxPresented(original.url) ?? "")
    }

    /// Nothing may be tested before the row exists: the endpoint takes an id
    /// (`McpServerController.kt:136-146`), and the console answers the same case with "save first"
    /// (`harnax-webui/src/pages/mcp/components/CreateForm.tsx:46-60`).
    public var canRunTest: Bool { original?.id != nil }

    public func validate() -> [McpFormIssue] {
        var issues: [McpFormIssue] = []
        let trimmedName = hxPresented(name) ?? ""
        if trimmedName.isEmpty {
            issues.append(McpFormIssue(field: .name, message: hx("mcp.form.requiredName")))
        } else if trimmedName.count > 100 {
            issues.append(McpFormIssue(field: .name, message: hx("mcp.form.nameTooLong", 100)))
        }
        if transport.usesCommand {
            if hxPresented(command) == nil {
                issues.append(McpFormIssue(field: .command, message: hx("mcp.form.requiredCommand")))
            }
        } else {
            let url = hxPresented(endpointURL) ?? ""
            if url.isEmpty {
                issues.append(McpFormIssue(field: .url, message: hx("mcp.form.requiredURL")))
            } else if !McpValidation.isHTTPURL(url) {
                issues.append(McpFormIssue(field: .url, message: hx("mcp.form.badURL")))
            }
        }
        if showsOAuthFields, let typed = hxPresented(issuer), !McpValidation.isHTTPURL(typed) {
            issues.append(McpFormIssue(field: .issuer, message: hx("mcp.form.badIssuer")))
        }
        if let duplicate = firstDuplicate(headers.map { hxPresented($0.key) ?? "" }) {
            issues.append(McpFormIssue(field: .headers, message: hx("mcp.form.duplicateKey", duplicate)))
        }
        if let duplicate = firstDuplicate(envParams.map { hxPresented($0.name) ?? "" }) {
            issues.append(McpFormIssue(field: .envParams, message: hx("mcp.form.duplicateParam", duplicate)))
        }
        // `required` with no default is a parameter the runtime has to be handed from somewhere else, and
        // the console refuses the same combination (`CreateForm.tsx:83-95`).
        if let missing = envParams.first(where: { $0.required && hxPresented($0.defaultValue) == nil }) {
            issues.append(McpFormIssue(
                field: .envParams,
                message: hx("mcp.form.envNeedsDefault", hxPresented(missing.name) ?? "")
            ))
        }
        return issues
    }

    public func addHeader() {
        headersTouched = true
        headers.append(McpHeaderDraft())
    }

    public func removeHeader(at index: Int) {
        guard headers.indices.contains(index) else { return }
        headersTouched = true
        headers.remove(at: index)
    }

    public func addEnvParam() {
        envParamsTouched = true
        envParams.append(McpEnvParamDraft())
    }

    public func removeEnvParam(at index: Int) {
        guard envParams.indices.contains(index) else { return }
        envParamsTouched = true
        envParams.remove(at: index)
    }

    /// The create body: every field the form owns, because there is nothing to leave unchanged.
    public func buildDraft() -> McpServerDraft {
        McpServerDraft(
            name: hxPresented(name) ?? "",
            description: hxPresented(detail),
            type: transport.wireValue ?? McpTransport.streamablehttp.wireValue!,
            command: transport.usesCommand ? hxPresented(command) : nil,
            url: showsEndpoint ? hxPresented(endpointURL) : nil,
            authType: auth.wireValue,
            oauthConfig: oauthConfigBlock,
            status: enabled ? 1 : 0,
            isPublic: isPublic ? 1 : 0,
            headers: headerEntries.nilWhenEmpty,
            envParams: showsEnvParams ? envParamEntries.nilWhenEmpty : nil
        )
    }

    /// The update body: only what the operator touched, so an untouched column keeps its value
    /// (`McpServerServiceImpl.kt:174-252`).
    public func buildPatch() -> McpServerPatch {
        var patch = McpServerPatch()
        guard let original else { return patch }
        let newName = hxPresented(name) ?? ""
        if newName != (hxPresented(original.name) ?? "") { patch.name = newName }
        // An erased field goes out as the empty string the service clears the column with
        // (`McpServerServiceImpl.kt:187,200,205,212,216`); `nil` here would drop the key, and an absent key
        // is that route's "keep what is stored" — the deleted text would be back on the next read.
        let newDetail = hxPresented(detail)
        if newDetail != hxPresented(original.description) { patch.description = newDetail ?? "" }
        if transport != original.transport { patch.type = transport.wireValue }
        if transport.usesCommand {
            let newCommand = hxPresented(command)
            if newCommand != hxPresented(original.command) { patch.command = newCommand ?? "" }
        } else {
            let newURL = hxPresented(endpointURL)
            if newURL != hxPresented(original.url) { patch.url = newURL ?? "" }
        }
        if auth != original.auth { patch.authType = auth.wireValue }
        if showsOAuthFields, oauthConfigBlock != (original.oauthConfig ?? .defaults) {
            patch.oauthConfig = oauthConfigBlock
        }
        // An absent key leaves the column, an empty array erases it (`:241-252`). A list the form does not
        // show is therefore never sent from a diff alone: an sse row that still carries legacy env params
        // keeps them, exactly as it does when the console edits it.
        let headerDiffers = showsHeaders && headerEntries != original.headerEntries.map { McpHeaderDraft($0).entry }
        if headersTouched || headerDiffers {
            patch.headers = headerEntries
        }
        let envDiffers = showsEnvParams && envParamEntries != original.envEntries.map { McpEnvParamDraft($0).entry }
        if envParamsTouched || envDiffers {
            patch.envParams = envParamEntries
        }
        if enabled != original.isEnabled { patch.status = enabled ? 1 : 0 }
        if isPublic != original.isShared { patch.isPublic = isPublic ? 1 : 0 }
        return patch
    }

    /// Runs the same probe the list card runs, against the stored row.
    ///
    /// An edit that has not been saved is not testable — the endpoint takes an id, not a body — which is
    /// why the console's create form refuses the button outright.
    public func runTest() async {
        guard let id = original?.id else {
            testOutcome = .refused(reason: hx("mcp.form.testAfterSave"))
            return
        }
        isTesting = true
        testOutcome = nil
        defer { isTesting = false }
        let catalog = mcp
        testOutcome = await McpConnectivity.probe(timeout: 15) {
            await catalog.testMcpConnectivity(id: id)
        }
    }

    public func save(acknowledgingURLChange: Bool = false) async -> McpFormResult {
        guard !isSaving else { return .invalid([]) }
        let issues = validate()
        if !issues.isEmpty { return .invalid(issues) }
        if acknowledgingURLChange == false && movesAuthorizedResource { return .needsURLConfirmation }
        isSaving = true
        defer { isSaving = false }
        switch mode {
        case .create:
            if case let .failure(error) = await mcp.createMCPServer(buildDraft()) {
                return .failed(message: ErrorMessage.text(for: error))
            }
        case let .edit(row):
            guard let id = row.id else {
                return .failed(message: hx("mcp.form.noId"))
            }
            if case let .failure(error) = await mcp.updateMCPServer(id: id, patch: buildPatch()) {
                return .failed(message: ErrorMessage.text(for: error))
            }
        }
        return .saved
    }

    /// Switching transports clears the other family's fields rather than hiding them with their values in
    /// place: the write nulls them anyway (`McpServerServiceImpl.kt:196-208`), and a form that kept them
    /// would show a URL the stored row no longer has.
    private func followTransportChange(from oldValue: McpTransport) {
        guard oldValue != transport else { return }
        testOutcome = nil
        if transport.isStdio {
            endpointURL = ""
            setHeaders([], touched: true)
            if auth == .oauth2 { auth = .none }
        } else {
            command = ""
            setEnvParams([], touched: true)
        }
    }

    private func followAuthChange(from oldValue: McpAuthKind) {
        guard auth != oldValue else { return }
        if auth != .oauth2 {
            issuer = ""
            scopes = ""
            audience = ""
            usesResourceIndicator = true
        }
    }

    private func setHeaders(_ value: [McpHeaderDraft], touched: Bool) {
        headersTouched = touched
        headers = value
    }

    private func setEnvParams(_ value: [McpEnvParamDraft], touched: Bool) {
        envParamsTouched = touched
        envParams = value
    }

    private var headerEntries: [McpConfigEntry] {
        headers.filter { hxPresented($0.key) != nil }.map(\.entry)
    }

    private var envParamEntries: [EnvParamEntry] {
        envParams.filter { hxPresented($0.name) != nil }.map(\.entry)
    }

    /// Left out unless the row authorizes per user: the write refuses a config on any other auth type
    /// (`McpServerServiceImpl.kt:415-421`), and leaving OAUTH2 clears the column server-side (`:234-238`).
    private var oauthConfigBlock: McpOAuthConfigValues? {
        guard showsOAuthFields else { return nil }
        return McpOAuthConfigValues(
            authorizationServer: hxPresented(issuer),
            scopes: McpValidation.splitScopes(scopes),
            audience: hxPresented(audience),
            resourceIndicator: usesResourceIndicator
        )
    }

    private func firstDuplicate(_ values: [String]) -> String? {
        var seen = Set<String>()
        for value in values where !value.isEmpty {
            if !seen.insert(value).inserted { return value }
        }
        return nil
    }
}

/// The URL and scope-list shapes the form checks before it sends.
///
/// The URL test mirrors the server's own (`McpServerServiceImpl.kt:425-433`: an http(s) URL with a host),
/// which is stricter than the console's regex in exactly one direction — a userinfo or query in the
/// issuer is rejected here because the authorization request built from it would be wrong.
enum McpValidation {
    static func isHTTPURL(_ raw: String) -> Bool {
        guard let text = hxPresented(raw), let url = URL(string: text),
              let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https"
        else { return false }
        guard let host = url.host, !host.isEmpty else { return false }
        return url.user == nil && url.password == nil
    }

    /// Comma or whitespace separated, deduplicated in order: the console's tag input accepts both
    /// separators (`OAuthFields.tsx:41-47`) and an empty scope is not a scope.
    static func splitScopes(_ raw: String) -> [String] {
        var seen = Set<String>()
        return raw
            .split(whereSeparator: { $0 == "," || $0 == " " || $0.isNewline })
            .map { String($0).trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
            .filter { seen.insert($0).inserted }
    }
}

private extension Array {
    var nilWhenEmpty: [Element]? { isEmpty ? nil : self }
}
