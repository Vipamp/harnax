import SwiftUI
import HarnaxCore
import HarnaxKit

/// C1 — create or edit one MCP server.
///
/// Two things make this sheet more than a stack of text fields. The transport row cannot offer stdio on a
/// new row and only keeps it for a row that already is stdio, because the runtime refuses the transport
/// while `harnax.mcp.stdio-enabled` is off (`McpStdioPolicy.kt:20-31`) — and the sheet says why rather than
/// hiding the option silently. And an edit sends only the fields the operator touched, because the update
/// body reads an absent key as "keep the column" (`McpServerServiceImpl.kt:174-252`).
struct McpFormView: View {
    @StateObject private var vm: McpFormViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var issues: [McpFormIssue] = []
    @State private var failure: String?
    @State private var showsURLConfirmation = false
    private let onSaved: () async -> Void

    init(mcp: any McpCataloging, mode: McpFormViewModel.Mode, onSaved: @escaping () async -> Void) {
        _vm = StateObject(wrappedValue: McpFormViewModel(mcp: mcp, mode: mode))
        self.onSaved = onSaved
    }

    private var isCreate: Bool { vm.mode == .create }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if !issues.isEmpty {
                        HXBanner(
                            "state.error.title",
                            message: issues.map(\.message).joined(separator: "\n"),
                            systemImage: "exclamationmark.triangle",
                            tone: .danger
                        )
                    }
                    if let failure {
                        HXBanner("state.error.title", message: failure, systemImage: "exclamationmark.triangle", tone: .danger)
                    }
                    if let outcome = vm.testOutcome {
                        HXBanner(
                            "mcp.test.title",
                            message: outcome.copy,
                            systemImage: outcome.isPassed ? "checkmark.circle" : "exclamationmark.triangle",
                            tone: outcome.tone
                        )
                    }
                    identitySection
                    transportSection
                    if vm.showsEndpoint { endpointSection }
                    if vm.showsHeaders { headerSection }
                    if vm.showsEnvParams { envParamSection }
                    authSection
                    visibilitySection
                    if !isCreate {
                        testRow
                    } else {
                        HXBanner("mcp.form.testAfterSave", systemImage: "bolt", tone: .textTertiary)
                    }
                }
                .padding(16)
            }
            .harnaxScreen()
            .navigationTitle(Text(verbatim: hx(isCreate ? "mcp.create" : "mcp.edit")))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { HXText("common.cancel") }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        submit()
                    } label: {
                        HXText(vm.isSaving ? "mcp.form.saving" : "common.save")
                    }
                    .disabled(vm.isSaving)
                }
            }
            .confirmationDialog(
                Text(verbatim: hx("mcp.form.urlChanged.title")),
                isPresented: $showsURLConfirmation,
                titleVisibility: .visible
            ) {
                Button(role: .destructive) {
                    submit(acknowledgingURLChange: true)
                } label: {
                    HXText("mcp.form.urlChanged.confirm")
                }
                Button(role: .cancel) {} label: { HXText("common.cancel") }
            } message: {
                Text(verbatim: hx("mcp.form.urlChanged.note"))
            }
        }
    }

    // MARK: - Sections

    private var identitySection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HXSectionHeader("mcp.form.section.identity")
            HXField("mcp.form.name", text: $vm.name, systemImage: "textformat")
            HXField("mcp.form.description", text: $vm.detail, systemImage: "alignleft")
        }
    }

    /// The transport choice, with stdio explained rather than omitted.
    private var transportSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HXSectionHeader("mcp.form.transport")
            HXFlow(spacing: 8) {
                ForEach(Array(vm.availableTransports.enumerated()), id: \.offset) { _, option in
                    transportChip(option)
                }
            }
            if isCreate {
                Text(verbatim: hx("mcp.stdio.note"))
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.textTertiary))
                    .fixedSize(horizontal: false, vertical: true)
            } else if vm.transport.isStdio {
                Text(verbatim: hx("mcp.stdio.legacy"))
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.textTertiary))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func transportChip(_ option: McpTransport) -> some View {
        Button {
            issues = []
            vm.transport = option
        } label: {
            HXChip(McpLabels.transport(option), tone: vm.transport == option ? .brand : nil)
        }
        .buttonStyle(.plain)
    }

    private var endpointSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HXSectionHeader("mcp.form.url")
            HXField("mcp.form.url.placeholder", text: $vm.endpointURL, systemImage: "link", kind: .URL)
            if vm.movesAuthorizedResource {
                Text(verbatim: hx("mcp.form.urlChanged.note"))
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.warning))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var headerSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HXSectionHeader("mcp.form.headers")
            Text(verbatim: hx("mcp.form.headers.note"))
                .font(.footnote)
                .foregroundStyle(Color.hx(.textTertiary))
                .fixedSize(horizontal: false, vertical: true)
            ForEach(Array(vm.headers.enumerated()), id: \.offset) { index, row in
                headerRow(index, row)
            }
            addRow(key: "mcp.form.addEntry", action: vm.addHeader)
        }
    }

    private func headerRow(_ index: Int, _ row: McpHeaderDraft) -> some View {
        HXGroupCard {
            HXField("mcp.form.key", text: $vm.headers[index].key, systemImage: "key")
            HXField(
                "mcp.form.value",
                text: $vm.headers[index].value,
                systemImage: "textformat.abc",
                kind: .URL
            )
            HStack(spacing: 8) {
                Toggle(isOn: $vm.headers[index].secret) {
                    HXText("mcp.form.secret")
                }
                .toggleStyle(.button)
                // The row was loaded masked, so the field holds the mask: saying so is the only way the
                // operator knows this value will be kept rather than re-entered.
                if row.isMasked {
                    HXChip(hx("mcp.form.keptMask"), tone: .warning)
                }
                Spacer(minLength: 0)
                removeButton { vm.removeHeader(at: index) }
            }
            .padding(.horizontal, 13)
            .padding(.vertical, 6)
        }
    }

    private var envParamSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HXSectionHeader("mcp.form.envParams")
            Text(verbatim: hx("mcp.form.envParams.note"))
                .font(.footnote)
                .foregroundStyle(Color.hx(.textTertiary))
                .fixedSize(horizontal: false, vertical: true)
            ForEach(Array(vm.envParams.enumerated()), id: \.offset) { index, row in
                envParamRow(index, row)
            }
            addRow(key: "mcp.form.addEntry", action: vm.addEnvParam)
        }
    }

    private func envParamRow(_ index: Int, _ row: McpEnvParamDraft) -> some View {
        HXGroupCard {
            HXField("mcp.form.paramName", text: $vm.envParams[index].name, systemImage: "key")
            HXField("mcp.form.description", text: $vm.envParams[index].detail, systemImage: "alignleft")
            HXField("mcp.form.paramDefault", text: $vm.envParams[index].defaultValue, systemImage: "equal.circle")
            HStack(spacing: 8) {
                Toggle(isOn: $vm.envParams[index].required) {
                    HXText("mcp.form.paramRequired")
                }
                .toggleStyle(.button)
                Toggle(isOn: $vm.envParams[index].secret) {
                    HXText("mcp.form.secret")
                }
                .toggleStyle(.button)
                if row.isMasked {
                    HXChip(hx("mcp.form.keptMask"), tone: .warning)
                }
                Spacer(minLength: 0)
                removeButton { vm.removeEnvParam(at: index) }
            }
            .padding(.horizontal, 13)
            .padding(.vertical, 6)
        }
    }

    private var authSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HXSectionHeader("mcp.form.authType")
            HXFlow(spacing: 8) {
                ForEach(Array(vm.shownAuthKinds.enumerated()), id: \.offset) { _, option in
                    Button {
                        issues = []
                        vm.auth = option
                    } label: {
                        HXChip(McpLabels.auth(option), tone: vm.auth == option ? .brand : nil)
                    }
                    .buttonStyle(.plain)
                }
            }
            if vm.auth == .basic {
                Text(verbatim: hx("mcp.form.legacyAuth"))
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.warning))
                    .fixedSize(horizontal: false, vertical: true)
            }
            if vm.showsOAuthFields {
                oauthFields
            }
        }
    }

    private var oauthFields: some View {
        VStack(alignment: .leading, spacing: 8) {
            HXField("mcp.form.oauth.issuer", text: $vm.issuer, systemImage: "lock", kind: .URL)
            if let stored = vm.originalIssuer, stored.isEmpty == false {
                Text(verbatim: hx("mcp.form.oauth.storedIssuer", stored))
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.textTertiary))
                    .fixedSize(horizontal: false, vertical: true)
            }
            HXField("mcp.form.oauth.scopes", text: $vm.scopes, systemImage: "list.bullet")
            HXField("mcp.form.oauth.audience", text: $vm.audience, systemImage: "target")
            Toggle(isOn: $vm.usesResourceIndicator) {
                HXText("mcp.form.oauth.resource")
            }
            .toggleStyle(.button)
            .padding(.horizontal, 13)
            .padding(.vertical, 6)
            Text(verbatim: hx("mcp.form.oauth.note"))
                .font(.footnote)
                .foregroundStyle(Color.hx(.textTertiary))
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var visibilitySection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HXSectionHeader("mcp.form.section.state")
            HXGroupCard {
                Toggle(isOn: $vm.enabled) {
                    HXText("state.badge.enabled")
                }
                .toggleStyle(.switch)
                .padding(.horizontal, 13)
                Toggle(isOn: $vm.isPublic) {
                    HXText("mcp.form.isPublic")
                }
                .toggleStyle(.switch)
                .padding(.horizontal, 13)
            }
            Text(verbatim: hx("mcp.form.isPublic.note"))
                .font(.footnote)
                .foregroundStyle(Color.hx(.textTertiary))
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var testRow: some View {
        Button {
            issues = []
            failure = nil
            Task { await vm.runTest() }
        } label: {
            HXText(vm.isTesting ? "mcp.testing" : "mcp.action.test")
        }
        .buttonStyle(.hxSecondary)
        .disabled(vm.isTesting || !vm.canRunTest)
    }

    private func addRow(key: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HXText(key)
        }
        .buttonStyle(.hxInline)
    }

    private func removeButton(_ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: "trash")
                .foregroundStyle(Color.hx(.danger))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(verbatim: hx("state.action.delete")))
    }

    private func submit(acknowledgingURLChange: Bool = false) {
        failure = nil
        Task {
            switch await vm.save(acknowledgingURLChange: acknowledgingURLChange) {
            case .saved:
                await onSaved()
                dismiss()
            case let .invalid(found):
                issues = found
            case .needsURLConfirmation:
                showsURLConfirmation = true
            case let .failed(message):
                failure = message
            }
        }
    }
}
