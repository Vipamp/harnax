import SwiftUI
import HarnaxCore
import HarnaxKit

/// A1 — sign in against the admin stack with the same credential the web console uses.
public struct LoginView: View {
    @ObservedObject private var model: AppModel
    @StateObject private var vm: LoginViewModel
    @State private var showsServerSheet = false
    /// The guard covers the credential form until the owner either unlocks or asks for the form; two
    /// full-width primary actions on one screen is a choice the screen should not be making for them.
    @State private var showsCredentialForm = false

    public init(model: AppModel) {
        self.model = model
        _vm = StateObject(wrappedValue: LoginViewModel(auth: model.dependencies.auth))
    }

    public var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                header
                if model.isAwaitingBiometric {
                    gate
                }
                if showsCredentialForm || !model.isAwaitingBiometric {
                    fields
                    if let error = vm.errorText {
                        HXBanner("state.error.title", message: error, systemImage: "exclamationmark.triangle", tone: .danger)
                    }
                    submitButton
                }
                serverRow
            }
            .padding(.horizontal, 20)
            .padding(.top, 36)
        }
        .harnaxScreen()
        .task { await vm.reload() }
        .sheet(isPresented: $showsServerSheet) {
            NavigationStack {
                ServerAddressView(auth: model.dependencies.auth)
            }
        }
        // The sheet is the only place addresses change, so the line under the wordmark is re-read when it
        // closes rather than on a timer.
        .onChange(of: showsServerSheet) { _, isOpen in
            if !isOpen { Task { await vm.reload() } }
        }
    }

    private var header: some View {
        VStack(spacing: 6) {
            Text(verbatim: "◈")
                .font(.system(size: 44))
                .foregroundStyle(Color.hx(.brand))
            Text(verbatim: "Harnax")
                .font(.largeTitle.weight(.semibold))
                .foregroundStyle(Color.hx(.textPrimary))
            HXText("login.subtitle")
                .font(.subheadline)
                .foregroundStyle(Color.hx(.textSecondary))
        }
        .padding(.bottom, 6)
    }

    /// The guard from R4: a stored session, and one prompt between the device and it. It covers the
    /// credential form rather than stacking on top of it, and the link below is what keeps this a guard
    /// instead of a lock — a password is always a valid answer.
    private var gate: some View {
        VStack(spacing: 10) {
            Button {
                Task { await model.unlockWithBiometrics(reason: hx("login.biometric.reason")) }
            } label: {
                HXText("login.biometric.submit")
            }
            .buttonStyle(.hxPrimary)
            HXText("login.biometric.hint")
                .font(.footnote)
                .foregroundStyle(Color.hx(.textTertiary))
                .multilineTextAlignment(.center)
            if let failure = model.biometricFailure, failure == .failed || failure == .unavailable {
                HXBanner(
                    "state.error.title",
                    message: hx(failure == .unavailable ? "login.biometric.unavailable" : "login.biometric.failed"),
                    systemImage: "exclamationmark.triangle",
                    tone: .danger
                )
            }
            Button {
                showsCredentialForm = true
            } label: {
                HXText("login.biometric.usePassword")
            }
            .buttonStyle(.hxInline)
        }
    }

    private var fields: some View {
        VStack(spacing: 12) {
            HXField("login.usernamePlaceholder", text: $vm.username, systemImage: "person")
            HXField("login.passwordPlaceholder", text: $vm.password, systemImage: "lock", secure: true)
        }
    }

    private var submitButton: some View {
        Button {
            Task {
                // Only an exchange answers the guard. A blank form or a refused credential re-reads the
                // session and leaves the gate exactly where it was — the stored sign-in is still behind it.
                if await vm.submit() {
                    await model.answerGate()
                } else {
                    await model.sync()
                }
            }
        } label: {
            HXText(vm.isSubmitting ? "login.signingIn" : "login.submit")
        }
        .buttonStyle(.hxPrimary)
        .disabled(vm.isSubmitting)
    }

    private var serverRow: some View {
        VStack(spacing: 10) {
            Button {
                showsServerSheet = true
            } label: {
                HXText("login.serverAddress")
            }
            .buttonStyle(.hxSecondary)
            if !vm.serverLine.isEmpty {
                HXBanner("login.serverBanner.title", message: vm.serverLine, systemImage: "network")
            }
            HXText("login.address.hint")
                .font(.footnote)
                .foregroundStyle(Color.hx(.textTertiary))
                .multilineTextAlignment(.center)
        }
    }
}

/// The admin and router addresses, pointed at whichever stack this device should talk to.
public struct ServerAddressView: View {
    @StateObject private var vm: ServerAddressViewModel
    @Environment(\.dismiss) private var dismiss

    public init(auth: any AuthFlowing) {
        _vm = StateObject(wrappedValue: ServerAddressViewModel(auth: auth))
    }

    public var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                HXField("login.address.admin", text: $vm.adminAddress, systemImage: "cable.connector", kind: .URL)
                HXField("login.address.router", text: $vm.routerAddress, systemImage: "network", kind: .URL)
                if let error = vm.errorText {
                    HXBanner("error.serverConfig", message: error, systemImage: "exclamationmark.triangle", tone: .danger)
                }
                if vm.saved {
                    HXBanner("login.address.saved", systemImage: "checkmark.circle", tone: .success)
                }
                HXBanner("login.serverBanner.title", message: hx("login.address.hint"), systemImage: "info.circle")
                Button {
                    Task { await vm.save() }
                } label: {
                    HXText("common.save")
                }
                .buttonStyle(.hxPrimary)
                .disabled(vm.isSaving)
                if vm.saved {
                    // No close affordance in the bar: the sheet swipes away, and this is the explicit
                    // "I have read the address back" step.
                    Button {
                        dismiss()
                    } label: {
                        HXText("common.confirm")
                    }
                    .buttonStyle(.hxSecondary)
                }
            }
            .padding(20)
            .padding(.bottom, HXLayout.tabBarClearance)
        }
        .harnaxScreen()
        .navigationTitle(Text(verbatim: hx("login.serverAddress")))
        .task { await vm.load() }
    }
}
