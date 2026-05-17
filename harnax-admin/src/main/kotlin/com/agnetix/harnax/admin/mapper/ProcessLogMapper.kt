package com.agnetix.harnax.admin.mapper

import com.agnetix.harnax.admin.entity.ProcessLogEntity
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * ProcessLogEntity Mapper interface
 */
@Mapper
interface ProcessLogMapper {

    // ==================== Basic CRUD methods ====================

    fun selectById(@Param("id") id: Long): ProcessLogEntity?

    fun insert(processlogentity: ProcessLogEntity): Int

    fun updateById(processlogentity: ProcessLogEntity): Int

    fun deleteById(@Param("id") id: Long): Int

    // ==================== Custom Query Methods ====================
}
