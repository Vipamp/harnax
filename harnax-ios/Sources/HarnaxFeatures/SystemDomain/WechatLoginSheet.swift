import SwiftUI
import HarnaxCore
import HarnaxKit

/// E3 — the personal-WeChat scan sheet.
///
/// The QR is the whole credential path for this type: the console offers no field on the form
/// (`CreateForm.tsx:254-267`), and the token the scan writes lands in `configJson` server-side, which is why
/// a success closes the sheet and re-reads the row instead of trusting anything local.
///
/// The sheet disables interactive dismissal on purpose. Dragging it away would end the view without the
/// cancel call the server is owed (`WechatLoginViewModel.dismiss()`), leaving a five-minute login in flight
/// against a channel nobody is watching (`WechatLoginService.kt:60`), so Close is the only way out besides
/// the login resolving itself.
public struct WechatLoginSheet: View {
    @StateObject private var vm: WechatLoginViewModel
    @Environment(\.dismiss) private var dismissEnvironment

    private let onDone: () -> Void

    public init(catalog: any ChannelCataloging, channelID: Int64, onDone: @escaping () -> Void) {
        _vm = StateObject(wrappedValue: WechatLoginViewModel(catalog: catalog, channelID: channelID))
        self.onDone = onDone
    }

    public var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                state
                footnote
            }
            .padding(.horizontal, 20)
            .padding(.top, 20)
            .padding(.bottom, 28)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .harnaxScreen()
            .navigationTitle(Text(verbatim: hx("channel.wechat.scan")))
            #if canImport(UIKit)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button {
                        close()
                    } label: {
                        HXText("common.close")
                    }
                }
            }
        }
        .interactiveDismissDisabled(true)
        .task {
            vm.onSucceeded = close
            await vm.begin()
        }
    }

    @ViewBuilder
    private var state: some View {
        switch vm.phase {
        case .loading:
            HXStateView(.loading)
        case let .failed(message):
            HXStateView(.error, message: message, retry: { Task { await vm.begin() } })
        case .expired:
            HXStateView(.error, message: hx("channel.wechat.expired"), retry: { Task { await vm.begin() } })
        case .success:
            VStack(spacing: 10) {
                Image(systemName: "checkmark.circle")
                    .font(.system(size: 40))
                    .foregroundStyle(Color.hx(.success))
                HXText("channel.wechat.success")
                    .font(.headline)
                    .foregroundStyle(Color.hx(.textPrimary))
            }
            .frame(maxWidth: .infinity, minHeight: 244)
        case .waiting, .scanned:
            qr
        }
    }

    /// The code plus the one line that tells the operator what the current phase wants from them.
    @ViewBuilder
    private var qr: some View {
        VStack(spacing: 14) {
            qrImage
                .frame(width: 220, height: 220)
                .padding(12)
                .background(Color.hx(.surface), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .strokeBorder(Color.hx(.separator), lineWidth: 1)
                )
            if vm.phase == .scanned {
                HXBadge("channel.wechat.scanned", tone: .success)
            }
            HXText(vm.phase == .scanned ? "channel.wechat.confirm" : "channel.wechat.hint")
                .font(.footnote)
                .foregroundStyle(Color.hx(.textSecondary))
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private var qrImage: some View {
        if let data = vm.pngData {
            #if canImport(UIKit)
            if let image = UIImage(data: data) {
                // A QR is pixels, not a photograph: smoothing turns a crisp module into an unreadable blur.
                Image(uiImage: image)
                    .resizable()
                    .interpolation(.none)
                    .aspectRatio(contentMode: .fit)
            } else {
                HXStateView(.error, message: hx("channel.wechat.imageFailed"))
            }
            #else
            HXStateView(.error, message: hx("channel.wechat.imageFailed"))
            #endif
        } else {
            Color.hx(.surfaceAlt)
        }
    }

    private var footnote: some View {
        VStack(spacing: 8) {
            Button {
                Task { await vm.begin() }
            } label: {
                HXText("channel.wechat.refresh")
            }
            .buttonStyle(.hxSecondary)
            Text(verbatim: hx("channel.wechat.timeout"))
                .font(.caption)
                .foregroundStyle(Color.hx(.textTertiary))
                .multilineTextAlignment(.center)
        }
    }

    /// Closes the login, cancelling it server-side unless it already succeeded, then hands the row back to
    /// the list for a re-read.
    private func close() {
        onDone()
        dismissEnvironment()
        Task { await vm.dismiss() }
    }
}
