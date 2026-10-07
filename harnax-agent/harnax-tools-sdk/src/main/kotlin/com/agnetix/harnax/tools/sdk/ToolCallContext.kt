package com.agnetix.harnax.tools.sdk

/**
 * Attribution of one tool call: which identity the call runs under.
 *
 * @Author: heqingsong
 * @Date: 2026/4/14
 * @Description: ToolCallContext
 * @Project: harnax
 */
interface ToolCallContext

data class UserIdentifier(
    /** End user behind the call; null when the caller is a service or a key without an owner. */
    val userId: Long? = null,
) : ToolCallContext
