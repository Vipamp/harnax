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

    /// Row slots the server has handed over across every page absorbed, duplicates and all.
    ///
    /// This is what ends paging rather than `elements.count`, because the rows on screen can permanently fall
    /// short of `total`: a concurrent write shifts a row past the page boundary, and a row whose id is missing
    /// is dropped by the de-dup in `append` — `nil` matches `nil`, so the second one is never keyable. Either
    /// way the last visible row would keep asking for a page that holds nothing new.
    public private(set) var served: Int

    public init(pageSize: Int) {
        self.elements = []
        self.pageNum = 0
        self.total = 0
        self.served = 0
        self.pageSize = pageSize
    }

    public var hasMore: Bool { served < total }
    public var isEmpty: Bool { elements.isEmpty }

    /// Pull-to-refresh and the first load both discard what came before.
    public mutating func replace(with page: Page<Element>) {
        elements = page.records
        pageNum = page.pageNum
        total = page.total
        served = page.records.count
    }
}

public extension PagedState where Element: Identifiable, Element.ID == Int64? {
    /// Drops one row after the server confirmed its deletion, and takes one off the total so `hasMore`
    /// does not keep promising a page that has shrunk out of existence.
    mutating func removeRow(id: Int64) {
        let before = elements.count
        elements.removeAll { $0.id == id }
        if elements.count != before { total = max(0, total - 1) }
    }
}

public extension PagedState where Element: Identifiable {
    /// Appends and drops rows already on screen. `List` keys on `id`, so a duplicated row from a shifted
    /// page would otherwise render twice under the same identity.
    mutating func append(with page: Page<Element>) {
        let seen = Set(elements.map(\.id))
        served += page.records.count
        elements.append(contentsOf: page.records.filter { !seen.contains($0.id) })
        pageNum = max(pageNum, page.pageNum)
        total = page.total
    }
}
