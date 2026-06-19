package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.MpSession
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * Mobile session Mapper interface
 */
@Mapper
interface MpSessionMapper {

    fun selectById(@Param("id") id: Long): MpSession?

    fun selectByUserId(@Param("userId") userId: Long): List<MpSession>

    fun insert(session: MpSession): Int

    fun updateById(session: MpSession): Int

    fun deleteById(@Param("id") id: Long): Int

    fun selectByIdAndUserId(
        @Param("id") id: Long,
        @Param("userId") userId: Long,
    ): MpSession?
}
