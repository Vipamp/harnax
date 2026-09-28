import Foundation

/// Wire shape of `ResultVo<Page<T>>`.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/Page.kt`. The backend also
/// serialises the computed `pages` / `hasPrevious` / `hasNext`; iOS ignores them and derives
/// `hasMore` from `records.count` against `total`, because those three are recomputed server-side
/// from the page that was just fetched rather than from the accumulated list.
public struct Page<T: Decodable>: Decodable, Sendable where T: Sendable {
    public let pageNum: Int
    public let pageSize: Int
    public let total: Int
    public let records: [T]
}
