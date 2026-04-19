package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.ModelProvider
import org.apache.ibatis.annotations.*

/**
 * ModelProvider Mapper 接口
 *
 * @author vipamp
 * @since 2026-03-18
 */
@Mapper
interface ModelProviderMapper {

    // ==================== 基础 CRUD 方法 ====================

    @Select("SELECT * FROM model_provider WHERE id = #{id} LIMIT 1")
    fun selectById(@Param("id") id: Long): ModelProvider?

    @Insert(
        """
        INSERT INTO model_provider (
            name, display_name, api_key, base_url, status, is_public, creator, active, create_time, update_time
        ) VALUES (
            #{name}, #{displayName}, #{apiKey}, #{baseUrl}, #{status}, #{isPublic}, #{creator}, #{active}, #{createTime}, #{updateTime}
        )
        """
    )
    @Options(useGeneratedKeys = true, keyProperty = "id")
    fun insert(modelprovider: ModelProvider): Int

    @Update(
        """
        UPDATE model_provider SET
            name = #{name},
            display_name = #{displayName},
            api_key = #{apiKey},
            base_url = #{baseUrl},
            status = #{status},
            is_public = #{isPublic},
            creator = #{creator},
            active = #{active},
            update_time = #{updateTime}
        WHERE id = #{id}
        """
    )
    fun updateById(modelprovider: ModelProvider): Int

    @Update("UPDATE model_provider SET active = 0 WHERE id = #{id}")
    fun deleteById(@Param("id") id: Long): Int

    // ==================== 自定义查询方法 ====================
    @Select(
        """
        <script>
            SELECT * FROM model_provider 
            WHERE active = 1
            AND (is_public = 1 OR creator = #{currentUsername})
            <if test='name != null and name != ""'>
                AND (name LIKE CONCAT('%', #{name}, '%') 
                     OR display_name LIKE CONCAT('%', #{name}, '%'))
            </if>
            <if test='status != null'>
                AND status = #{status}
            </if>
            ORDER BY status DESC, update_time DESC
        </script>
    """
    )
    fun selectModelProviderList(
        @Param("name") name: String?,
        @Param("status") status: Int?,
        @Param("currentUsername") currentUsername: String
    ): List<ModelProvider>

    @Select("SELECT COUNT(*) FROM model_provider WHERE name = #{name} AND active = 1")
    fun countByName(@Param("name") name: String): Int

    @Select("SELECT * FROM model_provider WHERE id = #{id} AND active = 1 LIMIT 1")
    fun selectActiveById(@Param("id") id: Long): ModelProvider?
}
