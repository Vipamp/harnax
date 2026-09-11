package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.McpServer
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * McpServer Mapper interface
 */
@Mapper
interface McpServerMapper {

    // ==================== Basic CRUD Methods ====================

    fun selectById(@Param("id") id: Long): McpServer?

    fun selectByIds(@Param("ids") ids: List<Long>): List<McpServer>

    fun insert(mcpserver: McpServer): Int

    fun updateById(mcpserver: McpServer): Int

    fun deleteById(@Param("id") id: Long): Int

    // ==================== Custom Query Methods ====================

    /**
     * Tenant-scoped list query: [tenantId] is pushed into the SQL when non-null, mirroring
     * `selectCliList`.
     */
    fun selectMcpServerList(
        @Param("keyword") keyword: String?,
        @Param("status") status: Int?,
        @Param("type") type: String?,
        @Param("currentUsername") currentUsername: String,
        @Param("tenantId") tenantId: Long? = null,
    ): List<McpServer>

    /**
     * Name uniqueness lookup. [tenantId] is pushed into the SQL when non-null, mirroring
     * `SkillRepositoryMapper.selectByName`: uniqueness is per tenant, which is what
     * `uk_mcp_server_tenant_active_name` enforces.
     */
    fun selectByName(@Param("name") name: String, @Param("tenantId") tenantId: Long? = null): McpServer?

    fun updateStatus(
        @Param("id") id: Long,
        @Param("status") status: Int,
    ): Int

    /**
     * Rewrite the OAuth config column and nothing else.
     *
     * Discovery writes the issuer it found back to this column, and `updateById` would do it by
     * writing every column of a row read moments earlier: an edit the administrator made in between
     * would disappear with it, and `headers` / `env_params` hold ciphertext that must never be
     * round-tripped through an object this call did not build. The caller resolves [id] through the
     * tenant-guarded read.
     */
    fun updateOAuthConfig(
        @Param("id") id: Long,
        @Param("oauthConfig") oauthConfig: String,
    ): Int
}
