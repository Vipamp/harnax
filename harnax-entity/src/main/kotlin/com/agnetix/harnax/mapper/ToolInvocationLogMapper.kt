package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.ToolInvocationLog
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * Tool invocation detail writes and the retention sweep the rollup needs.
 *
 * Every read here names a tenant, and `tenantId` is a non-null `Long` where a read is tenant-scoped: there
 * is no value that means "all tenants". The rollup's three statements are the exception and are commented
 * at their own methods, because they run over the whole server rather than over one caller's window.
 *
 * SQL lives in `resources/mapper/ToolInvocationLogMapper.xml`.
 */
@Mapper
interface ToolInvocationLogMapper {

    /**
     * Append one batch of invocation rows.
     *
     * @param logs Rows to write; an empty list must not reach this method — the caller returns early
     * @return Number of rows written
     */
    fun batchInsert(
        @Param("list") logs: List<ToolInvocationLog>,
    ): Int
}
