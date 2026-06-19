package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.MpChatMessage
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * Mobile chat message Mapper interface
 */
@Mapper
interface MpChatMessageMapper {

    fun selectBySessionId(@Param("sessionId") sessionId: Long): List<MpChatMessage>

    fun batchInsert(@Param("list") messages: List<MpChatMessage>): Int

    fun deleteBySessionId(@Param("sessionId") sessionId: Long): Int

    fun countBySessionId(@Param("sessionId") sessionId: Long): Int
}
