package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.McpUserCredential
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * McpUserCredential Mapper interface
 */
@Mapper
interface McpUserCredentialMapper {

    /**
     * The grant for one user on one MCP server. [tenantId] is part of the key rather than an added
     * filter: the delivery path resolves a session back to its owner, and without the tenant in the
     * lookup a colliding (user_id, mcp_id) pair across tenants would answer.
     */
    fun selectByUserAndMcp(
        @Param("tenantId") tenantId: Long,
        @Param("userId") userId: Long,
        @Param("mcpId") mcpId: Long,
    ): McpUserCredential?

    fun insert(credential: McpUserCredential): Int

    /**
     * Full-row update including the ciphertexts and the cleared ones: revoke has to be able to write
     * NULL back, which a `<if test='x != null'>` SET list can never do.
     */
    fun updateById(credential: McpUserCredential): Int

    /**
     * Hard delete, used when the MCP server itself goes away. Rows are otherwise updated in place, so
     * `mcp_user_credential` has no `active` column and no soft-delete path.
     */
    fun deleteByMcpId(@Param("mcpId") mcpId: Long): Int
}
