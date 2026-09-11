package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.McpCallLog
import org.apache.ibatis.annotations.Mapper

/**
 * McpCallLog Mapper interface
 */
@Mapper
interface McpCallLogMapper {

    fun insert(log: McpCallLog): Int
}
