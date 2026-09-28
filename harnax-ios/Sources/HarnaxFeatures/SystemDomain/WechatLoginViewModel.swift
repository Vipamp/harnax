import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// E3 — the WeChat QR sheet's flow object.
///
/// Mirrors the console's modal (`harnax-webui/src/pages/channel/components/WechatLoginModal.tsx`) in the four
/// behaviours that are visible from the outside:
///
/// - the poll runs every 2000 ms and one failed request does **not** end it (`:99-101`) — the login state is
///   held in a server-side memory map, so a dropped poll is noise rather than a verdict;
/// - `NOT_LOGIN` counts as expired (`:91-94`): it means the server has no login in progress for this channel,
///   which is what an expired QR has already become;
/// - success resolves 800 ms after the poll reports it (`:84-88`), so the "saved" state is actually seen;
/// - closing the sheet on an unresolved login cancels it server-side (`:105-111`), and that call's own failure
///   is ignored.
///
/// Nothing here retries a QR automatically. The console makes the operator press 重新获取, and the server's
/// five-minute timeout (`WechatLoginService.kt:60`) means a stale code is a real, visible state.
@MainActor
public final class WechatLoginViewModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        case waiting
        case scanned
        case success
        case expired
        case failed(String)

        /// An unresolved sheet owes the server a cancel.
        public var isResolved: Bool { self == .success }
    }

    @Published public private(set) var phase: Phase = .loading
    /// The decoded PNG. The view turns it into an image; this side keeps bytes so it stays testable.
    @Published public private(set) var pngData: Data?

    private let catalog: any ChannelCataloging
    private let channelID: Int64
    /// Both timings are the console's; they are inputs only so a test can watch the poll turn over without
    /// waiting two seconds per answer.
    private let pollInterval: Duration
    private let successHold: Duration
    private var pollTask: Task<Void, Never>?
    private var isBusy = false

    /// Called once the success hold has elapsed, so the parent can close the sheet and re-read the row — the
    /// credentials land in `configJson` on the server (`WechatLoginService.kt:162-186`) and no local update
    /// can show them.
    public var onSucceeded: (() -> Void)?

    public init(
        catalog: any ChannelCataloging,
        channelID: Int64,
        pollInterval: Duration = .milliseconds(2000),
        successHold: Duration = .milliseconds(800)
    ) {
        self.catalog = catalog
        self.channelID = channelID
        self.pollInterval = pollInterval
        self.successHold = successHold
    }

    public func begin() async {
        guard !isBusy else { return }
        isBusy = true
        defer { isBusy = false }
        stopPolling()
        phase = .loading
        pngData = nil
        switch await catalog.startWechatLogin(id: channelID) {
        case let .success(code):
            guard let data = code.pngData else {
                // A reply that carries no readable image is a failed generate, not an empty QR.
                phase = .failed(ErrorMessage.text(for: .unpackable))
                return
            }
            pngData = data
            phase = .waiting
            startPolling()
        case let .failure(error):
            phase = .failed(ErrorMessage.text(for: error))
        }
    }

    /// Stops the poll and, unless the login already succeeded, tells the server to drop it.
    public func dismiss() async {
        stopPolling()
        guard !phase.isResolved else { return }
        _ = await catalog.cancelWechatLogin(id: channelID)
    }

    public func stopPolling() {
        pollTask?.cancel()
        pollTask = nil
    }

    private func startPolling() {
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                try? await Task.sleep(for: pollInterval)
                if Task.isCancelled { return }
                await self.pollOnce()
            }
        }
    }

    private func pollOnce() async {
        // SCANNED keeps polling: the server flips to LOGGED_IN only after the phone confirms, so stopping at
        // the scan would leave a confirmed login unseen.
        switch phase {
        case .waiting, .scanned:
            break
        case .loading, .success, .expired, .failed:
            return
        }
        switch await catalog.wechatLoginStatus(id: channelID) {
        case let .success(update):
            apply(update)
        case .failure:
            // A transient poll failure keeps the QR on screen and the loop running.
            break
        }
    }

    private func apply(_ update: WechatLoginUpdate) {
        switch update.phase {
        case .scanned:
            phase = .scanned
        case .loggedIn:
            phase = .success
            Task { [weak self] in
                guard let self else { return }
                try? await Task.sleep(for: successHold)
                guard !Task.isCancelled else { return }
                await resolveSuccess()
            }
        case .expired, .notLoggedIn:
            stopPolling()
            phase = .expired
        case .error:
            stopPolling()
            // The server's own sentence when it writes one (`WechatLoginService.kt:134`).
            phase = .failed(hxPresented(update.message) ?? hx("channel.wechat.loginError"))
        case .waiting:
            break
        }
    }

    private func resolveSuccess() async {
        stopPolling()
        onSucceeded?()
    }
}
