package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

@Schema(description = "Skill entity")
class Skill : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "ID")
    var id: Long = 0

    @Schema(description = "Tenant ID")
    var tenantId: Long = 1

    @Schema(description = "Skill name")
    var name: String = ""

    @Schema(description = "Repository ID")
    var repositoryId: Long = 0

    @Schema(description = "Skill description")
    var description: String = ""

    @Schema(description = "skill.md content")
    var skillmd: String = ""

    @Schema(description = "Resource information")
    var resources: String = ""

    @Schema(description = "Status (0:disabled, 1:enabled)")
    var status: Int = 1

    @Schema(description = "Public status (0:no, 1:yes)")
    var isPublic: Int = 1

    @Schema(description = "Creator")
    var creator: String = ""

    @Schema(description = "Active status (0:deleted, 1:active)")
    var active: Int = 1

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Update time")
    var updateTime: LocalDateTime = LocalDateTime.now()
}
