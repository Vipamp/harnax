package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

data class SessionChatUpdateRequest(
    @Schema(description = "Enable deep thinking")
    val enableThink: Boolean? = false,

    @Schema(description = "Enable web search")
    val enableSearch: Boolean? = false,

    @Schema(description = "Enable planning")
    val enablePlan: Boolean? = false,

    @Schema(description = "Permission mode (DEFAULT/ACCEPT_EDITS/EXPLORE/BYPASS/DONT_ASK)")
    val permissionMode: String? = null,
)
