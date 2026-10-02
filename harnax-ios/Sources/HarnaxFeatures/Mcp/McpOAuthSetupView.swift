import SwiftUI
import HarnaxCore
import HarnaxKit

/// The setup block of the OAuth panel: what one discovery run found, and the entry to register the client it
/// does not create.
///
/// Kept apart from the grant block above it because the two answer different questions and one can fail while
/// the other is fine — this one is about the authorization server and the tenant's registration, and it writes
/// (`McpOAuthServiceImpl.kt:62-76`, `:95-120`), so it is never run on load and only ever on the operator's
/// word. The console gives the same panel both roles and the same two buttons
/// (`harnax-webui/src/pages/mcp/components/OAuthPanel.tsx:318-517`).
struct McpOAuthSetupSection: View {
    @ObservedObject var vm: McpDetailViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HXSectionHeader("mcp.oauth.setup.section")
            HXGroupCard {
                if vm.setupPhase == .running {
                    HXRow(text: hx("mcp.oauth.setup.running")) {
                        ProgressView().tint(Color.hx(.brand))
                    }
                }
                if let discovery = vm.discovery {
                    if !discovery.unknownScopes.isEmpty {
                        HXBanner(
                            "mcp.oauth.setup.unknownScopes",
                            message: discovery.unknownScopes.joined(separator: ", "),
                            systemImage: "exclamationmark.triangle",
                            tone: .warning
                        )
                        .padding(.bottom, 4)
                    }
                    if discovery.isCallbackStale, let current = hxPresented(discovery.defaultCallbackUrl) {
                        HXBanner(
                            "mcp.oauth.setup.callbackStale",
                            message: current,
                            systemImage: "arrow.triangle.2.circlepath",
                            tone: .warning
                        )
                        .padding(.bottom, 4)
                    }
                    rows(discovery)
                } else if case let .failed(message) = vm.setupPhase {
                    HXRow(text: message, divider: false) { EmptyView() }
                        .foregroundStyle(Color.hx(.danger))
                } else {
                    HXRow(text: hx("mcp.oauth.setup.notDiscovered"), divider: false) { EmptyView() }
                }
                actions
            }
        }
    }

    /// The endpoints as facts, each with the console's own fallback sentence when the authorization server did
    /// not advertise it — "not advertised" and "absent" are the same news here, and both of them change what
    /// a revoke can do (`McpOAuthUserServiceImpl.kt:244-257`).
    @ViewBuilder
    private func rows(_ discovery: McpOAuthDiscovery) -> some View {
        HXRow(
            text: hx("mcp.oauth.setup.issuer"),
            subtitle: discovery.knownIssuer ?? hx("mcp.oauth.setup.none")
        ) {
            if let source = McpOAuthPresentation.issuerSource(discovery.issuerSource) {
                HXChip(source, tone: .indigo)
            }
        }
        endpointRow("mcp.oauth.setup.authorizationEndpoint", discovery.authorizationEndpoint)
        endpointRow("mcp.oauth.setup.tokenEndpoint", discovery.tokenEndpoint)
        endpointRow(
            "mcp.oauth.setup.registrationEndpoint",
            discovery.registrationEndpoint,
            empty: hx("mcp.oauth.setup.registrationNone")
        )
        endpointRow(
            "mcp.oauth.setup.revocationEndpoint",
            discovery.revocationEndpoint,
            empty: hx("mcp.oauth.setup.revocationNone")
        )
        if !discovery.scopesSupported.isEmpty {
            HXRow(text: hx("mcp.oauth.setup.scopesSupported")) {
                HXFlow(spacing: 5) {
                    ForEach(discovery.scopesSupported, id: \.self) { scope in
                        HXChip(scope, tone: .teal)
                    }
                }
                .frame(maxWidth: 150)
            }
        }
        HXRow(
            text: hx("mcp.oauth.setup.clientId"),
            subtitle: discovery.registeredClientID ?? hx("mcp.oauth.setup.clientNone")
        ) { EmptyView() }
        HXRow(text: hx("mcp.oauth.setup.clientSecret")) {
            HXBadge(
                discovery.clientSecretPresent ? "mcp.oauth.setup.secretStored" : "mcp.oauth.setup.secretAbsent",
                tone: discovery.clientSecretPresent ? .success : .textTertiary
            )
        }
        endpointRow("mcp.oauth.setup.callbackUrl", discovery.callbackUrl, empty: hx("mcp.oauth.setup.none"))
    }

    private func endpointRow(_ key: String, _ value: String?, empty: String? = nil) -> some View {
        HXRow(text: hx(key), subtitle: value ?? empty ?? hx("mcp.oauth.setup.none")) { EmptyView() }
    }

    @ViewBuilder
    private var actions: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                Button {
                    Task { await vm.discover() }
                } label: {
                    HXText(vm.setupPhase == .running ? "mcp.oauth.setup.discovering" : "mcp.oauth.setup.discover")
                }
                .buttonStyle(.hxSecondary)
                .disabled(!vm.canDiscover)
                if case .failed = vm.setupPhase {
                    Button {
                        Task { await vm.discover() }
                    } label: {
                        HXText("common.retry")
                    }
                    .buttonStyle(.hxInline)
                    .disabled(!vm.canDiscover)
                }
            }
            Button {
                vm.openClientEditor()
            } label: {
                HXText("mcp.oauth.setup.register")
            }
            .buttonStyle(.hxInline)
            .disabled(!vm.canRegisterClient)
            if !vm.canRegisterClient {
                Text(verbatim: hx("mcp.oauth.setup.registerDisabled"))
                    .font(.caption)
                    .foregroundStyle(Color.hx(.textTertiary))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.vertical, 6)
        .padding(.horizontal, 13)
    }
}

