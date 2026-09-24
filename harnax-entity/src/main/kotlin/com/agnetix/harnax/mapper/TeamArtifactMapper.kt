package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.TeamArtifact
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface TeamArtifactMapper {

    fun insert(artifact: TeamArtifact): Int

    /**
     * Look up an artifact by its opaque reference. The caller must still compare the row's tenant and
     * session against the requester — [com.agnetix.harnax.entity.TeamArtifact.fileId] is a reference,
     * not a credential.
     */
    fun selectByFileId(@Param("fileId") fileId: String): TeamArtifact?

    fun selectBySessionId(@Param("sessionId") sessionId: String): List<TeamArtifact>

    /**
     * Drop the metadata row of one artifact. Only called after its object is gone (or when there is no
     * object to speak of), so no row ever outlives the bytes it names.
     */
    fun deleteById(@Param("id") id: Long): Int
}
