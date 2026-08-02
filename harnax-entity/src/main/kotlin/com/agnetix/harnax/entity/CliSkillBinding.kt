package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * CLI-Skill binding entity.
 * Associates a CLI tool with skills that teach agents how to use it.
 * Skills bound here are merged into the agent's skill set at runtime.
 */
@Schema(description = "CLI Skill Binding entity")
class CliSkillBinding : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "Binding ID")
    var id: Long = 0

    @Schema(description = "FK to cli.id")
    var cliId: Long = 0

    @Schema(description = "FK to skill.id")
    var skillId: Long = 0

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Update time")
    var updateTime: LocalDateTime = LocalDateTime.now()
}
