import SwiftUI

/// The arrows that set a row's place in its list: an up chevron and a down chevron, plus the VoiceOver actions
/// that do the same without hunting for them.
///
/// Order is a fact the backend already stores — every binding table is deleted and re-inserted in request
/// order (`AgentServiceImpl.kt:400`-`:404`, `:439`-`:442`, `:528`-`:535`, `:557`-`:560`) and read back
/// `ORDER BY id`, and the team lead shows its members in that order (`TeamMemberMapper.xml:16`) — so a list
/// the operator can arrange is the whole feature and no endpoint is involved (`specs/01-agent-team.md` 注意点 6).
///
/// Why handles and not `.onMove`: the wizard rows are `HXGroupCard` cards in a `ScrollView`/`VStack`, not
/// `List` rows. `onMove` fires only inside a `List` in edit mode, so attaching it here would be dead code, and
/// restyling five wizard steps into a `List` would change every one of them for the sake of one gesture. The
/// pair therefore moves rows with the same index semantics `onMove` uses — the destination counts the list
/// with the moved row already taken out — so a row that can be dragged later does not need a new move API.
///
/// Both controls are icon-only, so both carry the catalogue's word for themselves (`common.order.up`,
/// `common.order.down`): the icon-only gate charges a kit component double, because a silent glyph here is
/// silent at every call site at once.
public struct HXOrderHandles: View {
    private let index: Int
    private let count: Int
    private let move: (Int, Int) -> Void

    /// - Parameters:
    ///   - index: the row's place in the list, counted from zero.
    ///   - count: how many rows the list holds. Below two there is nothing to arrange, so nothing is drawn —
    ///     an arrow that could only ever be a no-op promises a move it cannot make.
    ///   - move: the list's own move, called with `(from, to)` in `onMove` indexes.
    public init(index: Int, count: Int, move: @escaping (Int, Int) -> Void) {
        self.index = index
        self.count = count
        self.move = move
    }

    public var body: some View {
        if count > 1 {
            HStack(spacing: 2) {
                Button {
                    move(index, index - 1)
                } label: {
                    Image(systemName: "chevron.up")
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(verbatim: hx("common.order.up")))
                .disabled(!canMoveUp)
                Button {
                    move(index, index + 1)
                } label: {
                    Image(systemName: "chevron.down")
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(verbatim: hx("common.order.down")))
                .disabled(!canMoveDown)
            }
            .font(.footnote)
            .foregroundStyle(Color.hx(.textSecondary))
        }
    }

    private var canMoveUp: Bool { index > 0 && count > 1 }
    private var canMoveDown: Bool { index < count - 1 }
}

/// The same two moves as named VoiceOver actions, hung on the card's own control so the rearrangement does not
/// depend on finding a 20-point chevron.
///
/// Attach it to the row's single-element control — the main picker button — and not to the card: a `VStack` of
/// buttons is a container, not an element, and an action added to a container reaches no rotor.
public struct HXOrderActionsModifier: ViewModifier {
    private let index: Int
    private let count: Int
    private let move: (Int, Int) -> Void

    public init(index: Int, count: Int, move: @escaping (Int, Int) -> Void) {
        self.index = index
        self.count = count
        self.move = move
    }

    public func body(content: Content) -> some View {
        if index > 0, index < count - 1 {
            content
                .accessibilityAction(named: Text(verbatim: hx("common.order.up"))) { move(index, index - 1) }
                .accessibilityAction(named: Text(verbatim: hx("common.order.down"))) { move(index, index + 1) }
        } else if index > 0 {
            content
                .accessibilityAction(named: Text(verbatim: hx("common.order.up"))) { move(index, index - 1) }
        } else if index < count - 1 {
            content
                .accessibilityAction(named: Text(verbatim: hx("common.order.down"))) { move(index, index + 1) }
        } else {
            content
        }
    }
}

public extension View {
    /// Adds 「上移」／「下移」 to the rotor of a control that stands for one row of an arranged list.
    func hxOrderActions(index: Int, count: Int, move: @escaping (Int, Int) -> Void) -> some View {
        modifier(HXOrderActionsModifier(index: index, count: count, move: move))
    }
}
