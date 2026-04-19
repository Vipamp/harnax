package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.Channel
import org.apache.ibatis.annotations.*

/**
 * Channel Mapper 接口
 *
 * @author vipamp
 * @since 2026-04-08
 */
@Mapper
interface ChannelMapper {

    // ==================== 基础 CRUD 方法 ====================

    @Select("SELECT * FROM channel WHERE id = #{id} LIMIT 1")
    fun selectById(@Param("id") id: Long): Channel?

    @Insert(
        """
        INSERT INTO channel (
            name, description, type, callback_url, callback_key,
            status, active, create_time, update_time
        ) VALUES (
            #{name}, #{description}, #{type}, #{callbackUrl}, #{callbackKey},
            #{status}, #{active}, #{createTime}, #{updateTime}
        )
        """
    )
    @Options(useGeneratedKeys = true, keyProperty = "id")
    fun insert(channel: Channel): Int

    @Update(
        """
        UPDATE channel SET
            name = #{name},
            description = #{description},
            type = #{type},
            callback_url = #{callbackUrl},
            callback_key = #{callbackKey},
            status = #{status},
            active = #{active},
            update_time = #{updateTime}
        WHERE id = #{id}
        """
    )
    fun updateById(channel: Channel): Int

    @Update("UPDATE channel SET active = 0 WHERE id = #{id}")
    fun deleteById(@Param("id") id: Long): Int

    // ==================== 自定义查询方法 ====================

    @Select("""
        <script>
            SELECT * FROM channel 
            WHERE active = 1
            <if test='keyword != null and keyword != ""'>
                AND (name LIKE CONCAT('%', #{keyword}, '%') 
                     OR description LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            <if test='type != null and type != ""'>
                AND type = #{type}
            </if>
            <if test='status != null'>
                AND status = #{status}
            </if>
            ORDER BY create_time DESC
        </script>
    """)
    fun selectChannelList(
        @Param("keyword") keyword: String?,
        @Param("type") type: String?,
        @Param("status") status: Int?
    ): List<Channel>

    @Select("SELECT * FROM channel WHERE callback_key = #{callbackKey} AND active = 1 LIMIT 1")
    fun selectByCallbackKey(@Param("callbackKey") callbackKey: String): Channel?
}
