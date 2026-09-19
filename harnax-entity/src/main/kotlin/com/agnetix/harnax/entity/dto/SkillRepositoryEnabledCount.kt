package com.agnetix.harnax.entity.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * How many skills a source still holds in the enabled state.
 *
 * A projection because the read behind it groups the `skill` table by `repository_id`: the source
 * list needs this to say which delete button is dead and by how much, and a `Skill` row per source
 * would fetch every skill's content to answer with one number. Mutable with a no-arg constructor so
 * MyBatis maps it by setter.
 */
@Schema(description = "Enabled skill count for one source")
class SkillRepositoryEnabledCount {

    @Schema(description = "Repository id the count belongs to")
    var repositoryId: Long = 0

    @Schema(description = "Skills that are active and enabled")
    var enabledCount: Int = 0
}
