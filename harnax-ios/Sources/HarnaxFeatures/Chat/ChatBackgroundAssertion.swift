import Foundation
#if canImport(UIKit)
import UIKit
#endif

/// The system's background allowance, held for exactly as long as a run's stream is open.
///
/// `DESIGN.md:183` asks for this in one line: a confirmation can sit waiting for minutes, and without
/// `beginBackgroundTask` the process is suspended the moment the user leaves the app, so the read dies under
/// a turn the screen still shows as streaming. What the platform actually grants is far less than that line
/// reads as — the assertion ends after a short grace period and iOS calls the handler below to take it back.
/// The ceiling is the platform's and nothing a client can call raises it, so what this type guarantees is the
/// shorter thing: a turn that leaves the foreground keeps reading for exactly as long as the system allows,
/// and when the system takes the allowance back the run is ended honestly rather than left streaming
/// (`ChatViewModel.backgroundAllowanceExpired()`).
///
/// Held per read, released per read, and only ever one platform assertion: the member answer leg reads beside
/// the lead's open stream (`Sources/HarnaxFeatures/Chat/ChatViewModel.swift:633-634`), and a confirm leg can
/// open while the read that parked the run is still draining (`:412-451`, `:612-629`), so a second
/// `beginBackgroundTask` for the same turn would be a second assertion to forget. `Handle` is what makes each
/// release land on the take that owns it — a read that dies late must not end the allowance a newer run is
/// living on, which is the same reason `HXPollLoop` identifies its live loop
/// (`Sources/HarnaxFeatures/Support/HXPollLoop.swift:19-21`).
@MainActor
final class ChatBackgroundAssertion {
    /// The two system calls, injected. The test host is macOS 14 (`Package.swift:7`), where
    /// `UIApplication.shared` does not exist, and one unguarded reference to it stops the whole test target
    /// from building — the same gate the photo picker sits behind
    /// (`Sources/HarnaxFeatures/Chat/ChatImagePicker.swift:63-70`,
    /// `Sources/HarnaxFeatures/SystemDomain/HXPasteboard.swift:8-11`).
    struct Platform {
        /// `UIApplication.beginBackgroundTask(withName:expirationHandler:)`, as the token it handed out, or
        /// `nil` where this host has no background state to enter. `Int` because that is what
        /// `UIBackgroundTaskIdentifier.rawValue` is (`UIKit.UIBackgroundTaskIdentifier`).
        let begin: @MainActor (String, @escaping @MainActor () -> Void) -> Int?

        /// `UIApplication.endBackgroundTask(_:)`, called with the token `begin` handed back.
        let end: @MainActor (Int) -> Void
    }

    /// One outstanding take. Hand it back to `release(_:)` when the read that took it is over.
    struct Handle: Hashable {
        let id: Int
    }

    /// The name iOS puts next to the assertion in a hang report. English, like every other string a developer
    /// rather than a user reads.
    private static let taskName = "harnax-chat-run"

    /// Every read in flight, each with what its own expiry has to do.
    private var holders: [Handle: @MainActor () -> Void] = [:]
    /// The assertion the platform has granted and this object has not given back.
    private var token: Int?
    private let platform: Platform
    private var nextID = 0

    init(platform: Platform) {
        self.platform = platform
    }

    /// The system's own allowance where it has one, and nothing at all where it has not. The fallback takes
    /// no assertion but keeps the same bookkeeping, which is what lets a test on a Mac drive the whole
    /// take, share, release and expire sequence.
    convenience init() {
        self.init(platform: Self.system)
    }

    static func live() -> ChatBackgroundAssertion { ChatBackgroundAssertion() }

    /// Whether the platform has an assertion outstanding right now.
    var isHolding: Bool { token != nil }

    /// Ask for the allowance for one read that is opening, and say what to do if the system takes it back.
    ///
    /// A read that joins an assertion already held shares it rather than stacking a second one, so the
    /// platform sees one `begin` per run set rather than one per socket.
    @discardableResult
    func take(onExpire: @escaping @MainActor () -> Void) -> Handle {
        let handle = Handle(id: nextID)
        nextID += 1
        if holders.isEmpty {
            token = platform.begin(Self.taskName, { [weak self] in self?.revoke() })
        }
        holders[handle] = onExpire
        return handle
    }

    /// Give the allowance back for the read that took `handle`.
    ///
    /// Every exit a read has calls this, and only the first one for a given handle does anything: a stop has
    /// already let the whole set go through `releaseAll()`, and an answer whose stream outlived the screen it
    /// belonged to has nothing left to end here.
    func release(_ handle: Handle) {
        guard holders.removeValue(forKey: handle) != nil else { return }
        guard holders.isEmpty else { return }
        endAssertion()
    }

    /// Give it back for every read at once, which is what a stop, a conversation switch and leaving the
    /// screen all mean: this conversation is waiting on nothing any more.
    func releaseAll() {
        holders.removeAll()
        endAssertion()
    }

    private func endAssertion() {
        guard let token else { return }
        self.token = nil
        platform.end(token)
    }

    /// The system's turn: the grace period is over.
    ///
    /// The assertion goes back before anything is said, so a run that reacts to this by stopping cannot end
    /// a second assertion behind it, and every read that was living on it hears about it exactly once. Their
    /// own releases — which arrive as each socket closes — then find nothing outstanding.
    private func revoke() {
        guard !holders.isEmpty else { return }
        let expired = Array(holders.values)
        holders.removeAll()
        endAssertion()
        for onExpire in expired { onExpire() }
    }

    private static var system: Platform {
        Platform(
            begin: { name, onExpire in
                #if canImport(UIKit) && os(iOS)
                let granted = UIApplication.shared.beginBackgroundTask(withName: name) {
                    // iOS calls this on the thread the task was created from, which is this one; hopping
                    // through the actor rather than asserting the isolation keeps that true even when it is
                    // not, and the only cost is one run-loop turn.
                    Task { @MainActor in onExpire() }
                }
                return granted == .invalid ? nil : granted.rawValue
                #else
                _ = name
                _ = onExpire
                return nil
                #endif
            },
            end: { token in
                #if canImport(UIKit) && os(iOS)
                UIApplication.shared.endBackgroundTask(UIBackgroundTaskIdentifier(rawValue: token))
                #else
                _ = token
                #endif
            }
        )
    }
}
