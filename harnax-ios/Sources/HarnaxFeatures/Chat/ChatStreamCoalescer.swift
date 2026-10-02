import Foundation
import HarnaxCore

/// The frame window's decision maker (`DESIGN.md` §10 性能: 「流式增量合并节流到一帧一次」).
///
/// What the console pays for every token is not the merge — `ChatTranscript` already folds consecutive deltas
/// into one growing block (`Sources/HarnaxFeatures/Chat/ChatTranscript.swift:668-685`) — it is the *publish*:
/// telling the view invalidates the transcript, so a run that says sixty words a second redraws the whole
/// conversation sixty times a second. This type decides how often the screen is told and nothing else. The
/// frames it hands back are the frames that arrived, in the order they arrived, folded by the same reducer as
/// before: batching changes the number of updates, never their content.
///
/// Two rules, and they are the whole design:
///
/// * A frame that only *grows* what is already on screen — a text or thinking delta — may wait for the window's
///   tick. Many of them wait together and reach the screen as one update.
/// * A frame that changes the screen's state goes through at once and takes everything the window was holding
///   with it: the run ending or failing, a tool call or result, a confirmation appearing or settling, a
///   keep-alive. Nothing waiting can hold that up, which is also what keeps the read loop's own
///   `transcript.isTerminated` test (`Sources/HarnaxFeatures/Chat/ChatViewModel.swift:444`) reading the truth
///   the moment the end frame lands instead of one tick late.
///
/// The first delta of a window is published rather than queued, so a run's first token never gains latency;
/// only the deltas that arrive behind it inside the same window are held.
///
/// Pure and synchronous, and it holds no clock: the caller owns the tick and injects it (``ChatFrameClock``),
/// which is why every decision here is assertable without waiting on anything.
struct ChatStreamCoalescer {
    /// What the screen does with one frame.
    enum Outcome: Equatable {
        /// Fold these frames now, in this order, and tell the view once. Never empty.
        case publish([ChatEvent])
        /// The frame waits for the window's tick. Nothing reaches the screen.
        case hold
        /// The window ended with nothing to show. The clock has no work left either.
        case idle
    }

    /// Whether a frame only grows the block already on screen, which is the one thing allowed to wait.
    ///
    /// A sealing delta (`isLast`, `Sources/HarnaxFeatures/Chat/ChatTranscript.swift:443-448`) grows nothing —
    /// its payload is discarded — but the seal is invisible until the *next* delta arrives, and that delta
    /// folds after it in the same batch, so the pair still reaches the reducer in arrival order.
    static func growsContent(_ event: ChatEvent) -> Bool {
        switch event {
        case .text, .thinking:
            return true
        case .toolCall, .toolResult, .toolConfirm, .end, .failure, .keepAlive:
            return false
        }
    }

    /// A leading frame has reached the screen and its tick has not come back yet.
    private(set) var isWindowOpen = false
    /// The frames waiting for that tick, oldest first.
    private(set) var held: [ChatEvent] = []

    init() {}

    /// The decision for one arriving frame. This is the function a test drives directly.
    mutating func accept(_ event: ChatEvent) -> Outcome {
        guard Self.growsContent(event) else {
            // State changes are never queued behind content: the held frames ride along in this same update,
            // ahead of this frame, because they arrived ahead of it.
            return .publish(settle() + [event])
        }
        guard !isWindowOpen else {
            held.append(event)
            return .hold
        }
        isWindowOpen = true
        return .publish([event])
    }

    /// The window's tick.
    ///
    /// Held frames go to the screen and the window re-arms immediately: a run that is talking is then updated
    /// once per frame and not once per two frames. A window that held nothing is over, and the clock with it.
    mutating func tick() -> Outcome {
        guard isWindowOpen else { return .idle }
        let batch = held
        held = []
        guard !batch.isEmpty else {
            isWindowOpen = false
            return .idle
        }
        return .publish(batch)
    }

    /// Close the window and hand back what it held, oldest first.
    ///
    /// Every path that touches the transcript for another reason — a send, a stop, a settled confirmation, a
    /// read that ended — takes the batch first and folds it *before* its own change. That is what keeps a held
    /// delta from ever landing after the turn the user started while it was waiting.
    mutating func settle() -> [ChatEvent] {
        let batch = held
        held = []
        isWindowOpen = false
        return batch
    }
}

/// The clock a frame window runs on.
///
/// Only an interface, because the two answers to "when does this window close" have to run the same code:
/// production ticks at display rate, a test hands itself every tick and so never waits on a wall clock.
@MainActor
protocol ChatFrameClock: AnyObject {
    /// Deliver one tick per frame window until `stop`. Calling this while already running does nothing, so the
    /// owner can re-assert what it wants rather than track the clock's state of its own.
    func start(onTick: @escaping @MainActor () -> Void)

    /// Stop ticking: the window is closed and nothing is waiting on it.
    func stop()
}

/// The production clock — one tick per display frame, about 1/60 s, and only while a window is open.
@MainActor
final class ChatFrameTicker: ChatFrameClock {
    /// One frame at 60 Hz. `DESIGN.md` asks for 一帧一次, so the number is the display's, not one tuned per
    /// screen or per test; the slower a handset's frames come, the fewer publishes this asks of it.
    static let frameWindow: Duration = .seconds(1.0 / 60.0)

    private let interval: Duration
    private var task: Task<Void, Never>?

    init(interval: Duration = ChatFrameTicker.frameWindow) {
        self.interval = interval
    }

    convenience init() {
        self.init(interval: Self.frameWindow)
    }

    func start(onTick: @escaping @MainActor () -> Void) {
        guard task == nil else { return }
        let interval = self.interval
        // Inherited from this `@MainActor` method, so `onTick` needs no hop and the window closes on the same
        // actor that opened it.
        task = Task {
            while !Task.isCancelled {
                try? await Task.sleep(for: interval)
                guard !Task.isCancelled else { break }
                onTick()
            }
        }
    }

    func stop() {
        task?.cancel()
        task = nil
    }

    /// The other half of `stop()`. The task holds no reference back to this object and its tick holds the
    /// window's owner weakly, so an owner deallocated mid-window leaves nobody to call `stop()` — and a loop
    /// waking on the display's cadence for a screen that is gone is worse than the update it was saving.
    deinit {
        task?.cancel()
    }
}
