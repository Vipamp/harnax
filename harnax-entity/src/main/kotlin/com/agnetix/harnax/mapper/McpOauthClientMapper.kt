package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.McpOauthClient
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * McpOauthClient Mapper interface
 */
@Mapper
interface McpOauthClientMapper {

    /**
     * Reuse lookup for discovery: one tenant talks to one authorization server with one registration,
     * so several MCP servers behind the same issuer share this row. The lowest id wins to keep the
     * choice deterministic when an issuer ends up with more than one client.
     */
    fun selectByTenantAndIssuer(
        @Param("tenantId") tenantId: Long,
        @Param("issuer") issuer: String,
    ): McpOauthClient?

    fun insert(client: McpOauthClient): Int

    fun updateById(client: McpOauthClient): Int
}
