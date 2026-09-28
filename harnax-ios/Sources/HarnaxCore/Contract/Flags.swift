import Foundation

/// Backend flags are `0`/`1` integers, not JSON booleans — `status`, `isPublic`, `needConfirm`,
/// `supportVision` and friends all follow that rule. The one exception in the whole surface is
/// `EnvVariable.sensitive`, which really is a boolean.
///
/// Decoding therefore keeps the raw `Int?` on the model (a `null` from the server must stay distinguishable
/// from a `0`) and exposes the flag through this reader.
public extension Optional where Wrapped == Int {
    var hxFlag: Bool { self == 1 }
}

public extension Bool {
    /// For the request side: a switch that has to be sent as `0`/`1`.
    var hxInt: Int { self ? 1 : 0 }
}

/// `thinkingMode` is a three-value field (`0` not supported, `1` optional, `2` required), and the backend
/// derives `supportReasoning` from it on the way in while the response side derives `tags` from the pair.
/// The two derivations live here so a form and a list row cannot disagree.
public enum ThinkingMode: Int, CaseIterable, Sendable {
    case off = 0
    case optional = 1
    case required = 2

    /// Old rows carry only `supportReasoning`; the web form reads them the same way.
    public static func resolve(stored mode: Int?, supportReasoning: Int?) -> ThinkingMode {
        if let mode, let found = ThinkingMode(rawValue: mode) { return found }
        return (supportReasoning ?? 0) >= 1 ? .optional : .off
    }

    public var supportReasoning: Int { rawValue >= 1 ? 1 : 0 }
}
