import SwiftUI

/// Wrapping pill row — the mockup's `.tags` block under a record card. Children keep their own width and
/// this type decides how many fit per line, which is what a chip cloud has to do and `HStack` cannot.
public struct HXFlow: Layout {
    public var spacing: CGFloat = 6

    public init(spacing: CGFloat = 6) {
        self.spacing = spacing
    }

    public func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) -> CGSize {
        let limit = proposal.width ?? .infinity
        let rows = plan(limit: limit, subviews: subviews)
        let width = rows.map(\.width).max() ?? 0
        let height = rows.reduce(0) { partial, row in partial + row.height }
            + spacing * CGFloat(max(rows.count - 1, 0))
        return CGSize(width: min(width, limit), height: height)
    }

    public func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) {
        var y = bounds.minY
        for row in plan(limit: bounds.width, subviews: subviews) {
            var x = bounds.minX
            for index in row.indices {
                let size = subviews[index].sizeThatFits(.unspecified)
                subviews[index].place(
                    at: CGPoint(x: x, y: y),
                    anchor: .topLeading,
                    proposal: ProposedViewSize(size)
                )
                x += size.width + spacing
            }
            y += row.height + spacing
        }
    }

    private struct Row {
        var indices: [Int] = []
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    private func plan(limit: CGFloat, subviews: Subviews) -> [Row] {
        var rows: [Row] = [Row()]
        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(.unspecified)
            let last = rows[rows.count - 1]
            let appendedWidth = last.indices.isEmpty ? size.width : last.width + spacing + size.width
            if appendedWidth > limit, !last.indices.isEmpty {
                rows.append(Row(indices: [index], width: size.width, height: size.height))
                continue
            }
            var opened = last
            opened.indices.append(index)
            opened.width = appendedWidth
            opened.height = max(opened.height, size.height)
            rows[rows.count - 1] = opened
        }
        return rows.filter { !$0.indices.isEmpty }
    }
}
