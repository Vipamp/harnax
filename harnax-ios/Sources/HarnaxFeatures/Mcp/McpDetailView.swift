import SwiftUI
import HarnaxCore
import HarnaxKit

/// The detail screen for one MCP server: the stored configuration grouped by what it decides, the tool
/// surface the runtime will see, and — for a server that authorizes per user — this operator's own grant.
///
/// Three reads and no more: the row, `list_tools`, and `oauth/status`. The first two come from
/// `McpServerController.kt:63,148-175` and the third from `McpOAuthController.kt:109`; discovery, client
/// registration and the code exchange are not here, so nothing on this screen can write a credential.
public struct McpDetailView: View {
    @StateObject private var vm: McpDetailViewModel

    public init(mcp: any McpCataloging, authorizer: any McpAuthorizing, id: Int64) {
        _vm = StateObject(wrappedValue: McpDetailViewModel(mcp: mcp, authorizer: authorizer, id: id))
    }

    public var body: some View {
        content
            .harnaxScreen()
            .navigationTitle(Text(verbatim: vm.title))
            .task {
                if vm.server == nil { await vm.load() }
            }
            .refreshable { await vm.load() }
    }

    @ViewBuilder
    private var content: some View {
        switch vm.phase {
        case .loading:
            HXStateView(.loading)
        case let .failed(message):
            HXStateView(.error, message: message, retry: { Task { await vm.load() } })
        case .content:
            body_
        }
    }

