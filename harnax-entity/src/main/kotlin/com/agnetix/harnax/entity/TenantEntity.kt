package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

@Schema(description = "Tenant entity")
class TenantEntity : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "Tenant ID")
    var id: Long = 0

    @Schema(description = "Tenant name")
    var name: String = ""

    @Schema(description = "Status (0:disabled, 1:enabled)")
    var status: Int = 1

    @Schema(description = "Creator")
    var creator: String = ""

    @Schema(description = "Whether active (0:deleted, 1:active)")
    var active: Int = 1

    @Schema(description = "Creation time")
    var createTime: LocalDateTime? = null

    @Schema(description = "Update time")
    var updateTime: LocalDateTime? = null
}
