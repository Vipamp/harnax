package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.Model
import org.apache.ibatis.annotations.*

/**
 * Model Mapper 接口
 *
 * @author vipamp
 * @since 2026-03-18
 */
@Mapper
interface ModelMapper {

    // ==================== 基础 CRUD 方法 ====================

    @Select("SELECT * FROM model WHERE id = #{id} LIMIT 1")
    fun selectById(@Param("id") id: Long): Model?

    @Insert(
        """
        INSERT INTO model (
            name, model_name, provider_id, description, model_type, support_internet, support_reasoning, support_tool, support_mcp, support_vision, price, status, is_public, active, create_time, update_time
        ) VALUES (
            #{name}, #{modelName}, #{providerId}, #{description}, #{modelType}, #{supportInternet}, #{supportReasoning}, #{supportTool}, #{supportMcp}, #{supportVision}, #{price}, #{status}, #{isPublic}, #{active}, #{createTime}, #{updateTime}
        )
        """
    )
    @Options(useGeneratedKeys = true, keyProperty = "id")
    fun insert(model: Model): Int

    @Update(
        """
        UPDATE model SET
            name = #{name},
            model_name = #{modelName},
            provider_id = #{providerId},
            description = #{description},
            model_type = #{modelType},
            support_internet = #{supportInternet},
            support_reasoning = #{supportReasoning},
            support_tool = #{supportTool},
            support_mcp = #{supportMcp},
            support_vision = #{supportVision},
            price = #{price},
            status = #{status},
            is_public = #{isPublic},
            active = #{active},
            update_time = #{updateTime}
        WHERE id = #{id}
        """
    )
    fun updateById(model: Model): Int

    @Update("UPDATE model SET active = 0 WHERE id = #{id}")
    fun deleteById(@Param("id") id: Long): Int

    // ==================== 自定义查询方法 ====================
    @Select(
        """
        <script>
            SELECT * FROM model 
            WHERE active = 1
            AND (is_public = 1 OR creator = #{currentUsername})
            <if test='name != null and name != ""'>
                AND (name LIKE CONCAT('%', #{name}, '%') 
                     OR model_name LIKE CONCAT('%', #{name}, '%'))
            </if>
            <if test='providerId != null'>
                AND provider_id = #{providerId}
            </if>
            <if test='modelType != null and modelType != ""'>
                AND model_type = #{modelType}
            </if>
            <if test='status != null'>
                AND status = #{status}
            </if>
            <if test='tags != null and tags != ""'>
                AND (
                    <foreach collection='tags' item='tag' separator=' OR '>
                        <choose>
                            <when test='tag == "internet"'>support_internet = 1</when>
                            <when test='tag == "reasoning"'>support_reasoning = 1</when>
                            <when test='tag == "tool"'>support_tool = 1</when>
                            <when test='tag == "mcp"'>support_mcp = 1</when>
                            <when test='tag == "vision"'>support_vision = 1</when>
                        </choose>
                    </foreach>
                )
            </if>
            <if test='minPrice != null'>
                AND price >= #{minPrice}
            </if>
            <if test='maxPrice != null'>
                AND price &lt;= #{maxPrice}
            </if>
            ORDER BY status DESC, update_time DESC
        </script>
    """
    )
    fun selectModelList(
        @Param("name") name: String?,
        @Param("providerId") providerId: Long?,
        @Param("modelType") modelType: String?,
        @Param("status") status: Int?,
        @Param("tags") tags: List<String>?,
        @Param("minPrice") minPrice: Double?,
        @Param("maxPrice") maxPrice: Double?,
        @Param("currentUsername") currentUsername: String
    ): List<Model>

    @Select("SELECT COUNT(*) FROM model WHERE provider_id = #{providerId} AND name = #{name} AND active = 1")
    fun countByProviderIdAndName(@Param("providerId") providerId: Long, @Param("name") name: String): Int

    @Select("SELECT COUNT(*) FROM model WHERE provider_id = #{providerId} AND model_name = #{modelName} AND active = 1")
    fun countByProviderIdAndModelName(@Param("providerId") providerId: Long, @Param("modelName") modelName: String): Int

    @Select("SELECT COUNT(*) FROM model WHERE provider_id = #{providerId} AND status = 1 AND active = 1")
    fun countActiveModelsByProviderId(@Param("providerId") providerId: Long): Int

    @Select("SELECT * FROM model WHERE id = #{id} AND active = 1 LIMIT 1")
    fun selectActiveById(@Param("id") id: Long): Model?
}
