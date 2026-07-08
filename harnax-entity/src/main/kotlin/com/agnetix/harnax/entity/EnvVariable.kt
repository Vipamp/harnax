package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

@Schema(description = "Environment Variable entity")
class EnvVariable : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "ID")
    var id: Long = 0

    @Schema(description = "Tenant ID")
    var tenantId: Long = 1

    @Schema(description = "Environment variable key")
    var envKey: String = ""

    @Schema(description = "Environment variable value")
    var envValue: String = ""

    @Schema(description = "Description")
    var description: String? = null

    @Schema(description = "Sensitive flag (0: No, 1: Yes)")
    var sensitive: Int = 0

    @Schema(description = "Enabled status (0: Disabled, 1: Enabled)")
    var enabled: Int = 1

    @Schema(description = "Creator")
    var creator: String = ""

    @Schema(description = "Active status (0: Deleted, 1: Active)")
    var active: Int = 1

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Update time")
    var updateTime: LocalDateTime = LocalDateTime.now()
}
