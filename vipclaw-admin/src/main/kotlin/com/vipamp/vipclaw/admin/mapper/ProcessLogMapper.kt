package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.ProcessLogEntity
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * ProcessLogEntity Mapper 接口
 *
 * @author vipamp
 * @since 2026-03-18
*/
@Mapper
interface ProcessLogMapper {

    // ==================== 基础 CRUD 方法 ====================

    fun selectById(@Param("id") id: Long): ProcessLogEntity?

    fun insert(processlogentity: ProcessLogEntity): Int

    fun updateById(processlogentity: ProcessLogEntity): Int

    fun deleteById(@Param("id") id: Long): Int

    // ==================== 自定义查询方法 ====================
}
