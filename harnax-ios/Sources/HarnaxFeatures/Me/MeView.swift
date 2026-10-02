import SwiftUI
import HarnaxCore
import HarnaxKit

/// Push destinations inside a tab. Value-based so the stack owns the history, not the row.
public enum HarnaxRoute: Hashable, Sendable {
    case serverAddress
}

/// F1 — who is signed in, which tenant the session is in, the preferences this build owns, the administration
/// domains that had a tab of their own, and signing out.
///
/// Everything this screen can change is on it. The two appearance picks and the tenant are menu rows rather
/// than a sub-screen and a sheet: a pick you have to navigate to reads as information, and the tenant entry
/// used to appear only once an account had a second membership, which is how it went unnoticed.
///
/// The account's own permanent key is no longer displayed here (O5's visible-here half). The administrator's
/// key list is a row of the group that came over from the 系统 tab, and it keeps that tab's gate.
public struct MeView: View {
    @ObservedObject var model: AppModel
    @StateObject private var vm: MeViewModel
    @ObservedObject private var catalog = HarnaxCatalog.shared
    @AppStorage(ThemeMode.storageKey) private var storedTheme = ThemeMode.system.rawValue
    @AppStorage(BiometricGate.defaultsKey) private var storedBiometricGate = false
    @State private var showsLogoutPrompt = false

    public init(model: AppModel) {
        self.model = model
        _vm = StateObject(wrappedValue: MeViewModel(auth: model.dependencies.auth))
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let error = vm.errorText {
                    HXBanner("state.error.title", message: error, systemImage: "exclamationmark.triangle", tone: .danger)
                }
                if let error = vm.tenantErrorText {
                    HXBanner("me.tenant.readFailed", message: error, systemImage: "exclamationmark.triangle", tone: .danger)
                }
                if let error = vm.switchErrorText {
                    HXBanner("me.tenant.switch", message: error, systemImage: "exclamationmark.triangle", tone: .danger)
                }
                if let account = model.account {
                    identityCard(account)
                }
                HXSectionHeader("me.section.account")
                HXGroupCard {
                    tenantRow
                }
                HXSectionHeader("me.section.preferences")
                HXGroupCard {
                    themeRow
                    languageRow
                    NavigationLink(value: HarnaxRoute.serverAddress) {
                        HXRow(
                            "me.server.address",
                            subtitle: vm.serverLine.isEmpty ? nil : vm.serverLine,
                            systemImage: "network",
                            divider: model.biometricsAvailable,
                            trailing: { HXChevron() }
                        )
                    }
                    .buttonStyle(.plain)
                    if model.biometricsAvailable {
                        HXRow(
                            "me.security.guard",
                            subtitle: hx("me.security.guard.hint"),
                            systemImage: "lock.shield",
                            divider: false,
                            trailing: {
                                Toggle(hx("me.security.guard"), isOn: $storedBiometricGate)
                                    .labelsHidden()
                                    .toggleStyle(.switch)
                            }
                        )
                    }
                }
                let routes = SystemRoute.visible(for: model.account)
                HXSectionHeader("system.section.administration")
                HXGroupCard {
                    ForEach(Array(routes.enumerated()), id: \.element) { offset, route in
                        NavigationLink(value: route) {
                            HXRow(
                                route.titleKey,
                                subtitle: hx(route.subtitleKey),
                                systemImage: route.systemImage,
                                divider: offset < routes.count - 1,
                                trailing: { HXChevron() }
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
                signOut
            }
            .padding(16)
            .padding(.bottom, HXLayout.tabBarClearance)
        }
        .harnaxScreen()
        .navigationDestination(for: HarnaxRoute.self) { route in
            switch route {
            case .serverAddress: ServerAddressView(auth: model.dependencies.auth)
            }
        }
        .navigationDestination(for: SystemRoute.self) { route in
            switch route {
            case .envVars:
                EnvVarListView(catalog: model.dependencies.envVars)
            case .apiKeys:
                ApiKeyListView(catalog: model.dependencies.apiKeys, account: model.account)
            case .channels:
                ChannelListView(
                    catalog: model.dependencies.channels,
                    agents: model.dependencies.agents,
                    account: model.account
                )
            case .tokenMonitor:
                TokenMonitorView(catalog: model.dependencies.tokenStats)
            }
        }
        .task {
            await vm.reload()
            // The profile write-back may have filled the tenant the login payload left out, and a 401
            // there has already ended the session.
            await model.sync()
        }
    }

    // MARK: - tenant

    /// The tenant the session is in, and the way out of it. The row stays when there is only one membership —
    /// an account still gets to read which tenant it is — and the menu is what refuses to open.
    @ViewBuilder
    private var tenantRow: some View {
        if model.account?.tenantID != nil && !vm.tenantChoices.isEmpty {
            HXRow("me.tenant.switch", divider: false) {
                Picker(selection: tenantSelection) {
                    ForEach(vm.tenantChoices, id: \.id) { tenant in
                        Text(verbatim: tenantLabel(tenant)).tag(tenant.id)
                    }
                } label: {
                    HXText("me.tenant.switch")
                }
                .pickerStyle(.menu)
                .tint(Color.hx(.brand))
                .disabled(!vm.canSwitchTenant || vm.isSwitching)
            }
        } else {
            // Either the read has no answerable row or the session has no tenant id yet; both leave a name to
            // show and nothing to switch to.
            HXRow("me.tenant.switch", divider: false) {
                Text(verbatim: currentTenantName)
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.textPrimary))
            }
        }
    }

