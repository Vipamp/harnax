import Foundation

/// Accumulated rows behind a paginated list screen, plus the one decision that screen has to make:
/// whether a further page exists.
///
/// `hasMore` compares the accumulated count against `total` instead of reading the envelope's `hasNext`,
/// because the backend recomputes that flag from the single page it just fetched rather than from what
/// the client is holding.
public struct PagedState<Element: Decodable & Sendable>: Sendable {
    public private(set) var elements: [Element]
    public private(set) var pageNum: Int
    public private(set) var total: Int
    public let pageSize: Int

    public init(pageSize: Int) {
        self.elements = []
        self.pageNum = 0
        self.total = 0
        self.pageSize = pageSize
    }

    public var hasMore: Bool { elements.count < total }
    public var isEmpty: Bool { elements.isEmpty }

    /// Pull-to-refresh and the first load both discard what came before.
    public mutating func replace(with page: Page<Element>) {
        elements = page.records
        pageNum = page.pageNum
        total = page.total
    }
}

public extension PagedState where Element: Identifiable {
    /// Appends and drops rows already on screen. `List` keys on `id`, so a duplicated row from a shifted
    /// page would otherwise render twice under the same identity.
    mutating func append(with page: Page<Element>) {
        let seen = Set(elements.map(\.id))
        elements.append(contentsOf: page.records.filter { !seen.contains($0.id) })
        pageNum = max(pageNum, page.pageNum)
        total = page.total
    }
}
