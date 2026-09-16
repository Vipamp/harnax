package com.agnetix.harnax.scheduler.dto

import com.github.pagehelper.PageInfo

/**
 * Custom pagination class
 * Based on PageHelper's PageInfo
 *
 * The seven serialized keys (`pageNum`, `pageSize`, `total`, `records`, `pages`, `hasPrevious`, `hasNext`)
 * are the envelope the webui, the mini-program and the CLI all read; `PageContractTest` pins them. This
 * class is a deliberate copy of `harnax-admin`'s own `dto.Page` — that one still serves every other admin
 * list, so nothing here can move instead of being duplicated.
 */
data class Page<T>(
    var pageNum: Long = 1,
    var pageSize: Long = 10,
    var total: Long = 0,
    var records: List<T> = emptyList(),
) {
    /**
     * Total pages
     */
    val pages: Long
        get() = if (pageSize > 0) (total + pageSize - 1) / pageSize else 0

    /**
     * Whether has previous page
     */
    val hasPrevious: Boolean
        get() = pageNum > 1

    /**
     * Whether has next page
     */
    val hasNext: Boolean
        get() = pageNum < pages

    companion object {
        /**
         * Convert from PageInfo to Page
         */
        fun <T> fromPageInfo(list: List<T>): Page<T> {
            val pageInfo = PageInfo(list)
            return Page(
                pageNum = pageInfo.pageNum.toLong(),
                pageSize = pageInfo.pageSize.toLong(),
                total = pageInfo.total,
                records = pageInfo.list,
            )
        }
    }
}

/**
 * Pagination object transformation extension function
 * Converts Page<Entity> to Page<Response>
 *
 * @param transform Entity to response object transformation function
 * @return Transformed pagination object
 */
fun <T, R> Page<T>.mapRecords(transform: (T) -> R): Page<R> = Page<R>(
    pageNum = this.pageNum,
    pageSize = this.pageSize,
    total = this.total,
    records = this.records.map(transform),
)