    private var currentTenantName: String {
        guard let name = model.account?.tenantName, !name.isEmpty else { return hx("me.tenant.unknown") }
        return name
    }

    /// The sheet that listed the tenants had a chip for a disabled one; a menu has only its option text, and
    /// the backend still hands out a token for a disabled tenant, so the row stays selectable and the status
    /// has to be said in words.
    private func tenantLabel(_ tenant: TenantSummary) -> String {
        let name = tenant.name ?? hx("me.tenant.unknown")
        return tenant.status == 0 ? "\(name) · \(hx("me.tenant.disabled"))" : name
    }

    /// The menu moves the session. Only a real pick goes out, and the selection reads back `AppModel`, so a
    /// refusal puts the marker on the tenant still in effect rather than the one that was refused.
    private var tenantSelection: Binding<Int64?> {
        Binding(
            get: { model.account?.tenantID },
            set: { id in
                guard id != model.account?.tenantID,
                      let tenant = vm.tenantChoices.first(where: { $0.id == id }) else { return }
                Task {
                    _ = await vm.switchTo(tenant)
                    // A switch replaces the token, a refusal may have spent it; either way the root re-reads.
                    await model.sync()
                }
            }
        )
    }

    // MARK: - preferences

    private var themeRow: some View {
        HXRow("me.theme") {
            Picker(selection: $storedTheme) {
                ForEach(ThemeMode.allCases, id: \.rawValue) { mode in
                    Text(verbatim: hx(AppearanceSummary.themeKey(mode))).tag(mode.rawValue)
                }
            } label: {
                HXText("me.theme")
            }
            .pickerStyle(.menu)
            .tint(Color.hx(.brand))
        }
    }

    private var languageRow: some View {
        HXRow("me.language") {
            Picker(selection: $catalog.language) {
                ForEach(HarnaxLanguage.allCases, id: \.rawValue) { language in
                    Text(verbatim: AppearanceSummary.languageLabel(language)).tag(language)
                }
            } label: {
                HXText("me.language")
            }
            .pickerStyle(.menu)
            .tint(Color.hx(.brand))
        }
    }

