import Foundation
import HarnaxCore

/// The one polling mechanism the scheduled-task screens share.
///
/// Both polls are data-driven rather than constant: the task list re-reads every 3 s *only* while the page holds
/// a Running or Stopping row and cancels itself when it does not
/// (`harnax-webui/src/pages/agent-task/index.tsx:67-80`), and the log page keeps a timer alive at any rate — 3 s
/// with an in-flight row, 5 s without (`TaskLogModal.tsx:77-113`). `nextInterval` is where that difference lives,
/// and it is re-read before every wait so a screen can change cadence without being restarted.
///
/// `park()`/`unpark()` are the `scenePhase` hooks (`§6.2`): background screens do not spend requests on rows
/// nobody is looking at, and coming back re-arms from the data rather than from a pull to refresh.
@MainActor
final class HXPollLoop {
    private let nextInterval: @MainActor () -> Duration?
    private let tick: @MainActor () async -> Void
    private var task: Task<Void, Never>?
    /// Identifies the live loop, so a tick that wakes after its loop was cancelled cannot retire the loop that
    /// has replaced it.
    private var token: UUID?
    private var parked = false

    var isRunning: Bool { task != nil }

    init(
        nextInterval: @escaping @MainActor () -> Duration?,
        tick: @escaping @MainActor () async -> Void
    ) {
        self.nextInterval = nextInterval
        self.tick = tick
    }

    /// Call after every data change: it starts what the data now asks for and cancels what it no longer does.
    func sync() {
        guard let interval = nextInterval(), !parked else {
            cancel()
            return
        }
        guard task == nil else { return }
        let token = UUID()
        self.token = token
        task = Task { [weak self] in await self?.run(token: token, interval: interval) }
    }

    func park() {
        parked = true
        cancel()
    }

    func unpark() {
        guard parked else { return }
        parked = false
        sync()
    }

    func cancel() {
        task?.cancel()
        task = nil
        token = nil
    }

    private func run(token: UUID, interval: Duration) async {
        var wait = interval
        while !Task.isCancelled {
            try? await Task.sleep(for: wait)
            if Task.isCancelled { break }
            await tick()
            guard let next = nextInterval() else { break }
            wait = next
        }
        if self.token == token { cancel() }
    }
}