    private var body_: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let inline = vm.inlineError {
                    HXBanner("state.error.title", message: inline, systemImage: "exclamationmark.triangle", tone: .danger)
                }
                header
                if let server = vm.server {
                    configurationSection(server)
                    if let outcome = vm.testOutcome {
                        outcomeBanner(outcome)
                    }
                    toolsSection
                    if vm.showsOAuth {
                        oauthSection
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, 24)
        }
    }

    /// The switch and the two writes that need no second screen.
    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 10) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(verbatim: vm.title)
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(Color.hx(.textPrimary))
                    HXFlow(spacing: 6) {
                        HXBadge(vm.isEnabled ? "state.badge.enabled" : "state.badge.disabled",
                                tone: vm.isEnabled ? .success : .textTertiary)
                        if let server = vm.server {
                            HXChip(McpLabels.transport(server.transport), tone: .brand)
                            HXChip(McpLabels.auth(server.auth), tone: server.requiresPerUserOAuth ? .warning : nil)
                        }
                    }
                }
                Spacer(minLength: 0)
                Toggle(isOn: Binding(
                    get: { vm.isEnabled },
                    set: { value in Task { await vm.setEnabled(value) } }
                )) {
                    EmptyView()
                }
                .toggleStyle(.switch)
                .labelsHidden()
            }
            Button {
                Task { await vm.runTest() }
            } label: {
                HXText(vm.isTesting ? "mcp.testing" : "mcp.action.test")
            }
            .buttonStyle(.hxSecondary)
            .disabled(vm.isTesting)
        }
    }

    private func configurationSection(_ server: McpServerRow) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HXSectionHeader("mcp.detail.section.transport")
            HXGroupCard {
                if let endpoint = server.endpoint {
                    HXRow(text: server.transport.usesCommand ? hx("mcp.form.command") : hx("mcp.form.url")) {
                        HXValueText(endpoint)
                    }
                }
                HXRow(text: hx("mcp.detail.section.auth"), subtitle: McpLabels.auth(server.auth)) {
                    EmptyView()
                }
                if !server.headerEntries.isEmpty {
                    HXRow(text: hx("mcp.form.headers"), subtitle: headerSummary(server)) {
                        EmptyView()
                    }
                }
                HXRow(
                    text: hx("mcp.form.isPublic"),
                    subtitle: server.isShared ? hx("state.badge.shared") : hx("mcp.detail.private")
                ) { EmptyView() }
                if let description = hxPresented(server.description) {
                    HXRow(text: hx("mcp.form.description"), subtitle: description, divider: false) {
                        EmptyView()
                    }
                }
            }
            if vm.showsStdioNotice {
                Text(verbatim: hx("mcp.stdio.note"))
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.textTertiary))
                    .fixedSize(horizontal: false, vertical: true)
            }
            metaSection(server)
        }
    }

    /// Header keys, never values: a secret one arrives masked and a non-secret one is still a credential
    ///-shaped string the screen has no reason to print (`McpServerResponse.kt:55-56`).
    private func headerSummary(_ server: McpServerRow) -> String {
        server.headerEntries.compactMap { hxPresented($0.key) }.joined(separator: ", ")
    }

    private func metaSection(_ server: McpServerRow) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HXSectionHeader("mcp.detail.section.meta")
            HXGroupCard {
                let byline = RowMeta.byline(creator: server.creator, createTime: server.createTime)
                if !byline.isEmpty {
                    HXRow(text: byline) { EmptyView() }
                }
                if let updated = hxPresented(server.updateTime) {
                    HXRow(text: hx("mcp.detail.updated"), subtitle: updated, divider: false) {
                        EmptyView()
                    }
                }
            }
        }
    }

    private var toolsSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HXSectionHeader("mcp.detail.section.tools")
            switch vm.tools {
            case .loading:
                HXStateView(.loading)
            case .notEnabled, .loaded, .failed:
                if vm.tools.tools.isEmpty {
                    HXGroupCard {
                        HXRow(text: vm.tools.message ?? "", divider: false) { EmptyView() }
                    }
                    // Only a refused read has anything to retry: an off row and a server with no tools both
                    // answer the same way twice.
                    if case .failed = vm.tools {
                        Button {
                            Task { await vm.loadTools() }
                        } label: {
                            HXText("common.retry")
                        }
                        .buttonStyle(.hxInline)
                    }
                } else {
                    HXChip(hx("mcp.tools.count", vm.tools.tools.count), tone: .purple)
                    ForEach(Array(vm.tools.tools.enumerated()), id: \.offset) { _, tool in
                        McpToolCard(tool: tool)
                    }
                }
            }
        }
    }

    // MARK: - OAuth

    private var oauthSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HXSectionHeader("mcp.oauth.section")
            HXGroupCard {
                statusRow
                if case let .loaded(status) = vm.oauthPhase {
                    if !status.grantedScopes.isEmpty {
                        HXRow(text: hx("mcp.oauth.scopes"), subtitle: status.grantedScopes.joined(separator: ", ")) {
                            EmptyView()
                        }
                    }
                    if let error = status.error {
                        HXRow(text: hx("mcp.oauth.lastError"), subtitle: error) { EmptyView() }
                    }
                }
                if let url = vm.authorizationURL {
                    HXRow(text: hx("mcp.oauth.browser")) {
                        HXValueText(url, lines: 3)
                    }
                }
                if let message = vm.revokeMessage {
                    HXRow(text: message, divider: false) { EmptyView() }
                }
                actions
            }
            Text(verbatim: hx("mcp.oauth.hint"))
                .font(.footnote)
                .foregroundStyle(Color.hx(.textTertiary))
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var statusRow: some View {
        HXRow(text: hx("mcp.oauth.state"), subtitle: vm.oauthBadge) {
            switch vm.oauthPhase {
            case .loading, .idle:
                ProgressView().tint(Color.hx(.brand))
            case let .loaded(status):
                HXBadge(
                    status.authorized ? "mcp.oauth.authorized" : "mcp.oauth.required",
                    tone: McpOAuthPresentation.tone(for: status)
                )
            case .failed:
                Button {
                    Task { await vm.loadOAuth() }
                } label: {
                    HXText("common.retry")
                }
                .buttonStyle(.hxInline)
            }
        }
    }

    @ViewBuilder
    private var actions: some View {
        HStack(spacing: 10) {
            if case .failed(let message) = vm.oauthPhase {
                Text(verbatim: message)
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.danger))
                    .fixedSize(horizontal: false, vertical: true)
            }
            if !vm.oauthReadFailed {
                Button {
                    Task { await vm.startAuthorization() }
                } label: {
                    HXText(vm.oauthActionKey)
                }
                .buttonStyle(.hxSecondary)
                .disabled(vm.isRequestingAuthorization)
            }
            if vm.canRevoke {
                Button {
                    Task { await vm.revoke() }
                } label: {
                    HXText(vm.isRevoking ? "mcp.oauth.revoking" : "mcp.oauth.action.revoke")
                }
                .buttonStyle(.hxInline)
                .disabled(vm.isRevoking)
            }
        }
        .padding(.vertical, 6)
        .padding(.horizontal, 13)
    }

    private func outcomeBanner(_ outcome: McpTestOutcome) -> some View {
        HXBanner(
            "mcp.test.title",
            message: outcome.copy,
            systemImage: outcome.isPassed ? "checkmark.circle" : "exclamationmark.triangle",
            tone: outcome.tone
        )
        .overlay(alignment: .topTrailing) {
            Button(action: { vm.dismissTest() }) {
                Image(systemName: "xmark")
                    .font(.caption2)
                    .foregroundStyle(Color.hx(.textTertiary))
                    .padding(6)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(verbatim: hx("common.close")))
        }
    }
}

/// One tool: its name, what it takes, and the parameter names the runtime will be called with.
///
/// `list_tools` flattens `inputSchema.properties` to `{name, type, description}` and drops `required` and
/// the enums (`McpServerController.kt:154-171`), so this row is a chip cloud rather than an expandable
/// table — the interface has nothing left to expand.
struct McpToolCard: View {
    let tool: McpToolRow

    var body: some View {
        HXCard {
            VStack(alignment: .leading, spacing: 7) {
                Text(verbatim: tool.displayName ?? tool.name)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.hx(.textPrimary))
                    .lineLimit(1)
                if tool.parameters.isEmpty {
                    HXChip(hx("mcp.tool.noParams"))
                } else {
                    HXChip(hx("mcp.tool.params", tool.parameters.count), tone: .purple)
                    HXFlow(spacing: 6) {
                        ForEach(Array(tool.parameters.enumerated()), id: \.offset) { _, parameter in
                            HXChip(parameter.label, tone: .teal)
                        }
                    }
                }
            }
        }
    }
}
