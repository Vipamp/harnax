import SwiftUI
import HarnaxCore
import HarnaxKit

/// Push destinations inside a tab. Value-based so the stack owns the history, not the row.
public enum HarnaxRoute: Hashable, Sendable {
    case appearance
    case serverAddress
}

/// F1 — who is signed in, which tenant the session is in, the two preferences this build owns, and signing
/// out.
///
/// The permanent API key and biometric unlock are not on this screen: each needs a backend call that does not
/// exist, and a row that does nothing is worse than no row.
public struct MeView: View {
    @ObservedObject var model: AppModel
    @StateObject private var vm: MeViewModel
    @ObservedObject private var catalog = HarnaxCatalog.shared
    @AppStorage(ThemeMode.storageKey) private var storedTheme = ThemeMode.system.rawValue
    @State private var showsLogoutPrompt = false
    @State private var showsTenants = false

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
                if let account = model.account {
                    if vm.canSwitchTenant {
                        Button { showsTenants = true } label: {
                            identityCard(account, showsChevron: true)
                        }
                        .buttonStyle(.plain)
                    } else {
                        identityCard(account, showsChevron: false)
                    }
                }
                HXSectionHeader("me.section.preferences")
                HXGroupCard {
                    NavigationLink(value: HarnaxRoute.appearance) {
                        HXRow(
                            "me.themeAndLanguage",
                            subtitle: AppearanceSummary.line(
                                mode: ThemeMode.stored(storedTheme),
                                language: catalog.language
                            ),
                            systemImage: "paintbrush",
                            trailing: { HXChevron() }
                        )
                    }
                    .buttonStyle(.plain)
                    NavigationLink(value: HarnaxRoute.serverAddress) {
                        HXRow(
                            "me.server.address",
                            subtitle: vm.serverLine.isEmpty ? nil : vm.serverLine,
                            systemImage: "network",
                            divider: false,
                            trailing: { HXChevron() }
                        )
                    }
                    .buttonStyle(.plain)
                }
                signOut
            }
            .padding(16)
            .padding(.bottom, HXLayout.tabBarClearance)
        }
        .harnaxScreen()
        .navigationDestination(for: HarnaxRoute.self) { route in
            switch route {
            case .appearance: AppearanceSettingsView()
            case .serverAddress: ServerAddressView(auth: model.dependencies.auth)
            }
        }
        .sheet(isPresented: $showsTenants) {
            TenantSwitchSheet(
                tenants: vm.tenants,
                currentID: model.account?.tenantID,
                isBusy: vm.isSwitching,
                errorText: vm.switchErrorText
            ) { tenant in
                Task {
                    let moved = await vm.switchTo(tenant)
                    if moved { showsTenants = false }
                    // The account copy carries the tenant the card shows, and a 401 on the way in has
                    // already ended the session — either way the root has to re-read it.
                    await model.sync()
                }
            }
        }
        .task {
            await vm.reload()
            // The profile write-back may have filled the tenant the login payload left out, and a 401
            // there has already ended the session.
            await model.sync()
        }
    }

    private func identityCard(_ account: AccountSnapshot, showsChevron: Bool) -> some View {
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
                if showsChevron { HXChevron() }
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
