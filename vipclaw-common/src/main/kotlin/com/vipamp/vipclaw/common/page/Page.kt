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
    var pageNum: Long = 1,
    var pageSize: Long = 10,
    var total: Long = 0,
    var records: List<T> = emptyList()
) {
    /**
     * 总页数
     */
    var pages: Long = 0
        get() = if (pageSize > 0) (total + pageSize - 1) / pageSize else 0

    /**
     * 是否有上一页
     */
    val hasPrevious: Boolean
        get() = pageNum > 1

    /**
     * 是否有下一页
     */
    val hasNext: Boolean
        get() = pageNum < pages

    companion object {
        /**
         * 从 PageInfo 转换为 Page
         */
        fun <T> fromPageInfo(list: List<T>): Page<T> {
            val pageInfo = PageInfo(list)
            return Page(
                pageNum = pageInfo.pageNum.toLong(),
                pageSize = pageInfo.pageSize.toLong(),
                total = pageInfo.total,
                records = pageInfo.list
            )
        }
    }
}

/**
 * 分页对象转换扩展函数
 * 将 Page<Entity> 转换为 Page<Response>
 *
 * @param transform 实体到响应对象的转换函数
 * @return 转换后的分页对象
 */
fun <T, R> Page<T>.mapRecords(transform: (T) -> R): Page<R> {
    return Page<R>(
        pageNum = this.pageNum,
        pageSize = this.pageSize,
        total = this.total,
        records = this.records.map(transform)
    )
}
