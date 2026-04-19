package com.vipamp.vipclaw.common.page

import com.github.pagehelper.PageInfo

/**
 * 自定义分页类
 * 基于 PageHelper 的 PageInfo
 *
 * @author vipamp
 * @since 2026-04-19
 */
data class Page<T>(
    var current: Long = 1,
    var size: Long = 10,
    var total: Long = 0,
    var records: List<T> = emptyList()
) {
    /**
     * 总页数
     */
    var pages: Long =0
        get() = if (size > 0) (total + size - 1) / size else 0

    /**
     * 是否有上一页
     */
    val hasPrevious: Boolean
        get() = current > 1

    /**
     * 是否有下一页
     */
    val hasNext: Boolean
        get() = current < pages

    companion object {
        /**
         * 从 PageInfo 转换为 Page
         */
        fun <T> fromPageInfo(pageInfo: PageInfo<T>): Page<T> {
            return Page(
                current = pageInfo.pageNum.toLong(),
                size = pageInfo.pageSize.toLong(),
                total = pageInfo.total,
                records = pageInfo.list
            )
        }
    }
}