    private func identityCard(_ account: AccountSnapshot) -> some View {
        HXCard {
            HStack(spacing: 12) {
                HXAvatar(name: account.displayName, size: .large)
                VStack(alignment: .leading, spacing: 7) {
                    HStack(spacing: 6) {
                        Text(verbatim: account.displayName)
                            .font(.title3.weight(.semibold))
                            .foregroundStyle(Color.hx(.textPrimary))
                            .lineLimit(1)
                        HXBadge(
                            account.isAdministrator ? "me.role.administrator" : "me.role.member",
                            tone: account.isAdministrator ? .brand : .teal
                        )
                    }
                    Text(verbatim: tenantLine(account))
                        .font(.footnote)
                        .foregroundStyle(Color.hx(.textSecondary))
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
            }
        }
    }

    /// `me` answers no tenant name, so an account restored from the keychain may only have the id.
    private func tenantLine(_ account: AccountSnapshot) -> String {
        let name = account.tenantName.flatMap { $0.isEmpty ? nil : $0 } ?? hx("me.tenant.unknown")
        let id = account.tenantID.map(String.init) ?? "—"
        return hx("me.tenant.current", name, id)
    }

    private var signOut: some View {
        VStack(spacing: 12) {
            Button {
                showsLogoutPrompt = true
            } label: {
                HXText("me.logout")
            }
            .buttonStyle(.hxDestructive)
        }
        .confirmationDialog(hx("me.logout.confirm"), isPresented: $showsLogoutPrompt, titleVisibility: .visible) {
            Button(role: .destructive) {
                Task { await model.signOut() }
            } label: {
                HXText("me.logout")
            }
            Button(role: .cancel) {
                showsLogoutPrompt = false
            } label: {
                HXText("common.cancel")
            }
        }
    }
}

/// The picker behind the identity card. A tap only moves the marker: entering another tenant swaps the
/// session token and reloads every screen underneath, so the step that costs something stays the one the
/// user took on purpose.
///
/// Mirror: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AuthController.kt:131-182`. The
/// web console renders no switcher at all (`harnax-webui/src/components/TenantSwitcher/index.tsx:92`).
public struct TenantSwitchSheet: View {
    private let tenants: [TenantSummary]
    private let currentID: Int64?
    private let isBusy: Bool
    private let errorText: String?
    private let onConfirm: (TenantSummary) -> Void

    public init(
        tenants: [TenantSummary],
        currentID: Int64?,
        isBusy: Bool,
        errorText: String?,
        onConfirm: @escaping (TenantSummary) -> Void
    ) {
        self.tenants = tenants
        self.currentID = currentID
        self.isBusy = isBusy
        self.errorText = errorText
        self.onConfirm = onConfirm
    }

    @Environment(\.dismiss) private var dismiss
    @State private var pending: Int64?

    public var body: some View {
        VStack(spacing: 0) {
            header
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    HXText("me.tenant.choose")
                        .font(.footnote)
                        .foregroundStyle(Color.hx(.textSecondary))
                        .fixedSize(horizontal: false, vertical: true)
                    if let errorText {
                        HXBanner(
                            "state.error.title",
                            message: errorText,
                            systemImage: "exclamationmark.triangle",
                            tone: .danger
                        )
                    }
                    rows
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
            }
            footer
        }
        .background(Color.hx(.background))
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private var header: some View {
        HStack {
            HXText("me.tenant.switch")
                .font(.headline)
                .foregroundStyle(Color.hx(.textPrimary))
            Spacer(minLength: 0)
            Button { dismiss() } label: { HXText("common.cancel") }
                .buttonStyle(.hxInline)
        }
        .padding(.horizontal, 16)
        .padding(.top, 14)
        .padding(.bottom, 6)
    }

    /// A row the switch route cannot address is not offered: `tenantId` is the only thing that goes out.
    private var choices: [(id: Int64, tenant: TenantSummary)] {
        tenants.compactMap { tenant in tenant.id.map { ($0, tenant) } }
    }

    private var rows: some View {
        VStack(spacing: 0) {
            ForEach(choices, id: \.id) { choice in
                row(choice)
            }
        }
        .background(Color.hx(.surface), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private func row(_ choice: (id: Int64, tenant: TenantSummary)) -> some View {
        let isCurrent = choice.id == currentID
        let isPending = pending == choice.id
        return Button { pending = choice.id } label: {
            HStack(spacing: 8) {
                Image(systemName: isPending ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(Color.hx(isPending ? .brand : .textTertiary))
                Text(verbatim: choice.tenant.name ?? hx("me.tenant.unknown"))
                    .font(.subheadline)
                    .foregroundStyle(Color.hx(.textPrimary))
                    .lineLimit(1)
                // The list answers active memberships only, but a tenant row itself can be disabled, and
                // the backend will still hand out a token for it.
                if choice.tenant.status == 0 {
                    HXChip(hx("me.tenant.disabled"), tone: .warning)
                }
                Spacer(minLength: 0)
                if isCurrent {
                    HXChip(hx("me.tenant.currentTag"), tone: .brand)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 11)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(isCurrent || isBusy)
    }

    private var footer: some View {
        VStack(spacing: 8) {
            Button {
                guard let pending, let choice = choices.first(where: { $0.id == pending }) else { return }
                onConfirm(choice.tenant)
            } label: {
                HXText("common.confirm")
            }
            .buttonStyle(.hxPrimary)
            .disabled(pending == nil || isBusy)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 18)
    }
}