/// Register the client for this tenant's issuer.
///
/// Three fields because that is the whole body the endpoint takes, and two of them are tri-state: a blank
/// half means "keep what is stored" and only the secret has an explicit way to say "clear it", which is why
/// the editor starts empty rather than echoing a value back that the wire never carried
/// (`McpOAuthClientRequest.kt:22-31`, `SecretFieldEncryptor.kt:94-104`). Dynamic registration is not
/// implemented server-side, so the credentials have to have been issued at the authorization server already
/// (`OAuthPanel.tsx:519-528`).
///
/// Public so the DEBUG walkthrough can frame the sheet from a launch argument — the simulator takes no input,
/// so a sheet the harness cannot name is a sheet nobody can review (`App/HarnaxDebugScreens.swift`).
public struct McpOAuthClientSheet: View {
    @ObservedObject var vm: McpDetailViewModel

    public init(vm: McpDetailViewModel) {
        self.vm = vm
    }

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text(verbatim: hx("mcp.oauth.client.hint", vm.issuerForClient ?? hx("mcp.oauth.setup.none")))
                        .font(.footnote)
                        .foregroundStyle(Color.hx(.textSecondary))
                        .fixedSize(horizontal: false, vertical: true)
                    VStack(alignment: .leading, spacing: 6) {
                        HXField("mcp.oauth.client.id", text: $vm.clientID, systemImage: "key.horizontal")
                        issue(idIssue)
                    }
                    VStack(alignment: .leading, spacing: 6) {
                        HXField(
                            "mcp.oauth.client.secretPlaceholder",
                            text: $vm.clientSecret,
                            systemImage: "lock",
                            secure: true
                        )
                        .disabled(vm.clearsClientSecret)
                        issue(secretIssue)
                    }
                    VStack(alignment: .leading, spacing: 6) {
                        HXField(
                            "mcp.oauth.client.callbackPlaceholder",
                            text: $vm.clientCallbackURL,
                            systemImage: "link",
                            kind: .URL
                        )
                        issue(callbackIssue)
                    }
                    HXGroupCard {
                        HXRow(text: hx("mcp.oauth.client.clearSecret"), divider: false) {
                            Toggle(isOn: Binding(
                                get: { vm.clearsClientSecret },
                                set: { vm.setClearsClientSecret($0) }
                            )) {
                                EmptyView()
                            }
                            .toggleStyle(.switch)
                            .labelsHidden()
                        }
                    }
                    if vm.clearsClientSecret {
                        Text(verbatim: hx("mcp.oauth.client.clearSecretNote"))
                            .font(.caption)
                            .foregroundStyle(Color.hx(.textTertiary))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    if let failure = vm.inlineError {
                        HXBanner("state.error.title", message: failure, systemImage: "exclamationmark.triangle", tone: .danger)
                    }
                }
                .padding(16)
            }
            .harnaxScreen()
            .navigationTitle(Text(verbatim: hx("mcp.oauth.client.title")))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { vm.closeClientEditor() } label: { HXText("common.cancel") }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        Task { await vm.saveClient() }
                    } label: {
                        HXText(vm.isSavingClient ? "mcp.oauth.client.saving" : "common.save")
                    }
                    // The draft cannot see the third case: a whitespace-only secret is a "keep what is
                    // stored" to the endpoint, so the local issue line is what holds the button.
                    .disabled(!vm.canSaveClient || idIssue != nil || callbackIssue != nil || secretIssue != nil)
                }
            }
        }
    }

    /// Left blank, the client id is not a registration: `@NotBlank` on the server, and the same trim it
    /// applies before checking.
    private var idIssue: String? {
        guard !McpOAuthClientDraft.isClientIDUsable(vm.clientID) else { return nil }
        return vm.clientID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? hx("mcp.oauth.client.idRequired")
            : hx("mcp.oauth.client.idTooLong", McpOAuthClientDraft.clientIDMaxLength)
    }

    private var callbackIssue: String? {
        guard !McpOAuthClientDraft.isCallbackURLUsable(vm.clientCallbackURL) else { return nil }
        return vm.clientCallbackURL.utf16.count > McpOAuthClientDraft.callbackURLMaxLength
            ? hx("mcp.oauth.client.callbackTooLong", McpOAuthClientDraft.callbackURLMaxLength)
            : hx("mcp.oauth.client.callbackNotHttp")
    }

    /// The secret has no server-side bound and no read-back; the only local rule is that whitespace is not a
    /// secret. Blank means "unchanged", which is what the placeholder says.
    private var secretIssue: String? {
        guard !vm.clearsClientSecret else { return nil }
        let raw = vm.clientSecret
        guard !raw.isEmpty, raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return hx("mcp.oauth.client.secretBlank")
    }

    @ViewBuilder
    private func issue(_ text: String?) -> some View {
        if let text {
            Text(verbatim: text)
                .font(.caption)
                .foregroundStyle(Color.hx(.danger))
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
