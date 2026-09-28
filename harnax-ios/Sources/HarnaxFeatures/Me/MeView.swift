import SwiftUI
import HarnaxCore
import HarnaxKit

/// Push destinations inside a tab. Value-based so the stack owns the history, not the row.
public enum HarnaxRoute: Hashable, Sendable {
    case appearance
    case serverAddress
}

/// F1 — who is signed in, the two preferences this build owns, and signing out.
///
/// Tenant switching, the permanent API key and biometric unlock are not on this screen yet: each needs a
/// backend call that does not exist in M0, and a row that does nothing is worse than no row.
public struct MeView: View {
    @ObservedObject var model: AppModel
    @StateObject private var vm: MeViewModel
    @ObservedObject private var catalog = HarnaxCatalog.shared
    @AppStorage(ThemeMode.storageKey) private var storedTheme = ThemeMode.system.rawValue
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
                if let account = model.account {
                    identityCard(account)
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
        .task {
            await vm.reload()
            // The profile write-back may have filled the tenant the login payload left out, and a 401
            // there has already ended the session.
            await model.sync()
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
